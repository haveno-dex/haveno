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

package haveno.core.api;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import haveno.common.ThreadUtils;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.FaultHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Attachment;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeSummaryVerification;
import haveno.core.support.dispute.DisputeResult.SubtractFeeFrom;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import static java.lang.String.format;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;


@Singleton
@Slf4j
public class CoreDisputesService {

    public enum DisputePayout {
        BUYER_GETS_TRADE_AMOUNT,
        BUYER_GETS_ALL, // used in desktop
        SELLER_GETS_TRADE_AMOUNT,
        SELLER_GETS_ALL, // used in desktop
        CUSTOM
    }

    private final ArbitrationManager arbitrationManager;
    private final CoinFormatter formatter;
    private final KeyRing keyRing;
    private final TradeManager tradeManager;

    @Inject
    public CoreDisputesService(ArbitrationManager arbitrationManager,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter, // TODO: XMR?
                               KeyRing keyRing,
                               TradeManager tradeManager,
                               XmrWalletService xmrWalletService) {
        this.arbitrationManager = arbitrationManager;
        this.formatter = formatter;
        this.keyRing = keyRing;
        this.tradeManager = tradeManager;
    }

    public List<Dispute> getDisputes() {
        return new ArrayList<>(arbitrationManager.getDisputesAsObservableList());
    }

    public Dispute getDispute(String tradeId) {
        Optional<Dispute> dispute = arbitrationManager.findDispute(tradeId);
        if (dispute.isPresent()) return dispute.get();
        else throw new IllegalStateException(format("dispute for trade id '%s' not found", tradeId));
    }

    public void openDispute(String tradeId, ResultHandler resultHandler, FaultHandler faultHandler) {
        Trade trade = tradeManager.getOpenTrade(tradeId).orElseThrow(() ->
                new IllegalArgumentException(format("trade with id '%s' not found", tradeId)));

        // open dispute on trade thread
        ThreadUtils.execute(() -> {
            Offer offer = trade.getOffer();
            if (offer == null) throw new IllegalStateException(format("offer with tradeId '%s' is null", tradeId));

            // Dispute agents are registered as mediators and refund agents, but current UI appears to be hardcoded
            // to reference the arbitrator. Reference code is in desktop PendingTradesDataModel.java and could be refactored.
            var disputeManager = arbitrationManager;
            var isSupportTicket = false;
            var isMaker = tradeManager.isMyOffer(offer);
            var dispute = createDisputeForTrade(trade, offer, keyRing.getPubKeyRing(), isMaker, isSupportTicket);

            // Sends the openNewDisputeMessage to arbitrator, who will then create 2 disputes
            // one for the opener, the other for the peer, see sendPeerOpenedDisputeMessage.
            disputeManager.sendDisputeOpenedMessage(dispute, false, trade.getSelf().getUpdatedMultisigHex(), resultHandler, faultHandler);
            tradeManager.requestPersistence();
        }, trade.getId());
    }

    public Dispute createDisputeForTrade(Trade trade, Offer offer, PubKeyRing pubKey, boolean isMaker, boolean isSupportTicket) {
        synchronized (trade) {
            byte[] payoutTxSerialized = null;
            String payoutTxHashAsString = null;

            PubKeyRing arbitratorPubKeyRing = trade.getArbitrator().getPubKeyRing();
            checkNotNull(arbitratorPubKeyRing, "arbitratorPubKeyRing must not be null");
            Dispute dispute = new Dispute(new Date().getTime(),
                    trade.getId(),
                    pubKey.hashCode(), // trader id,
                    true,
                    (offer.getDirection() == OfferDirection.BUY) == isMaker,
                    isMaker,
                    pubKey,
                    trade.getDate().getTime(),
                    trade.getMaxTradePeriodDate().getTime(),
                    trade.getContract(),
                    trade.getContractHash(),
                    payoutTxSerialized,
                    payoutTxHashAsString,
                    trade.getContractAsJson(),
                    trade.getMaker().getContractSignature(),
                    trade.getTaker().getContractSignature(),
                    trade.getMaker().getPaymentAccountPayload(),
                    trade.getTaker().getPaymentAccountPayload(),
                    arbitratorPubKeyRing,
                    isSupportTicket,
                    SupportType.ARBITRATION);

            return dispute;
        }
    }

    // TODO: does not wait for success or error response
    public void resolveDispute(String tradeId, DisputeResult.Winner winner, DisputeResult.Reason reason, String summaryNotes, long customWinnerAmount) {

        // get winning dispute
        Dispute winningDispute;
        Trade trade = tradeManager.getTrade(tradeId);
        var winningDisputeOptional = arbitrationManager.getDisputesAsObservableList().stream() // TODO (woodser): use getDispute()
                .filter(d -> tradeId.equals(d.getTradeId()))
                .filter(d -> trade.getTradePeer(d.getTraderPubKeyRing()) == (winner == DisputeResult.Winner.BUYER ? trade.getBuyer() : trade.getSeller()))
                .findFirst();
        if (winningDisputeOptional.isPresent()) winningDispute = winningDisputeOptional.get();
        else throw new IllegalStateException(format("dispute for tradeId '%s' not found", tradeId));

        synchronized (trade) {
            try {

                // create dispute result
                var closeDate = new Date();
                var winnerDisputeResult = createDisputeResult(winningDispute, winner, reason, summaryNotes, closeDate);
                DisputePayout payout;
                if (customWinnerAmount > 0) {
                    payout = DisputePayout.CUSTOM;
                } else if (winner == DisputeResult.Winner.BUYER) {
                    payout = DisputePayout.BUYER_GETS_TRADE_AMOUNT;
                } else if (winner == DisputeResult.Winner.SELLER) {
                    payout = DisputePayout.SELLER_GETS_TRADE_AMOUNT;
                } else {
                    throw new IllegalStateException("Unexpected DisputeResult.Winner: " + winner);
                }
                applyPayoutAmountsToDisputeResult(payout, winningDispute, winnerDisputeResult, customWinnerAmount);

                // close winning dispute ticket
                closeDisputeTicket(arbitrationManager, winningDispute, winnerDisputeResult, () -> {
                    arbitrationManager.requestPersistence();
                }, (errMessage, err) -> {
                    throw new IllegalStateException(errMessage, err);
                });

                // close loser's dispute ticket
                var loserDisputeOptional = arbitrationManager.getDisputesAsObservableList().stream()
                        .filter(d -> tradeId.equals(d.getTradeId()) && winningDispute.getTraderId() != d.getTraderId())
                        .findFirst();
                if (!loserDisputeOptional.isPresent()) throw new IllegalStateException("could not find peer dispute");
                var loserDispute = loserDisputeOptional.get();
                var loserDisputeResult = createDisputeResult(loserDispute, winner, reason, summaryNotes, closeDate);
                loserDisputeResult.setBuyerPayoutAmountBeforeCost(winnerDisputeResult.getBuyerPayoutAmountBeforeCost());
                loserDisputeResult.setSellerPayoutAmountBeforeCost(winnerDisputeResult.getSellerPayoutAmountBeforeCost());
                loserDisputeResult.setSubtractFeeFrom(winnerDisputeResult.getSubtractFeeFrom());
                closeDisputeTicket(arbitrationManager, loserDispute, loserDisputeResult, () -> {
                    arbitrationManager.requestPersistence();
                }, (errMessage, err) -> {
                    throw new IllegalStateException(errMessage, err);
                });
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalStateException(e.getMessage() == null ? ("Error resolving dispute for trade " + trade.getId()) : e.getMessage());
            }
        }
    }

    private DisputeResult createDisputeResult(Dispute dispute, DisputeResult.Winner winner, DisputeResult.Reason reason,
                                              String summaryNotes, Date closeDate) {
        var disputeResult = new DisputeResult(dispute.getTradeId(), dispute.getTraderId());
        disputeResult.setWinner(winner);
        disputeResult.setReason(reason);
        disputeResult.setSummaryNotes(summaryNotes);
        disputeResult.setCloseDate(closeDate);
        return disputeResult;
    }

    /**
     * Sets payout amounts given a payout type. If custom is selected, the winner gets a custom amount, and the peer
     * receives the remaining amount minus the mining fee.
     */
    public void applyPayoutAmountsToDisputeResult(DisputePayout payout, Dispute dispute, DisputeResult disputeResult, long customWinnerAmount) {
        Contract contract = dispute.getContract();
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        BigInteger buyerSecurityDeposit = trade.getBuyer().getSecurityDeposit();
        BigInteger sellerSecurityDeposit = trade.getSeller().getSecurityDeposit();
        BigInteger tradeAmount = contract.getTradeAmount();
        disputeResult.setSubtractFeeFrom(DisputeResult.SubtractFeeFrom.BUYER_AND_SELLER);
        if (payout == DisputePayout.BUYER_GETS_TRADE_AMOUNT) {
            disputeResult.setBuyerPayoutAmountBeforeCost(tradeAmount.add(buyerSecurityDeposit));
            disputeResult.setSellerPayoutAmountBeforeCost(sellerSecurityDeposit);
        } else if (payout == DisputePayout.BUYER_GETS_ALL) {
            disputeResult.setBuyerPayoutAmountBeforeCost(tradeAmount.add(buyerSecurityDeposit).add(sellerSecurityDeposit)); // TODO (woodser): apply min payout to incentivize loser? (see post v1.1.7)
            disputeResult.setSellerPayoutAmountBeforeCost(BigInteger.ZERO);
        } else if (payout == DisputePayout.SELLER_GETS_TRADE_AMOUNT) {
            disputeResult.setBuyerPayoutAmountBeforeCost(buyerSecurityDeposit);
            disputeResult.setSellerPayoutAmountBeforeCost(tradeAmount.add(sellerSecurityDeposit));
        } else if (payout == DisputePayout.SELLER_GETS_ALL) {
            disputeResult.setBuyerPayoutAmountBeforeCost(BigInteger.ZERO);
            disputeResult.setSellerPayoutAmountBeforeCost(tradeAmount.add(sellerSecurityDeposit).add(buyerSecurityDeposit));
        } else if (payout == DisputePayout.CUSTOM) {
            if (customWinnerAmount > trade.getWallet().getBalance().longValueExact()) throw new RuntimeException("Winner payout is more than the trade wallet's balance");
            long loserAmount = tradeAmount.add(buyerSecurityDeposit).add(sellerSecurityDeposit).subtract(BigInteger.valueOf(customWinnerAmount)).longValueExact();
            if (loserAmount < 0) throw new RuntimeException("Loser payout cannot be negative");
            disputeResult.setBuyerPayoutAmountBeforeCost(BigInteger.valueOf(disputeResult.getWinner() == DisputeResult.Winner.BUYER ? customWinnerAmount : loserAmount));
            disputeResult.setSellerPayoutAmountBeforeCost(BigInteger.valueOf(disputeResult.getWinner() == DisputeResult.Winner.BUYER ? loserAmount : customWinnerAmount));
            disputeResult.setSubtractFeeFrom(disputeResult.getWinner() == DisputeResult.Winner.BUYER ? SubtractFeeFrom.SELLER_ONLY : SubtractFeeFrom.BUYER_ONLY); // winner gets exact amount, loser pays mining fee
        }
    }

    public void closeDisputeTicket(DisputeManager disputeManager, Dispute dispute, DisputeResult disputeResult, ResultHandler resultHandler, FaultHandler faultHandler) {
        DisputeResult.Reason reason = disputeResult.getReason();

        String role = Res.get("shared.arbitrator");
        String agentNodeAddress = checkNotNull(disputeManager.getAgentNodeAddress(dispute)).getFullAddress();
        Contract contract = dispute.getContract();
        String currencyCode = contract.getOfferPayload().getCurrencyCode();
        String amount = HavenoUtils.formatXmr(contract.getTradeAmount(), true);

        String textToSign = Res.get("disputeSummaryWindow.close.msg",
                FormattingUtils.formatDateTime(disputeResult.getCloseDate(), true),
                role,
                agentNodeAddress,
                dispute.getShortTradeId(),
                currencyCode,
                Res.get("disputeSummaryWindow.reason." + reason.name()),
                amount,
                HavenoUtils.formatXmr(disputeResult.getBuyerPayoutAmountBeforeCost(), true),
                HavenoUtils.formatXmr(disputeResult.getSellerPayoutAmountBeforeCost(), true),
                disputeResult.summaryNotesProperty().get()
        );

        if (reason == DisputeResult.Reason.OPTION_TRADE &&
                dispute.getChatMessages().size() > 1 &&
                dispute.getChatMessages().get(1).isSystemMessage()) {
            textToSign += "\n" + dispute.getChatMessages().get(1).getMessage() + "\n";
        }

        String summaryText = DisputeSummaryVerification.signAndApply(disputeManager, disputeResult, textToSign);

        disputeManager.closeDisputeTicket(disputeResult, dispute, summaryText, () -> {
            dispute.setDisputeResult(disputeResult);
            dispute.setIsClosed();
            resultHandler.handleResult();
        }, faultHandler);
    }

    public void sendDisputeChatMessage(String disputeId, String message, ArrayList<Attachment> attachments) {
        var disputeOptional = arbitrationManager.findDisputeById(disputeId);
        Dispute dispute;
        if (disputeOptional.isPresent()) dispute = disputeOptional.get();
        else throw new IllegalStateException(format("dispute with id '%s' not found", disputeId));
        ChatMessage chatMessage = new ChatMessage(
                arbitrationManager.getSupportType(),
                dispute.getTradeId(),
                dispute.getTraderId(),
                arbitrationManager.isTrader(dispute),
                message,
                arbitrationManager.getMyAddress(),
                attachments);
        dispute.addAndPersistChatMessage(chatMessage);
        arbitrationManager.sendChatMessage(chatMessage);
    }
}
