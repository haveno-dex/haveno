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

package bisq.core.support.dispute.arbitration;

import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.api.CoreNotificationService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.dispute.DisputeResult.Winner;
import bisq.core.support.dispute.messages.DisputeClosedMessage;
import bisq.core.support.dispute.messages.DisputeOpenedMessage;
import bisq.core.support.messages.ChatMessage;
import bisq.core.support.messages.SupportMessage;
import bisq.core.trade.ClosedTradableManager;
import bisq.core.trade.Contract;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;

import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import common.utils.GenUtils;
import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
@Singleton
public final class ArbitrationManager extends DisputeManager<ArbitrationDisputeList> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ArbitrationManager(P2PService p2PService,
                              TradeWalletService tradeWalletService,
                              XmrWalletService walletService,
                              CoreMoneroConnectionsService connectionService,
                              CoreNotificationService notificationService,
                              TradeManager tradeManager,
                              ClosedTradableManager closedTradableManager,
                              OpenOfferManager openOfferManager,
                              KeyRing keyRing,
                              ArbitrationDisputeListService arbitrationDisputeListService,
                              Config config,
                              PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, connectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, arbitrationDisputeListService, config, priceFeedService);
        HavenoUtils.arbitrationManager = this; // store static reference
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

            if (message instanceof DisputeOpenedMessage) {
                handleDisputeOpenedMessage((DisputeOpenedMessage) message);
            } else if (message instanceof ChatMessage) {
                handleChatMessage((ChatMessage) message);
            } else if (message instanceof DisputeClosedMessage) {
                handleDisputeClosedMessage((DisputeClosedMessage) message);
            } else {
                log.warn("Unsupported message at dispatchMessage. message={}", message);
            }
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
        DisputeResult disputeResult = disputeClosedMessage.getDisputeResult();
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        String tradeId = disputeResult.getTradeId();

        // get trade
        Trade trade = tradeManager.getTrade(tradeId);
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", tradeId);
            return;
        }

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
        Dispute dispute = disputeOptional.get();

        // verify that arbitrator does not get DisputeClosedMessage
        if (pubKeyRing.equals(dispute.getAgentPubKeyRing())) {
            log.error("Arbitrator received disputeResultMessage. That should never happen.");
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
            log.warn("We already got a dispute result. That should only happen if a dispute needs to be closed " +
                    "again because the first close did not succeed. TradeId = " + tradeId);
        }
        dispute.setDisputeResult(disputeResult);

        // import multisig hex
        List<String> updatedMultisigHexes = new ArrayList<String>();
        if (trade.getTradingPeer().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getTradingPeer().getUpdatedMultisigHex());
        if (trade.getArbitrator().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getArbitrator().getUpdatedMultisigHex());
        if (!updatedMultisigHexes.isEmpty()) trade.getWallet().importMultisigHex(updatedMultisigHexes.toArray(new String[0])); // TODO (monero-project): fails if multisig hex imported individually

        // sync and save wallet
        trade.syncWallet();
        trade.saveWallet();

        // run off main thread
        new Thread(() -> {
            String errorMessage = null;
            boolean success = true;

            // attempt to sign and publish dispute payout tx if given and not already published
            if (disputeClosedMessage.getUnsignedPayoutTxHex() != null && !trade.isPayoutPublished()) {

                // wait to sign and publish payout tx if defer flag set
                if (disputeClosedMessage.isDeferPublishPayout()) {
                    log.info("Deferring signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                    GenUtils.waitFor(Trade.DEFER_PUBLISH_MS);
                    trade.syncWallet();
                }

                // sign and publish dispute payout tx if peer still has not published
                if (!trade.isPayoutPublished()) {
                    try {
                        log.info("Signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                        signAndPublishDisputePayoutTx(trade, disputeClosedMessage.getUnsignedPayoutTxHex());
                    } catch (Exception e) {

                        // check if payout published again
                        trade.syncWallet();
                        if (trade.isPayoutPublished()) {
                            log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                        } else {
                            e.printStackTrace();
                            errorMessage = "Failed to sign and publish dispute payout tx from arbitrator: " + e.getMessage() + ". TradeId = " + tradeId;
                            log.warn(errorMessage);
                            success = false;
                        }
                    }
                } else {
                    log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                }
            } else {
                if (trade.isPayoutPublished()) log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                else if (disputeClosedMessage.getUnsignedPayoutTxHex() == null) log.info("{} did not receive unsigned dispute payout tx for trade {} because the arbitrator did not have their updated multisig info (can happen if trader went offline after trade started)", trade.getClass().getName(), trade.getId());
            }

            // We use the chatMessage as we only persist those not the DisputeClosedMessage.
            // If we would use the DisputeClosedMessage we could not lookup for the msg when we receive the AckMessage.
            sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), success, errorMessage);
            requestPersistence();
        }).start();
    }

    private MoneroTxSet signAndPublishDisputePayoutTx(Trade trade, String payoutTxHex) {

        // gather trade info
        MoneroWallet multisigWallet = trade.getWallet();
        Optional<Dispute> disputeOptional = findDispute(trade.getId());
        if (!disputeOptional.isPresent()) throw new RuntimeException("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + trade.getId());
        Dispute dispute = disputeOptional.get();
        Contract contract = dispute.getContract();
        DisputeResult disputeResult = dispute.getDisputeResultProperty().get();

//    Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
//    BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): use contract instead of trade to get deposit tx ids when contract has deposit tx ids
//    BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getTaker().getDepositTxHash() : trade.getMaker().getDepositTxHash()).getIncomingAmount();
//    BigInteger tradeAmount = BigInteger.valueOf(contract.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);

        // parse arbitrator-signed payout tx
        MoneroTxSet signedTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(payoutTxHex));
        if (signedTxSet.getTxs() == null || signedTxSet.getTxs().size() != 1) throw new RuntimeException("Bad arbitrator-signed payout tx");  // TODO (woodser): nack
        MoneroTxWallet arbitratorSignedPayoutTx = signedTxSet.getTxs().get(0);

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

        // TODO: verify miner fee is within expected range

        // verify winner and loser payout amounts
        BigInteger txCost = arbitratorSignedPayoutTx.getFee().add(arbitratorSignedPayoutTx.getChangeAmount()); // fee + lost dust change
        BigInteger expectedWinnerAmount = HavenoUtils.coinToAtomicUnits(disputeResult.getWinner() == Winner.BUYER ? disputeResult.getBuyerPayoutAmount() : disputeResult.getSellerPayoutAmount());
        BigInteger expectedLoserAmount = HavenoUtils.coinToAtomicUnits(disputeResult.getWinner() == Winner.BUYER ? disputeResult.getSellerPayoutAmount() : disputeResult.getBuyerPayoutAmount());
        if (expectedLoserAmount.equals(BigInteger.ZERO)) expectedWinnerAmount = expectedWinnerAmount.subtract(txCost); // winner only pays tx cost if loser gets 0
        else expectedLoserAmount = expectedLoserAmount.subtract(txCost); // loser pays tx cost
        BigInteger actualWinnerAmount = disputeResult.getWinner() == Winner.BUYER ? buyerPayoutDestination.getAmount() : sellerPayoutDestination.getAmount();
        BigInteger actualLoserAmount = numDestinations == 1 ? BigInteger.ZERO : disputeResult.getWinner() == Winner.BUYER ? sellerPayoutDestination.getAmount() : buyerPayoutDestination.getAmount();
        if (!expectedWinnerAmount.equals(actualWinnerAmount)) throw new RuntimeException("Unexpected winner payout: " + expectedWinnerAmount + " vs " + actualWinnerAmount);
        if (!expectedLoserAmount.equals(actualLoserAmount)) throw new RuntimeException("Unexpected loser payout: " + expectedLoserAmount + " vs " + actualLoserAmount);

        // sign arbitrator-signed payout tx
        MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(payoutTxHex);
        if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing arbitrator-signed payout tx");
        String signedMultisigTxHex = result.getSignedMultisigTxHex();
        signedTxSet.setMultisigTxHex(signedMultisigTxHex);

        // submit fully signed payout tx to the network
        List<String> txHashes = multisigWallet.submitMultisigTxHex(signedTxSet.getMultisigTxHex());
        signedTxSet.getTxs().get(0).setHash(txHashes.get(0)); // manually update hash which is known after signed

        // update state
        trade.setPayoutTx(signedTxSet.getTxs().get(0)); // TODO (woodser): is trade.payoutTx() mutually exclusive from dispute payout tx?
        trade.setPayoutTxId(signedTxSet.getTxs().get(0).getHash());
        trade.setPayoutState(Trade.PayoutState.PAYOUT_PUBLISHED);
        dispute.setDisputePayoutTxId(signedTxSet.getTxs().get(0).getHash());
        return signedTxSet;
    }
}
