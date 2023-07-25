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
import common.utils.GenUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.core.api.CoreMoneroConnectionsService;
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
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.messages.SupportMessage;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Singleton
public final class ArbitrationManager extends DisputeManager<ArbitrationDisputeList> {

    private final ArbitratorManager arbitratorManager;

    private Map<String, Integer> reprocessDisputeClosedMessageCounts = new HashMap<>();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ArbitrationManager(P2PService p2PService,
                              TradeWalletService tradeWalletService,
                              XmrWalletService walletService,
                              CoreMoneroConnectionsService connectionService,
                              CoreNotificationService notificationService,
                              ArbitratorManager arbitratorManager,
                              TradeManager tradeManager,
                              ClosedTradableManager closedTradableManager,
                              OpenOfferManager openOfferManager,
                              KeyRing keyRing,
                              ArbitrationDisputeListService arbitrationDisputeListService,
                              Config config,
                              PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, connectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, arbitrationDisputeListService, config, priceFeedService);
        this.arbitratorManager = arbitratorManager;
        HavenoUtils.arbitrationManager = this; // TODO: storing static reference, better way?
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

            new Thread(() -> {
                if (message instanceof DisputeOpenedMessage) {
                    handleDisputeOpenedMessage((DisputeOpenedMessage) message);
                } else if (message instanceof ChatMessage) {
                    handleChatMessage((ChatMessage) message);
                } else if (message instanceof DisputeClosedMessage) {
                    handleDisputeClosedMessage((DisputeClosedMessage) message);
                } else {
                    log.warn("Unsupported message at dispatchMessage. message={}", message);
                }
            }).start();
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
        // no action
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.arbitrator").toLowerCase();
        String link = "https://docs.bisq.network/trading-rules.html#legacy-arbitration";
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
        ChatMessage chatMessage = null;
        Dispute dispute = null;
        synchronized (trade) {
            try {
                DisputeResult disputeResult = disputeClosedMessage.getDisputeResult();
                chatMessage = disputeResult.getChatMessage();
                checkNotNull(chatMessage, "chatMessage must not be null");
                String tradeId = disputeResult.getTradeId();

                log.info("Processing {} for {} {}", disputeClosedMessage.getClass().getSimpleName(), trade.getClass().getSimpleName(), disputeResult.getTradeId());

                // verify arbitrator signature
                String summaryText = chatMessage.getMessage();
                DisputeSummaryVerification.verifySignature(summaryText, arbitratorManager);

                // save dispute closed message for reprocessing
                trade.getProcessModel().setDisputeClosedMessage(disputeClosedMessage);
                requestPersistence();

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

                // verify that arbitrator does not get DisputeClosedMessage
                if (keyRing.getPubKeyRing().equals(dispute.getAgentPubKeyRing())) {
                    log.error("Arbitrator received disputeResultMessage. That should never happen.");
                    trade.getProcessModel().setDisputeClosedMessage(null); // don't reprocess
                    return;
                }

                // set dispute state
                cleanupRetryMap(uid);
                if (!dispute.getChatMessages().contains(chatMessage)) {
                    dispute.addAndPersistChatMessage(chatMessage);
                } else {
                    log.warn("We got a dispute mail msg what we have already stored. TradeId = " + chatMessage.getTradeId());
                }
                dispute.setIsClosed();
                if (dispute.disputeResultProperty().get() != null) {
                    log.info("We already got a dispute result, indicating the message was resent after updating multisig info. TradeId = " + tradeId);
                }
                dispute.setDisputeResult(disputeResult);

                // sync and save wallet
                if (!trade.isPayoutPublished()) {
                    trade.syncWallet();
                    trade.saveWallet();
                }

                // import multisig hex
                if (trade.walletExists()) {
                    if (disputeClosedMessage.getUpdatedMultisigHex() != null) trade.getArbitrator().setUpdatedMultisigHex(disputeClosedMessage.getUpdatedMultisigHex());
                    trade.importMultisigHex();
                }

                // attempt to sign and publish dispute payout tx if given and not already published
                if (disputeClosedMessage.getUnsignedPayoutTxHex() != null && !trade.isPayoutPublished()) {

                    // wait to sign and publish payout tx if defer flag set
                    if (disputeClosedMessage.isDeferPublishPayout()) {
                        log.info("Deferring signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                        GenUtils.waitFor(Trade.DEFER_PUBLISH_MS);
                        if (!trade.isPayoutUnlocked()) trade.syncWallet();
                    }

                    // sign and publish dispute payout tx if peer still has not published
                    if (!trade.isPayoutPublished()) {
                        try {
                            log.info("Signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                            signAndPublishDisputePayoutTx(trade);
                        } catch (Exception e) {

                            // check if payout published again
                            trade.syncWallet();
                            if (trade.isPayoutPublished()) {
                                log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                            } else {
                                throw new RuntimeException("Failed to sign and publish dispute payout tx from arbitrator: " + e.getMessage() + ". TradeId = " + tradeId);
                            }
                        }
                    } else {
                        log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                    }
                } else {
                    if (trade.isPayoutPublished()) log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                    else if (disputeClosedMessage.getUnsignedPayoutTxHex() == null) log.info("{} did not receive unsigned dispute payout tx for trade {} because the arbitrator did not have their updated multisig info (can happen if trader went offline after trade started)", trade.getClass().getSimpleName(), trade.getId());
                }

                // We use the chatMessage as we only persist those not the DisputeClosedMessage.
                // If we would use the DisputeClosedMessage we could not lookup for the msg when we receive the AckMessage.
                sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null);
                requestPersistence();
            } catch (Exception e) {
                log.warn("Error processing dispute closed message: " + e.getMessage());
                e.printStackTrace();
                requestPersistence();

                // nack bad message and do not reprocess
                if (e instanceof IllegalArgumentException) {
                    trade.getProcessModel().setPaymentReceivedMessage(null); // message is processed
                    sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), false, e.getMessage());
                    requestPersistence();
                    throw e;
                }

                // schedule to reprocess message unless deleted
                if (trade.getProcessModel().getDisputeClosedMessage() != null) {
                    if (!reprocessDisputeClosedMessageCounts.containsKey(trade.getId())) reprocessDisputeClosedMessageCounts.put(trade.getId(), 0);
                    UserThread.runAfter(() -> {
                        reprocessDisputeClosedMessageCounts.put(trade.getId(), reprocessDisputeClosedMessageCounts.get(trade.getId()) + 1); // increment reprocess count
                        maybeReprocessDisputeClosedMessage(trade, reprocessOnError);
                    }, trade.getReprocessDelayInSeconds(reprocessDisputeClosedMessageCounts.get(trade.getId())));
                }
            }
        }
    }

    public void maybeReprocessDisputeClosedMessage(Trade trade, boolean reprocessOnError) {
        synchronized (trade) {

            // skip if no need to reprocess
            if (trade.isArbitrator() || trade.getProcessModel().getDisputeClosedMessage() == null || trade.getProcessModel().getDisputeClosedMessage().getUnsignedPayoutTxHex() == null || trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_CLOSED.ordinal()) {
                return;
            }

            log.warn("Reprocessing dispute closed message for {} {}", trade.getClass().getSimpleName(), trade.getId());
            new Thread(() -> handleDisputeClosedMessage(trade.getProcessModel().getDisputeClosedMessage(), reprocessOnError)).start();
        }
    }

    private MoneroTxSet signAndPublishDisputePayoutTx(Trade trade) {

        // gather trade info
        MoneroWallet multisigWallet = trade.getWallet();
        Optional<Dispute> disputeOptional = findDispute(trade.getId());
        if (!disputeOptional.isPresent()) throw new RuntimeException("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + trade.getId());
        Dispute dispute = disputeOptional.get();
        Contract contract = dispute.getContract();
        DisputeResult disputeResult = dispute.getDisputeResultProperty().get();
        String unsignedPayoutTxHex = trade.getProcessModel().getDisputeClosedMessage().getUnsignedPayoutTxHex();

//    Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
//    BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): use contract instead of trade to get deposit tx ids when contract has deposit tx ids
//    BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getTaker().getDepositTxHash() : trade.getMaker().getDepositTxHash()).getIncomingAmount();
//    BigInteger tradeAmount = BigInteger.valueOf(contract.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);

        // parse arbitrator-signed payout tx
        MoneroTxSet disputeTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(unsignedPayoutTxHex));
        if (disputeTxSet.getTxs() == null || disputeTxSet.getTxs().size() != 1) throw new RuntimeException("Bad arbitrator-signed payout tx");  // TODO (woodser): nack
        MoneroTxWallet arbitratorSignedPayoutTx = disputeTxSet.getTxs().get(0);

        // verify payout tx has 1 or 2 destinations
        int numDestinations = arbitratorSignedPayoutTx.getOutgoingTransfer() == null || arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations() == null ? 0 : arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations().size();
        if (numDestinations != 1 && numDestinations != 2) throw new RuntimeException("Buyer-signed payout tx does not have 1 or 2 destinations");

        // get buyer and seller destinations (order not preserved)
        List<MoneroDestination> destinations = arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations();
        boolean buyerFirst = destinations.get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
        MoneroDestination buyerPayoutDestination = buyerFirst ? destinations.get(0) : numDestinations == 2 ? destinations.get(1) : null;
        MoneroDestination sellerPayoutDestination = buyerFirst ? (numDestinations == 2 ? destinations.get(1) : null) : destinations.get(0);

        // verify payout addresses
        if (buyerPayoutDestination != null && !buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new RuntimeException("Buyer payout address does not match contract");
        if (sellerPayoutDestination != null && !sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new RuntimeException("Seller payout address does not match contract");

        // verify change address is multisig's primary address
        if (!arbitratorSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO) && !arbitratorSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new RuntimeException("Change address is not multisig wallet's primary address");

        // verify sum of outputs = destination amounts + change amount
        BigInteger destinationSum = (buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount()).add(sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount());
        if (!arbitratorSignedPayoutTx.getOutputSum().equals(destinationSum.add(arbitratorSignedPayoutTx.getChangeAmount()))) throw new RuntimeException("Sum of outputs != destination amounts + change amount");

        // get actual payout amounts
        BigInteger actualWinnerAmount = disputeResult.getWinner() == Winner.BUYER ? buyerPayoutDestination.getAmount() : sellerPayoutDestination.getAmount();
        BigInteger actualLoserAmount = numDestinations == 1 ? BigInteger.ZERO : disputeResult.getWinner() == Winner.BUYER ? sellerPayoutDestination.getAmount() : buyerPayoutDestination.getAmount();

        // verify payouts sum to unlocked balance within loss of precision due to conversion to centineros
        BigInteger txCost = arbitratorSignedPayoutTx.getFee().add(arbitratorSignedPayoutTx.getChangeAmount()); // fee + lost dust change
        if (trade.getWallet().getUnlockedBalance().subtract(actualWinnerAmount.add(actualLoserAmount).add(txCost)).compareTo(BigInteger.valueOf(0)) > 0) {
            throw new RuntimeException("The dispute payout amounts do not sum to the wallet's unlocked balance while verifying the dispute payout tx, unlocked balance=" + trade.getWallet().getUnlockedBalance() + " vs sum payout amount=" + actualWinnerAmount.add(actualLoserAmount) + ", winner payout=" + actualWinnerAmount + ", loser payout=" + actualLoserAmount);
        }

        // get expected payout amounts
        BigInteger expectedWinnerAmount = disputeResult.getWinner() == Winner.BUYER ? disputeResult.getBuyerPayoutAmount() : disputeResult.getSellerPayoutAmount();
        BigInteger expectedLoserAmount = disputeResult.getWinner() == Winner.BUYER ? disputeResult.getSellerPayoutAmount() : disputeResult.getBuyerPayoutAmount();

        // winner pays cost if loser gets nothing, otherwise loser pays cost
        if (expectedLoserAmount.equals(BigInteger.ZERO)) expectedWinnerAmount = expectedWinnerAmount.subtract(txCost);
        else expectedLoserAmount = expectedLoserAmount.subtract(txCost);

        // verify winner and loser payout amounts
        if (!expectedWinnerAmount.equals(actualWinnerAmount)) throw new RuntimeException("Unexpected winner payout: " + expectedWinnerAmount + " vs " + actualWinnerAmount);
        if (!expectedLoserAmount.equals(actualLoserAmount)) throw new RuntimeException("Unexpected loser payout: " + expectedLoserAmount + " vs " + actualLoserAmount);

        // check wallet's daemon connection
        trade.checkDaemonConnection();

        // determine if we already signed dispute payout tx
        // TODO: better way, such as by saving signed dispute payout tx hex in designated field instead of shared payoutTxHex field?
        Set<String> nonSignedDisputePayoutTxHexes = new HashSet<String>();
        if (trade.getProcessModel().getPaymentSentMessage() != null) nonSignedDisputePayoutTxHexes.add(trade.getProcessModel().getPaymentSentMessage().getPayoutTxHex());
        if (trade.getProcessModel().getPaymentReceivedMessage() != null) {
            nonSignedDisputePayoutTxHexes.add(trade.getProcessModel().getPaymentReceivedMessage().getUnsignedPayoutTxHex());
            nonSignedDisputePayoutTxHexes.add(trade.getProcessModel().getPaymentReceivedMessage().getSignedPayoutTxHex());
        }
        boolean signed = trade.getPayoutTxHex() != null && !nonSignedDisputePayoutTxHexes.contains(trade.getPayoutTxHex());

        // sign arbitrator-signed payout tx
        if (!signed) {
            MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(unsignedPayoutTxHex);
            if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing arbitrator-signed payout tx");
            String signedMultisigTxHex = result.getSignedMultisigTxHex();
            disputeTxSet.setMultisigTxHex(signedMultisigTxHex);
            trade.setPayoutTxHex(signedMultisigTxHex);
            requestPersistence();

            // verify mining fee is within tolerance by recreating payout tx
            // TODO (monero-project): creating tx will require exchanging updated multisig hex if message needs reprocessed. provide weight with describe_transfer so fee can be estimated?
            MoneroTxWallet feeEstimateTx = null;
            try {
                feeEstimateTx = createDisputePayoutTx(trade, dispute.getContract(), disputeResult, true);
            } catch (Exception e) {
                log.warn("Could not recreate dispute payout tx to verify fee: " + e.getMessage());
            }
            if (feeEstimateTx != null) {
                BigInteger feeEstimate = feeEstimateTx.getFee();
                double feeDiff = arbitratorSignedPayoutTx.getFee().subtract(feeEstimate).abs().doubleValue() / feeEstimate.doubleValue();
                if (feeDiff > XmrWalletService.MINER_FEE_TOLERANCE) throw new IllegalArgumentException("Miner fee is not within " + (XmrWalletService.MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + feeEstimate + " but was " + arbitratorSignedPayoutTx.getFee());
                log.info("Payout tx fee {} is within tolerance, diff %={}", arbitratorSignedPayoutTx.getFee(), feeDiff);
            }
        } else {
            disputeTxSet.setMultisigTxHex(trade.getPayoutTxHex());
        }

        // submit fully signed payout tx to the network
        List<String> txHashes = multisigWallet.submitMultisigTxHex(disputeTxSet.getMultisigTxHex());
        disputeTxSet.getTxs().get(0).setHash(txHashes.get(0)); // manually update hash which is known after signed

        // update state
        trade.setPayoutTx(disputeTxSet.getTxs().get(0)); // TODO (woodser): is trade.payoutTx() mutually exclusive from dispute payout tx?
        trade.setPayoutTxId(disputeTxSet.getTxs().get(0).getHash());
        trade.setPayoutState(Trade.PayoutState.PAYOUT_PUBLISHED);
        dispute.setDisputePayoutTxId(disputeTxSet.getTxs().get(0).getHash());
        return disputeTxSet;
    }
}
