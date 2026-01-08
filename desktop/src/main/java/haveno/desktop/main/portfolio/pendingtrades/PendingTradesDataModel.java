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

package haveno.desktop.main.portfolio.pendingtrades;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.FaultHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.CoreDisputesService;
import haveno.core.api.XmrConnectionService;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.traderchat.TraderChatManager;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.BuyerProtocol;
import haveno.core.trade.protocol.SellerProtocol;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableDataModel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.main.support.SupportView;
import haveno.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import haveno.desktop.main.support.dispute.client.mediation.MediationClientView;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javax.annotation.Nullable;
import lombok.Getter;
import org.bitcoinj.core.Coin;
import org.bouncycastle.crypto.params.KeyParameter;

public class PendingTradesDataModel extends ActivatableDataModel {
    @Getter
    public final TradeManager tradeManager;
    public final XmrWalletService xmrWalletService;
    public final ArbitrationManager arbitrationManager;
    public final MediationManager mediationManager;
    private final P2PService p2PService;
    private final XmrConnectionService xmrConnectionService;
    @Getter
    private final AccountAgeWitnessService accountAgeWitnessService;
    public final Navigation navigation;
    public final WalletPasswordWindow walletPasswordWindow;
    private final NotificationCenter notificationCenter;
    private final OfferUtil offerUtil;
    private final CoinFormatter btcFormatter;

    final ObservableList<PendingTradesListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<Trade> tradesListChangeListener;
    private boolean isMaker;

    final ObjectProperty<PendingTradesListItem> selectedItemProperty = new SimpleObjectProperty<>();
    public final StringProperty makerTxId = new SimpleStringProperty();
    public final StringProperty takerTxId = new SimpleStringProperty();

    @Getter
    private final TraderChatManager traderChatManager;
    public final Preferences preferences;
    private boolean activated;
    private ChangeListener<Trade.State> tradeStateChangeListener;
    private Trade selectedTrade;
    @Getter
    private final PubKeyRingProvider pubKeyRingProvider;
    private final CoreDisputesService disputesService;

    private final Set<Trade> hiddenTrades = new HashSet<Trade>();
    private final ChangeListener<Trade.State> hiddenStateChangeListener = (observable, oldValue, newValue) -> {
        onListChanged();
    };

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PendingTradesDataModel(TradeManager tradeManager,
                                  XmrWalletService xmrWalletService,
                                  PubKeyRingProvider pubKeyRingProvider,
                                  ArbitrationManager arbitrationManager,
                                  MediationManager mediationManager,
                                  TraderChatManager traderChatManager,
                                  Preferences preferences,
                                  P2PService p2PService,
                                  XmrConnectionService xmrConnectionService,
                                  AccountAgeWitnessService accountAgeWitnessService,
                                  Navigation navigation,
                                  WalletPasswordWindow walletPasswordWindow,
                                  NotificationCenter notificationCenter,
                                  OfferUtil offerUtil,
                                  CoreDisputesService disputesService,
                                  @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        this.tradeManager = tradeManager;
        this.xmrWalletService = xmrWalletService;
        this.pubKeyRingProvider = pubKeyRingProvider;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.traderChatManager = traderChatManager;
        this.preferences = preferences;
        this.p2PService = p2PService;
        this.xmrConnectionService = xmrConnectionService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.walletPasswordWindow = walletPasswordWindow;
        this.notificationCenter = notificationCenter;
        this.offerUtil = offerUtil;
        this.disputesService = disputesService;
        this.btcFormatter = formatter;

        tradesListChangeListener = change -> onListChanged();
        notificationCenter.setSelectItemByTradeIdConsumer(this::selectItemByTradeId);
    }

    @Override
    protected void activate() {
        tradeManager.getObservableList().addListener(tradesListChangeListener);
        onListChanged();
        if (selectedItemProperty.get() != null)
            notificationCenter.setSelectedTradeId(selectedItemProperty.get().getTrade().getId());

        activated = true;
    }

    @Override
    protected void deactivate() {
        for (Trade trade : hiddenTrades) trade.stateProperty().removeListener(hiddenStateChangeListener);
        tradeManager.getObservableList().removeListener(tradesListChangeListener);
        notificationCenter.setSelectedTradeId(null);
        activated = false;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onSelectItem(PendingTradesListItem item) {
        doSelectItem(item);
    }

    public void onPaymentSent(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Trade trade = getTrade();
        checkNotNull(trade, "trade must not be null");
        checkArgument(trade instanceof BuyerTrade, "Check failed: trade instanceof BuyerTrade. Was: " + trade.getClass().getSimpleName());
        ((BuyerProtocol) tradeManager.getTradeProtocol(trade)).onPaymentSent(resultHandler, errorMessageHandler);
    }

    public void onPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Trade trade = getTrade();
        checkNotNull(trade, "trade must not be null");
        checkArgument(trade instanceof SellerTrade, "Trade must be instance of SellerTrade");
        ((SellerProtocol) tradeManager.getTradeProtocol(trade)).onPaymentReceived(resultHandler, errorMessageHandler);
    }

    public void onWithdrawRequest(String toAddress,
                                  Coin amount,
                                  Coin fee,
                                  KeyParameter aesKey,
                                  @Nullable String memo,
                                  ResultHandler resultHandler,
                                  FaultHandler faultHandler) {
        checkNotNull(getTrade(), "trade must not be null");

        if (toAddress != null && toAddress.length() > 0) {
            tradeManager.onWithdrawRequest(
                    toAddress,
                    amount,
                    fee,
                    aesKey,
                    getTrade(),
                    memo,
                    () -> {
                        resultHandler.handleResult();
                        selectBestItem();
                    },
                    (errorMessage, throwable) -> {
                        log.error(errorMessage);
                        faultHandler.handleFault(errorMessage, throwable);
                    });
        } else {
            faultHandler.handleFault(Res.get("portfolio.pending.noReceiverAddressDefined"), null);
        }
    }

    public void onOpenDispute() {
        tryOpenDispute(false);
    }

    public void onOpenSupportTicket() {
        tryOpenDispute(true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public Trade getTrade() {
        return selectedItemProperty.get() != null ? selectedItemProperty.get().getTrade() : null;
    }

    @Nullable
    Offer getOffer() {
        return getTrade() != null ? getTrade().getOffer() : null;
    }

    private boolean isBuyOffer() {
        return getOffer() != null && offerUtil.isBuyOffer(getOffer().getDirection());
    }

    boolean isBuyer() {
        return (isMaker(getOffer()) && isBuyOffer())
                || (!isMaker(getOffer()) && !isBuyOffer());
    }

    boolean isMaker(Offer offer) {
        return tradeManager.isMyOffer(offer);
    }

    public boolean isMaker() {
        return isMaker;
    }

    BigInteger getTradeFee() {
        Trade trade = getTrade();
        if (trade != null) {
            Offer offer = trade.getOffer();
            if (isMaker()) {
                if (offer != null) {
                    return trade.getMakerFee();
                } else {
                    log.error("offer is null");
                    return BigInteger.ZERO;
                }
            } else {
                return trade.getTakerFee();
            }
        } else {
            log.error("Trade is null at getTotalFees");
            return BigInteger.ZERO;
        }
    }

    @Nullable
    public PaymentAccountPayload getSellersPaymentAccountPayload() {
        if (getTrade() == null) return null;
        return getTrade().getSeller().getPaymentAccountPayload();
    }

    @Nullable
    public PaymentAccountPayload getBuyersPaymentAccountPayload() {
        if (getTrade() == null) return null;
        return getTrade().getBuyer().getPaymentAccountPayload();
    }

    public String getReference() {
        return getOffer() != null ? getOffer().getShortId() : "";
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onListChanged() {
        UserThread.execute(() -> {
            synchronized (tradeManager.getObservableList()) {

                // add or remove listener for hidden trades
                for (Trade trade : tradeManager.getObservableList()) {
                    if (isTradeShown(trade)) {
                        if (hiddenTrades.contains(trade)) {
                            UserThread.execute(() -> trade.stateProperty().removeListener(hiddenStateChangeListener));
                            hiddenTrades.remove(trade);
                        }
                    } else {
                        if (!hiddenTrades.contains(trade)) {
                            UserThread.execute(() -> trade.stateProperty().addListener(hiddenStateChangeListener));
                            hiddenTrades.add(trade);
                        }
                    }
                }
        
                // add shown trades to list
                synchronized (list) {
                    list.clear();
                    list.addAll(tradeManager.getObservableList().stream()
                            .filter(trade -> isTradeShown(trade))
                            .map(trade -> new PendingTradesListItem(trade, btcFormatter))
                            .collect(Collectors.toList()));

                    // we sort by date, earliest first
                    list.sort((o1, o2) -> o2.getTrade().getDate().compareTo(o1.getTrade().getDate()));
                }
            }

            selectBestItem();
        });
    }

    private boolean isTradeShown(Trade trade) {
        return trade.isDepositsPublished();
    }

    private void selectBestItem() {
        synchronized (list) {
            if (list.size() == 1)
                doSelectItem(list.get(0));
            else if (list.size() > 1 && (selectedItemProperty.get() == null || !list.contains(selectedItemProperty.get())))
                doSelectItem(list.get(0));
            else if (list.size() == 0)
                doSelectItem(null);
        }
    }

    private void selectItemByTradeId(String tradeId) {
        if (activated) {
            synchronized (list) {
                list.stream().filter(e -> e.getTrade().getId().equals(tradeId)).findAny().ifPresent(this::doSelectItem);
            }
        }
    }

    private void doSelectItem(@Nullable PendingTradesListItem item) {
        UserThread.execute(() -> {
            if (selectedTrade != null)
                selectedTrade.stateProperty().removeListener(tradeStateChangeListener);

            if (item != null) {
                selectedTrade = item.getTrade();
                if (selectedTrade == null) {
                    log.error("selectedTrade is null");
                    return;
                }

                String tradeId = selectedTrade.getId();
                tradeStateChangeListener = (observable, oldValue, newValue) -> {
                    String makerDepositTxHash = selectedTrade.getMaker().getDepositTxHash();
                    String takerDepositTxHash = selectedTrade.getTaker().getDepositTxHash();
                    makerTxId.set(nullToEmptyString(makerDepositTxHash));
                    takerTxId.set(nullToEmptyString(takerDepositTxHash));
                    if (makerDepositTxHash != null || takerDepositTxHash != null) {
                        notificationCenter.setSelectedTradeId(tradeId);
                        UserThread.execute(() -> selectedTrade.stateProperty().removeListener(tradeStateChangeListener));
                    }
                };
                selectedTrade.stateProperty().addListener(tradeStateChangeListener);

                Offer offer = selectedTrade.getOffer();
                if (offer == null) {
                    log.error("offer is null");
                    return;
                }

                isMaker = tradeManager.isMyOffer(offer);
                String makerDepositTxHash = selectedTrade.getMaker().getDepositTxHash();
                String takerDepositTxHash = selectedTrade.getTaker().getDepositTxHash();
                makerTxId.set(nullToEmptyString(makerDepositTxHash));
                takerTxId.set(nullToEmptyString(takerDepositTxHash));
                notificationCenter.setSelectedTradeId(tradeId);
            } else {
                selectedTrade = null;
                makerTxId.set("");
                takerTxId.set("");
                notificationCenter.setSelectedTradeId(null);
            }
            selectedItemProperty.set(item);
        });
    }

    private String nullToEmptyString(String str) {
        return str == null ? "" : str;
    }

    private void tryOpenDispute(boolean isSupportTicket) {
        Trade trade = getTrade();
        if (trade == null) {
            log.error("Trade is null");
            return;
        }

        doOpenDispute(isSupportTicket, trade);
    }

    private void doOpenDispute(boolean isSupportTicket, Trade trade) {
      if (trade == null) {
          log.warn("trade is null at doOpenDispute");
          return;
      }

      Offer offer = trade.getOffer();
      if (offer == null) {
          log.warn("offer is null at doOpenDispute");
          return;
      }

      if (!GUIUtil.isBootstrappedOrShowPopup(p2PService)) {
          return;
      }

      byte[] payoutTxSerialized = null;
      String payoutTxHashAsString = null;
      if (trade.getPayoutTxId() != null) {
//          payoutTxSerialized = payoutTx.bitcoinSerialize(); // TODO (woodser): no need to pass serialized txs for xmr
//          payoutTxHashAsString = payoutTx.getHashAsString();
      }
      Trade.DisputeState disputeState = trade.getDisputeState();
      DisputeManager<? extends DisputeList<Dispute>> disputeManager;
      boolean useMediation;
      boolean useArbitration;
      // If mediation is not activated we use arbitration
      if (false) {  // TODO (woodser): use mediation for xmr? if (MediationManager.isMediationActivated()) {
          // In case we re-open a dispute we allow Trade.DisputeState.MEDIATION_REQUESTED or
          useMediation = disputeState == Trade.DisputeState.NO_DISPUTE || disputeState == Trade.DisputeState.MEDIATION_REQUESTED || disputeState == Trade.DisputeState.DISPUTE_OPENED;
          // in case of arbitration disputeState == Trade.DisputeState.ARBITRATION_REQUESTED
          useArbitration = disputeState == Trade.DisputeState.MEDIATION_CLOSED || disputeState == Trade.DisputeState.DISPUTE_REQUESTED || disputeState == Trade.DisputeState.DISPUTE_OPENED;
      } else {
          useMediation = false;
          useArbitration = true;
      }

//      if (useMediation) {
//          // If no dispute state set we start with mediation
//          disputeManager = mediationManager;
//          PubKeyRing mediatorPubKeyRing = trade.getMediatorPubKeyRing();
//          checkNotNull(mediatorPubKeyRing, "mediatorPubKeyRing must not be null");
//          byte[] depositTxSerialized = null;  // depositTx.bitcoinSerialize();  // TODO (woodser): no serialized txs in xmr
//          String depositTxHashAsString = null;  // depositTx.getHashAsString(); // TODO (woodser): two deposit txs for dispute
//          Dispute dispute = new Dispute(new Date().getTime(),
//                  trade.getId(),
//                  pubKeyRing.hashCode(), // traderId
//                  true,
//                  (offer.getDirection() == OfferDirection.BUY) == isMaker,
//                  isMaker,
//                  pubKeyRing,
//                  trade.getDate().getTime(),
//                  trade.getMaxTradePeriodDate().getTime(),
//                  trade.getContract(),
//                  trade.getContractHash(),
//                  payoutTxSerialized,
//                  payoutTxHashAsString,
//                  trade.getContractAsJson(),
//                  trade.getMakerContractSignature(),
//                  trade.getTakerContractSignature(),
//                  mediatorPubKeyRing,
//                  isSupportTicket,
//                  SupportType.MEDIATION);

        ResultHandler resultHandler;
        if (useMediation) {
            // If no dispute state set we start with mediation
            resultHandler = () -> navigation.navigateTo(MainView.class, SupportView.class, MediationClientView.class);
            disputeManager = mediationManager;
            PubKeyRing arbitratorPubKeyRing = trade.getArbitrator().getPubKeyRing();
            checkNotNull(arbitratorPubKeyRing, "arbitratorPubKeyRing must not be null");
            byte[] depositTxSerialized = null;  // depositTx.bitcoinSerialize();  // TODO (woodser): no serialized txs in xmr
            Dispute dispute = new Dispute(new Date().getTime(),
                    trade.getId(),
                    pubKeyRingProvider.get().hashCode(), // trader id
                    true,
                    (offer.getDirection() == OfferDirection.BUY) == isMaker,
                    isMaker,
                    pubKeyRingProvider.get(),
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
                    SupportType.MEDIATION);
            dispute.setExtraData("counterCurrencyTxId", trade.getCounterCurrencyTxId());
            dispute.setExtraData("counterCurrencyExtraData", trade.getCounterCurrencyExtraData());

            trade.setDisputeState(Trade.DisputeState.MEDIATION_REQUESTED);
            sendDisputeOpenedMessage(dispute, disputeManager);
            tradeManager.requestPersistence();
        } else if (useArbitration) {
          disputeManager = arbitrationManager;
          Dispute dispute = disputesService.createDisputeForTrade(trade, offer, pubKeyRingProvider.get(), isMaker, isSupportTicket);

          // send dispute opened message
          sendDisputeOpenedMessage(dispute, disputeManager);
          tradeManager.requestPersistence();
        } else {
            log.warn("Invalid dispute state {}", disputeState.name());
        }
    }

    private void sendDisputeOpenedMessage(Dispute dispute, DisputeManager<? extends DisputeList<Dispute>> disputeManager) {
        Optional<Dispute> optionalDispute = disputeManager.findDispute(dispute);
        boolean disputeClosed = optionalDispute.isPresent() && optionalDispute.get().isClosed();
        if (disputeClosed) {
            String msg = "We got a dispute already open for that trade and trading peer.\n" + "TradeId = " + dispute.getTradeId();
            new Popup().warning(msg + "\n\n" + Res.get("portfolio.pending.openAgainDispute.msg"))
                    .actionButtonText(Res.get("portfolio.pending.openAgainDispute.button"))
                    .onAction(() -> doSendDisputeOpenedMessage(dispute, disputeManager))
                    .closeButtonText(Res.get("shared.cancel")).show();
        } else {
            doSendDisputeOpenedMessage(dispute, disputeManager);
        }
    }

    private void doSendDisputeOpenedMessage(Dispute dispute, DisputeManager<? extends DisputeList<Dispute>> disputeManager) {
        navigation.navigateTo(MainView.class, SupportView.class, ArbitrationClientView.class);
        disputeManager.sendDisputeOpenedMessage(dispute,
                null,
                (errorMessage, throwable) -> new Popup().warning(errorMessage).show());
    }

    public boolean isReadyForTxBroadcast() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService) && GUIUtil.isReadyForTxBroadcastOrShowPopup(xmrWalletService);
    }

    public boolean isBootstrappedOrShowPopup() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }

    public void onMoveInvalidTradeToFailedTrades(Trade trade) {
        tradeManager.onMoveInvalidTradeToFailedTrades(trade);
    }

    public boolean isSignWitnessTrade() {
        return accountAgeWitnessService.isSignWitnessTrade(selectedTrade);
    }
}

