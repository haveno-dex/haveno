/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.support.dispute.arbitration;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.support.dispute.DisputeSummaryVerification;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.FileTransferReceiver;
import haveno.core.support.dispute.mediation.FileTransferSender;
import haveno.core.support.dispute.mediation.FileTransferSession;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.messages.SupportMessage;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.FileTransferPart;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.exception.ExceptionUtils;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Singleton
public final class ArbitrationManager extends DisputeManager<ArbitrationDisputeList> implements MessageListener, FileTransferSession.FtpCallback {

    private final ArbitratorManager arbitratorManager;

    private Map<String, Integer> reprocessDisputeClosedMessageCounts = new HashMap<>();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ArbitrationManager(P2PService p2PService,
                              TradeWalletService tradeWalletService,
                              XmrWalletService walletService,
                              XmrConnectionService xmrConnectionService,
                              CoreNotificationService notificationService,
                              ArbitratorManager arbitratorManager,
                              TradeManager tradeManager,
                              ClosedTradableManager closedTradableManager,
                              OpenOfferManager openOfferManager,
                              KeyRing keyRing,
                              ArbitrationDisputeListService arbitrationDisputeListService,
                              Config config,
                              PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, xmrConnectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, arbitrationDisputeListService, config, priceFeedService);
        this.arbitratorManager = arbitratorManager;
        HavenoUtils.arbitrationManager = this; // TODO: storing static reference, better way?
        p2PService.getNetworkNode().addMessageListener(this);   // listening for FileTransferPart message
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.ARBITRATION;
    }

    @Override
    public void onSupportMessage(SupportMessage message) {
        if (canProcessMessage(message)) {
            log.info("Received {} from {} with tradeId {} and uid {}",
                    message.getClass().getSimpleName(), message.getSenderNodeAddress(), message.getTradeId(), message.getUid());

            ThreadUtils.execute(() -> {
                if (message instanceof DisputeOpenedMessage) {
                    handleDisputeOpenedMessage((DisputeOpenedMessage) message);
                } else if (message instanceof ChatMessage) {
                    handleChatMessage((ChatMessage) message);
                } else if (message instanceof DisputeClosedMessage) {
                    handleDisputeClosedMessage((DisputeClosedMessage) message);
                } else {
                    log.warn("Unsupported message at dispatchMessage. message={}", message);
                }
            }, message.getTradeId());
        }
    }

    @Override
    public NodeAddress getAgentNodeAddress(Dispute dispute) {
        return dispute.getContract().getArbitratorNodeAddress();
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.ARBITRATION_MESSAGE;
    }

    @Override
    public void cleanupDisputes() {

        // remove disputes opened by arbitrator, which is not allowed
        Set<Dispute> toRemoves = new HashSet<>();
        List<Dispute> disputes = getDisputeList().getList();
        synchronized (disputes) {
            for (Dispute dispute : disputes) {

                // get dispute's trade
                final Trade trade = tradeManager.getTrade(dispute.getTradeId());
                if (trade == null) {
                    log.warn("Dispute trade {} does not exist", dispute.getTradeId());
                    return;
                }
    
                // collect dispute if owned by arbitrator
                if (dispute.getTraderPubKeyRing().equals(trade.getArbitrator().getPubKeyRing())) {
                    toRemoves.add(dispute);
                }
            }
        }
        for (Dispute toRemove : toRemoves) {
            log.warn("Removing invalid dispute opened by arbitrator, disputeId={}", toRemove.getTradeId(), toRemove.getId());
            getDisputeList().remove(toRemove);
        }
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.arbitrator").toLowerCase();
        String link = "https://docs.haveno.exchange/trading-rules.html#legacy-arbitration";
        return Res.get("support.initialInfo", role, role, link);
    }

    @Override
    protected String getDisputeIntroForPeer(String disputeInfo) {
        return Res.get("support.peerOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected String getDisputeIntroForDisputeCreator(String disputeInfo) {
        return Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        // Arbitrator is not used anymore.
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // received by both peers when arbitrator closes disputes
    @Override
    public void handleDisputeClosedMessage(DisputeClosedMessage disputeClosedMessage) {
        handleDisputeClosedMessage(disputeClosedMessage, true);
    }

    private void handleDisputeClosedMessage(DisputeClosedMessage disputeClosedMessage, boolean reprocessOnError) {

        // get dispute's trade
        final Trade trade = tradeManager.getTrade(disputeClosedMessage.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", disputeClosedMessage.getTradeId());
            return;
        }

        // try to process dispute closed message
        ThreadUtils.execute(() -> {
            ChatMessage chatMessage = null;
            Dispute dispute = null;
            synchronized (trade.getLock()) {
                try {
                    DisputeResult disputeResult = disputeClosedMessage.getDisputeResult();
                    chatMessage = disputeResult.getChatMessage();
                    checkNotNull(chatMessage, "chatMessage must not be null");
                    String tradeId = disputeResult.getTradeId();

                    log.info("Processing {} for {} {}", disputeClosedMessage.getClass().getSimpleName(), trade.getClass().getSimpleName(), disputeResult.getTradeId());

                    // get dispute
                    Optional<Dispute> disputeOptional = findDispute(disputeResult);
                    String uid = disputeClosedMessage.getUid();
                    if (!disputeOptional.isPresent()) {
                        log.warn("We got a dispute closed msg but we don't have a matching dispute. " +
                                "That might happen when we get the DisputeClosedMessage before the dispute was created. " +
                                "We try again after 2 sec. to apply the DisputeClosedMessage. TradeId = " + tradeId);
                        if (!delayMsgMap.containsKey(uid)) {
                            // We delay 2 sec. to be sure the comm. msg gets added first
                            Timer timer = UserThread.runAfter(() -> handleDisputeClosedMessage(disputeClosedMessage), 2);
                            delayMsgMap.put(uid, timer);
                        } else {
                            log.warn("We got a dispute closed msg after we already repeated to apply the message after a delay. " +
                                    "That should never happen. TradeId = " + tradeId);
                        }
                        return;
                    }
                    dispute = disputeOptional.get();

                    // verify arbitrator signature
                    String summaryText = chatMessage.getMessage();
                    if (summaryText == null || summaryText.isEmpty()) throw new IllegalArgumentException("Summary text for dispute is missing, tradeId=" + tradeId + (dispute == null ? "" : ", disputeId=" + dispute.getId()));
                    if (dispute != null) DisputeSummaryVerification.verifySignature(summaryText, dispute.getAgentPubKeyRing()); // use dispute's arbitrator pub key ring
                    else DisputeSummaryVerification.verifySignature(summaryText, arbitratorManager); // verify using registered arbitrator (will fail if arbitrator is unregistered)

                    // save dispute closed message for reprocessing
                    trade.getArbitrator().setDisputeClosedMessage(disputeClosedMessage);
                    requestPersistence(trade);

                    // verify arbitrator does not receive DisputeClosedMessage
                    if (keyRing.getPubKeyRing().equals(dispute.getAgentPubKeyRing())) {
                        log.error("Arbitrator received disputeResultMessage. That should never happen.");
                        trade.getArbitrator().setDisputeClosedMessage(null); // don't reprocess
                        return;
                    }

                    // set dispute state
                    cleanupRetryMap(uid);
                    synchronized (dispute.getChatMessages()) {
                        if (!dispute.getChatMessages().contains(chatMessage)) {
                            dispute.addAndPersistChatMessage(chatMessage);
                        } else {
                            log.warn("We got a dispute mail msg that we have already stored. TradeId = " + chatMessage.getTradeId());
                        }
                    }
                    dispute.setIsClosed();
                    if (dispute.disputeResultProperty().get() != null) {
                        log.info("We already got a dispute result, indicating the message was resent after updating multisig info. TradeId = " + tradeId);
                    }
                    dispute.setDisputeResult(disputeResult);

                    // update multisig hex
                    if (disputeClosedMessage.getUpdatedMultisigHex() != null) trade.getArbitrator().setUpdatedMultisigHex(disputeClosedMessage.getUpdatedMultisigHex());
                    if (trade.walletExists()) trade.importMultisigHex();

                    // sync and save wallet
                    if (!trade.isPayoutPublished()) trade.syncAndPollWallet();

                    // attempt to sign and publish dispute payout tx if given and not already published
                    if (!trade.isPayoutPublished() && disputeClosedMessage.getUnsignedPayoutTxHex() != null) {

                        // wait to sign and publish payout tx if defer flag set
                        if (disputeClosedMessage.isDeferPublishPayout()) {
                            log.info("Deferring signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                            for (int i = 0; i < 5; i++) {
                                if (trade.isPayoutPublished()) break;
                                HavenoUtils.waitFor(Trade.DEFER_PUBLISH_MS / 5);
                            }
                            if (!trade.isPayoutPublished()) trade.syncAndPollWallet();
                        }

                        // sign and publish dispute payout tx if peer still has not published
                        if (trade.isPayoutPublished()) {
                            log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                        } else {
                            try {
                                log.info("Signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                processDisputePayoutTx(trade);
                            } catch (Exception e) {

                                // check if payout published again
                                trade.syncAndPollWallet();
                                if (trade.isPayoutPublished()) {
                                    log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                } else {
                                    if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) throw e;
                                    else throw new RuntimeException("Failed to sign and publish dispute payout tx from arbitrator for " + trade.getClass().getSimpleName() + " " + tradeId + ": " + e.getMessage(), e);
                                }
                            }
                        }
                    } else {
                        if (trade.isPayoutPublished()) log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                        else if (disputeClosedMessage.getUnsignedPayoutTxHex() == null) log.info("{} did not receive unsigned dispute payout tx for trade {} because the arbitrator did not have their updated multisig info (can happen if trader went offline after trade started)", trade.getClass().getSimpleName(), trade.getId());
                    }

                    // complete disputed trade
                    if (trade.isPayoutPublished()) {
                        tradeManager.closeDisputedTrade(trade.getId(), Trade.DisputeState.DISPUTE_CLOSED);
                    }

                    // We use the chatMessage as we only persist those not the DisputeClosedMessage.
                    // If we would use the DisputeClosedMessage we could not lookup for the msg when we receive the AckMessage.
                    sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null);
                    requestPersistence(trade);
                } catch (Exception e) {
                    log.warn("Error processing dispute closed message: {}", e.getMessage());
                    log.warn(ExceptionUtils.getStackTrace(e));
                    requestPersistence(trade);

                    // nack bad message and do not reprocess
                    if (HavenoUtils.isIllegal(e)) {
                        trade.getArbitrator().setDisputeClosedMessage(null); // message is processed
                        trade.setDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                        String warningMsg = "Error processing dispute closed message: " +  e.getMessage() + "\n\nOpen another dispute to try again (ctrl+o).";
                        trade.prependErrorMessage(warningMsg);
                        sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), false, e.getMessage());
                        HavenoUtils.havenoSetup.getTopErrorMsg().set(warningMsg);
                        requestPersistence(trade);
                        throw e;
                    }

                    // schedule to reprocess message unless deleted
                    if (trade.getArbitrator().getDisputeClosedMessage() != null && reprocessOnError) {
                        if (!reprocessDisputeClosedMessageCounts.containsKey(trade.getId())) reprocessDisputeClosedMessageCounts.put(trade.getId(), 0);
                        UserThread.runAfter(() -> {
                            reprocessDisputeClosedMessageCounts.put(trade.getId(), reprocessDisputeClosedMessageCounts.get(trade.getId()) + 1); // increment reprocess count
                            maybeReprocessDisputeClosedMessage(trade, reprocessOnError);
                        }, trade.getReprocessDelayInSeconds(reprocessDisputeClosedMessageCounts.get(trade.getId())));
                    }
                }
            }
        }, trade.getId());
    }

    public void maybeReprocessDisputeClosedMessage(Trade trade, boolean reprocessOnError) {
        if (trade.isShutDownStarted()) return;
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {

                // skip if no need to reprocess
                if (trade.isArbitrator() || trade.getArbitrator().getDisputeClosedMessage() == null || trade.getArbitrator().getDisputeClosedMessage().getUnsignedPayoutTxHex() == null || trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_CLOSED.ordinal()) {
                    return;
                }

                log.warn("Reprocessing dispute closed message for {} {}", trade.getClass().getSimpleName(), trade.getId());
                handleDisputeClosedMessage(trade.getArbitrator().getDisputeClosedMessage(), reprocessOnError);
            }
        }, trade.getId());
    }

    private MoneroTxSet processDisputePayoutTx(Trade trade) {

        // recover if missing wallet data
        trade.recoverIfMissingWalletData();

        // gather trade info
        MoneroWallet multisigWallet = trade.getWallet();
        Optional<Dispute> disputeOptional = findDispute(trade.getId());
        if (!disputeOptional.isPresent()) throw new IllegalArgumentException("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + trade.getId());
        Dispute dispute = disputeOptional.get();
        Contract contract = dispute.getContract();
        DisputeResult disputeResult = dispute.getDisputeResultProperty().get();
        String unsignedPayoutTxHex = trade.getArbitrator().getDisputeClosedMessage().getUnsignedPayoutTxHex();

//    Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
//    BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): use contract instead of trade to get deposit tx ids when contract has deposit tx ids
//    BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getTaker().getDepositTxHash() : trade.getMaker().getDepositTxHash()).getIncomingAmount();
//    BigInteger tradeAmount = BigInteger.valueOf(contract.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);

        // parse arbitrator-signed payout tx
        MoneroTxSet disputeTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(unsignedPayoutTxHex));
        if (disputeTxSet.getTxs() == null || disputeTxSet.getTxs().size() != 1) throw new IllegalArgumentException("Bad arbitrator-signed payout tx");  // TODO (woodser): nack
        MoneroTxWallet arbitratorSignedPayoutTx = disputeTxSet.getTxs().get(0);

        // verify payout tx has 1 or 2 destinations
        int numDestinations = arbitratorSignedPayoutTx.getOutgoingTransfer() == null || arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations() == null ? 0 : arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations().size();
        if (numDestinations != 1 && numDestinations != 2) throw new IllegalArgumentException("Buyer-signed payout tx does not have 1 or 2 destinations");

        // get buyer and seller destinations (order not preserved)
        List<MoneroDestination> destinations = arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations();
        boolean buyerFirst = destinations.get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
        MoneroDestination buyerPayoutDestination = buyerFirst ? destinations.get(0) : numDestinations == 2 ? destinations.get(1) : null;
        MoneroDestination sellerPayoutDestination = buyerFirst ? (numDestinations == 2 ? destinations.get(1) : null) : destinations.get(0);

        // verify payout addresses
        if (buyerPayoutDestination != null && !buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new IllegalArgumentException("Buyer payout address does not match contract");
        if (sellerPayoutDestination != null && !sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new IllegalArgumentException("Seller payout address does not match contract");

        // verify change address is multisig's primary address
        if (!arbitratorSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO) && !arbitratorSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new IllegalArgumentException("Change address is not multisig wallet's primary address");

        // verify sum of outputs = destination amounts + change amount
        BigInteger destinationSum = (buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount()).add(sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount());
        if (!arbitratorSignedPayoutTx.getOutputSum().equals(destinationSum.add(arbitratorSignedPayoutTx.getChangeAmount()))) throw new IllegalArgumentException("Sum of outputs != destination amounts + change amount");

        // get actual payout amounts
        BigInteger actualBuyerAmount = buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount();
        BigInteger actualSellerAmount = sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount();

        // verify payouts sum to unlocked balance within loss of precision due to conversion to centineros
        BigInteger txCost = arbitratorSignedPayoutTx.getFee().add(arbitratorSignedPayoutTx.getChangeAmount()); // cost = fee + lost dust change
        if (!arbitratorSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO)) log.warn("Dust left in multisig wallet for {} {}: {}", getClass().getSimpleName(), trade.getId(), arbitratorSignedPayoutTx.getChangeAmount());
        if (trade.getWallet().getUnlockedBalance().subtract(actualBuyerAmount.add(actualSellerAmount).add(txCost)).compareTo(BigInteger.ZERO) > 0) {
            throw new IllegalArgumentException("The dispute payout amounts do not sum to the wallet's unlocked balance while verifying the dispute payout tx, unlocked balance=" + trade.getWallet().getUnlockedBalance() + " vs sum payout amount=" + actualBuyerAmount.add(actualSellerAmount) + ", buyer payout=" + actualBuyerAmount + ", seller payout=" + actualSellerAmount);
        }

        // verify payout amounts
        BigInteger[] buyerSellerPayoutTxCost = getBuyerSellerPayoutTxCost(disputeResult, txCost);
        BigInteger expectedBuyerAmount = disputeResult.getBuyerPayoutAmountBeforeCost().subtract(buyerSellerPayoutTxCost[0]);
        BigInteger expectedSellerAmount = disputeResult.getSellerPayoutAmountBeforeCost().subtract(buyerSellerPayoutTxCost[1]);
        if (!expectedBuyerAmount.equals(actualBuyerAmount)) throw new IllegalArgumentException("Unexpected buyer payout: " + expectedBuyerAmount + " vs " + actualBuyerAmount);
        if (!expectedSellerAmount.equals(actualSellerAmount)) throw new IllegalArgumentException("Unexpected seller payout: " + expectedSellerAmount + " vs " + actualSellerAmount);

        // check daemon connection
        trade.verifyDaemonConnection();

        // sign arbitrator-signed payout tx
        if (trade.getPayoutTxHex() == null) {
            try {
                MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(unsignedPayoutTxHex);
                if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing arbitrator-signed payout tx");
                String signedMultisigTxHex = result.getSignedMultisigTxHex();
                disputeTxSet.setMultisigTxHex(signedMultisigTxHex);
                trade.setPayoutTxHex(signedMultisigTxHex);
                requestPersistence(trade);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage());
            }

            // verify mining fee is within tolerance by recreating payout tx
            // TODO (monero-project): creating tx will require exchanging updated multisig hex if message needs reprocessed. provide weight with describe_transfer so fee can be estimated?
            MoneroTxWallet feeEstimateTx = null;
            try {
                log.info("Creating dispute fee estimate tx for {} {}", getClass().getSimpleName(), trade.getShortId());
                feeEstimateTx = createDisputePayoutTx(trade, dispute.getContract(), disputeResult, false);
            } catch (Exception e) {
                log.warn("Could not recreate dispute payout tx to verify fee: {}\n", e.getMessage(), e);
            }
            if (feeEstimateTx != null) {
                HavenoUtils.verifyMinerFee(feeEstimateTx.getFee(), arbitratorSignedPayoutTx.getFee());
                log.info("Dispute payout tx fee is within tolerance for {} {}", getClass().getSimpleName(), trade.getShortId());
            }
        } else {
            disputeTxSet.setMultisigTxHex(trade.getPayoutTxHex());
        }

        // submit fully signed payout tx to the network
        for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
            MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
            try {
                List<String> txHashes = multisigWallet.submitMultisigTxHex(disputeTxSet.getMultisigTxHex());
                disputeTxSet.getTxs().get(0).setHash(txHashes.get(0)); // manually update hash which is known after signed
                break;
            } catch (Exception e) {
                if (trade.isPayoutPublished()) throw new IllegalStateException("Payout tx already published for " + trade.getClass().getSimpleName() + " " + trade.getShortId());
                if (HavenoUtils.isNotEnoughSigners(e)) throw new IllegalArgumentException(e);
                log.warn("Failed to submit dispute payout tx, tradeId={}, attempt={}/{}, error={}", trade.getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                if (trade.getXmrConnectionService().isConnected()) trade.requestSwitchToNextBestConnection(sourceConnection);
                HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
            }
        }

        // update state
        trade.updatePayout(disputeTxSet.getTxs().get(0));
        trade.setPayoutState(Trade.PayoutState.PAYOUT_PUBLISHED);
        dispute.setDisputePayoutTxId(disputeTxSet.getTxs().get(0).getHash());
        requestPersistence(trade);
        return disputeTxSet;
    }

    public static BigInteger[] getBuyerSellerPayoutTxCost(DisputeResult disputeResult, BigInteger payoutTxCost) {
        boolean isBuyerWinner = disputeResult.getWinner() == Winner.BUYER;
        BigInteger loserAmount = isBuyerWinner ? disputeResult.getSellerPayoutAmountBeforeCost() : disputeResult.getBuyerPayoutAmountBeforeCost();
        if (loserAmount.equals(BigInteger.ZERO)) {
            BigInteger buyerPayoutTxFee = isBuyerWinner ? payoutTxCost : BigInteger.ZERO;
            BigInteger sellerPayoutTxFee = isBuyerWinner ? BigInteger.ZERO : payoutTxCost;
            return new BigInteger[] { buyerPayoutTxFee, sellerPayoutTxFee };
        } else {
            switch (disputeResult.getSubtractFeeFrom()) {
                case BUYER_AND_SELLER:
                    BigInteger payoutTxFeeSplit = payoutTxCost.divide(BigInteger.valueOf(2));
                    return new BigInteger[] { payoutTxFeeSplit, payoutTxFeeSplit };
                case BUYER_ONLY:
                    return new BigInteger[] { payoutTxCost, BigInteger.ZERO };
                case SELLER_ONLY:
                    return new BigInteger[] { BigInteger.ZERO, payoutTxCost };
                default:
                    throw new RuntimeException("Unsupported subtract fee from: " + disputeResult.getSubtractFeeFrom());
            }
        }
    }

    public FileTransferSender initLogUpload(FileTransferSession.FtpCallback callback,
                                            String tradeId,
                                            int traderId) throws IOException {
        Dispute dispute = findDispute(tradeId, traderId)
                .orElseThrow(() -> new IOException("could not locate Dispute for tradeId/traderId"));
        return dispute.createFileTransferSender(p2PService.getNetworkNode(),
                dispute.getContract().getArbitratorNodeAddress(), callback);
    }

    private void processFilePartReceived(FileTransferPart ftp) {
        if (!ftp.isInitialRequest()) {
            return; // existing sessions are processed by FileTransferSession object directly
        }
        // we create a new session which is related to an open dispute from our list
        Optional<Dispute> dispute = findDispute(ftp.getTradeId(), ftp.getTraderId());
        if (dispute.isEmpty()) {
            log.error("Received log upload request for unknown TradeId/TraderId {}/{}", ftp.getTradeId(), ftp.getTraderId());
            return;
        }
        if (dispute.get().isClosed()) {
            log.error("Received a file transfer request for closed dispute {}", ftp.getTradeId());
            return;
        }
        try {
            FileTransferReceiver session = dispute.get().createOrGetFileTransferReceiver(
                    p2PService.getNetworkNode(), ftp.getSenderNodeAddress(), this);
            session.processFilePartReceived(ftp);
        } catch (IOException e) {
            log.error("Unable to process a received file message" + e);
        }
    }

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof FileTransferPart) {              // mediator receiving log file data
            FileTransferPart ftp = (FileTransferPart) networkEnvelope;
            processFilePartReceived(ftp);
        }
    }

    @Override
    public void onFtpProgress(double progressPct) {
        log.trace("ftp progress: {}", progressPct);
    }

    @Override
    public void onFtpComplete(FileTransferSession session) {
        Optional<Dispute> dispute = findDispute(session.getFullTradeId(), session.getTraderId());
        dispute.ifPresent(d -> addMediationLogsReceivedMessage(d, session.getZipId()));
    }

    @Override
    public void onFtpTimeout(String statusMsg, FileTransferSession session) {
        session.resetSession();
    }
}
