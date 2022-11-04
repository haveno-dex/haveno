package bisq.core.api;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Attachment;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.dispute.DisputeSummaryVerification;
import bisq.core.support.dispute.arbitration.ArbitrationManager;
import bisq.core.support.messages.ChatMessage;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.util.FormattingUtils;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.Coin;

import com.google.inject.name.Named;
import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;


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
    private final XmrWalletService xmrWalletService;

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
        this.xmrWalletService = xmrWalletService;
    }

    public List<Dispute> getDisputes() {
        return arbitrationManager.getDisputesAsObservableList();
    }

    public Dispute getDispute(String tradeId) {
        Optional<Dispute> dispute = arbitrationManager.findDispute(tradeId);
        if (dispute.isPresent()) return dispute.get();
        else throw new IllegalStateException(format("dispute for trade id '%s' not found", tradeId));
    }

    public void openDispute(String tradeId, ResultHandler resultHandler, FaultHandler faultHandler) {
        Trade trade = tradeManager.getOpenTrade(tradeId).orElseThrow(() ->
                new IllegalArgumentException(format("trade with id '%s' not found", tradeId)));

        synchronized (trade) {
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
        }
    }

    public Dispute createDisputeForTrade(Trade trade, Offer offer, PubKeyRing pubKey, boolean isMaker, boolean isSupportTicket) {
        synchronized (trade) {
            byte[] payoutTxSerialized = null;
            String payoutTxHashAsString = null;

            PubKeyRing arbitratorPubKeyRing = trade.getArbitrator().getPubKeyRing();
            checkNotNull(arbitratorPubKeyRing, "arbitratorPubKeyRing must not be null");
            byte[] depositTxSerialized = null; // depositTx.bitcoinSerialize(); TODO (woodser)
            String depositTxHashAsString = null; // depositTx.getHashAsString(); TODO (woodser)
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
                    depositTxSerialized,
                    payoutTxSerialized,
                    depositTxHashAsString,
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

    public void resolveDispute(String tradeId, DisputeResult.Winner winner, DisputeResult.Reason reason, String summaryNotes, long customWinnerAmount) {
        try {

            // get winning dispute
            Dispute winningDispute;
            Trade trade = tradeManager.getTrade(tradeId);
            var winningDisputeOptional = arbitrationManager.getDisputesAsObservableList().stream() // TODO (woodser): use getDispute()
                    .filter(d -> tradeId.equals(d.getTradeId()))
                    .filter(d -> trade.getTradingPeer(d.getTraderPubKeyRing()) == (winner == DisputeResult.Winner.BUYER ? trade.getBuyer() : trade.getSeller()))
                    .findFirst();
            if (winningDisputeOptional.isPresent()) winningDispute = winningDisputeOptional.get();
            else throw new IllegalStateException(format("dispute for tradeId '%s' not found", tradeId));

            synchronized (trade) {
                var closeDate = new Date();
                var disputeResult = createDisputeResult(winningDispute, winner, reason, summaryNotes, closeDate);

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
                applyPayoutAmountsToDisputeResult(payout, winningDispute, disputeResult, customWinnerAmount);

                // close dispute ticket
                closeDisputeTicket(arbitrationManager, winningDispute, disputeResult, () -> {
                    arbitrationManager.requestPersistence();

                    // close peer's dispute ticket
                    var peersDisputeOptional = arbitrationManager.getDisputesAsObservableList().stream()
                            .filter(d -> tradeId.equals(d.getTradeId()) && winningDispute.getTraderId() != d.getTraderId())
                            .findFirst();
                    if (peersDisputeOptional.isPresent()) {
                        var peerDispute = peersDisputeOptional.get();
                        var peerDisputeResult = createDisputeResult(peerDispute, winner, reason, summaryNotes, closeDate);
                        peerDisputeResult.setBuyerPayoutAmount(disputeResult.getBuyerPayoutAmount());
                        peerDisputeResult.setSellerPayoutAmount(disputeResult.getSellerPayoutAmount());
                        closeDisputeTicket(arbitrationManager, peerDispute, peerDisputeResult, () -> {
                            arbitrationManager.requestPersistence();
                        });
                    } else {
                        throw new IllegalStateException("could not find peer dispute");
                    }
                });
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
        Offer offer = new Offer(contract.getOfferPayload());
        Coin buyerSecurityDeposit = offer.getBuyerSecurityDeposit();
        Coin sellerSecurityDeposit = offer.getSellerSecurityDeposit();
        Coin tradeAmount = contract.getTradeAmount();
        if (payout == DisputePayout.BUYER_GETS_TRADE_AMOUNT) {
            disputeResult.setBuyerPayoutAmount(tradeAmount.add(buyerSecurityDeposit));
            disputeResult.setSellerPayoutAmount(sellerSecurityDeposit);
        } else if (payout == DisputePayout.BUYER_GETS_ALL) {
            disputeResult.setBuyerPayoutAmount(tradeAmount
                    .add(buyerSecurityDeposit)
                    .add(sellerSecurityDeposit)); // TODO (woodser): apply min payout to incentivize loser (see post v1.1.7)
            disputeResult.setSellerPayoutAmount(Coin.ZERO);
        } else if (payout == DisputePayout.SELLER_GETS_TRADE_AMOUNT) {
            disputeResult.setBuyerPayoutAmount(buyerSecurityDeposit);
            disputeResult.setSellerPayoutAmount(tradeAmount.add(sellerSecurityDeposit));
        } else if (payout == DisputePayout.SELLER_GETS_ALL) {
            disputeResult.setBuyerPayoutAmount(Coin.ZERO);
            disputeResult.setSellerPayoutAmount(tradeAmount
                    .add(sellerSecurityDeposit)
                    .add(buyerSecurityDeposit));
        } else if (payout == DisputePayout.CUSTOM) {
            Coin winnerAmount = Coin.valueOf(customWinnerAmount);
            Coin loserAmount = tradeAmount.add(buyerSecurityDeposit).add(sellerSecurityDeposit).minus(winnerAmount);
            disputeResult.setBuyerPayoutAmount(disputeResult.getWinner() == DisputeResult.Winner.BUYER ? winnerAmount : loserAmount);
            disputeResult.setSellerPayoutAmount(disputeResult.getWinner() == DisputeResult.Winner.BUYER ? loserAmount : winnerAmount);
        }
    }

    // From DisputeSummaryWindow.java
    public void closeDisputeTicket(DisputeManager disputeManager, Dispute dispute, DisputeResult disputeResult, ResultHandler resultHandler) {
        dispute.setDisputeResult(disputeResult);
        dispute.setIsClosed();
        DisputeResult.Reason reason = disputeResult.getReason();

        String role = Res.get("shared.arbitrator");
        String agentNodeAddress = checkNotNull(disputeManager.getAgentNodeAddress(dispute)).getFullAddress();
        Contract contract = dispute.getContract();
        String currencyCode = contract.getOfferPayload().getCurrencyCode();
        String amount = formatter.formatCoinWithCode(contract.getTradeAmount());

        String textToSign = Res.get("disputeSummaryWindow.close.msg",
                FormattingUtils.formatDateTime(disputeResult.getCloseDate(), true),
                role,
                agentNodeAddress,
                dispute.getShortTradeId(),
                currencyCode,
                amount,
                formatter.formatCoinWithCode(disputeResult.getBuyerPayoutAmount()),
                formatter.formatCoinWithCode(disputeResult.getSellerPayoutAmount()),
                Res.get("disputeSummaryWindow.reason." + reason.name()),
                disputeResult.summaryNotesProperty().get()
        );

        if (reason == DisputeResult.Reason.OPTION_TRADE &&
                dispute.getChatMessages().size() > 1 &&
                dispute.getChatMessages().get(1).isSystemMessage()) {
            textToSign += "\n" + dispute.getChatMessages().get(1).getMessage() + "\n";
        }

        String summaryText = DisputeSummaryVerification.signAndApply(disputeManager, disputeResult, textToSign);
        summaryText += Res.get("disputeSummaryWindow.close.nextStepsForRefundAgentArbitration");
        disputeManager.closeDisputeTicket(disputeResult, dispute, summaryText, resultHandler);
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
