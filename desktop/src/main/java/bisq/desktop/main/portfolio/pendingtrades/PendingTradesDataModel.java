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

package bisq.desktop.main.portfolio.pendingtrades;

import bisq.desktop.Navigation;
import bisq.desktop.common.model.ActivatableDataModel;
import bisq.desktop.main.MainView;
import bisq.desktop.main.overlays.notifications.NotificationCenter;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.main.overlays.windows.WalletPasswordWindow;
import bisq.desktop.main.support.SupportView;
import bisq.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import bisq.desktop.main.support.dispute.client.mediation.MediationClientView;
import bisq.desktop.util.GUIUtil;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.api.CoreDisputesService;
import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OfferUtil;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeAlreadyOpenException;
import bisq.core.support.dispute.DisputeList;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.arbitration.ArbitrationManager;
import bisq.core.support.dispute.mediation.MediationManager;
import bisq.core.support.traderchat.TraderChatManager;
import bisq.core.trade.BuyerTrade;
import bisq.core.trade.SellerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.protocol.BuyerProtocol;
import bisq.core.trade.protocol.SellerProtocol;
import bisq.core.user.Preferences;

import bisq.network.p2p.P2PService;

import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.PubKeyRingProvider;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.Coin;

import com.google.inject.Inject;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import org.bouncycastle.crypto.params.KeyParameter;

import java.util.Date;
import java.util.stream.Collectors;

import lombok.Getter;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;

public class PendingTradesDataModel extends ActivatableDataModel {
    @Getter
    public final TradeManager tradeManager;
    public final XmrWalletService xmrWalletService;
    public final ArbitrationManager arbitrationManager;
    public final MediationManager mediationManager;
    private final P2PService p2PService;
    private final CoreMoneroConnectionsService connectionService;
    @Getter
    private final AccountAgeWitnessService accountAgeWitnessService;
    public final Navigation navigation;
    public final WalletPasswordWindow walletPasswordWindow;
    private final NotificationCenter notificationCenter;
    private final OfferUtil offerUtil;

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
                                  CoreMoneroConnectionsService connectionService,
                                  AccountAgeWitnessService accountAgeWitnessService,
                                  Navigation navigation,
                                  WalletPasswordWindow walletPasswordWindow,
                                  NotificationCenter notificationCenter,
                                  OfferUtil offerUtil,
                                  CoreDisputesService disputesService) {
        this.tradeManager = tradeManager;
        this.xmrWalletService = xmrWalletService;
        this.pubKeyRingProvider = pubKeyRingProvider;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.traderChatManager = traderChatManager;
        this.preferences = preferences;
        this.p2PService = p2PService;
        this.connectionService = connectionService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.walletPasswordWindow = walletPasswordWindow;
        this.notificationCenter = notificationCenter;
        this.offerUtil = offerUtil;
        this.disputesService = disputesService;

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

    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Trade trade = getTrade();
        checkNotNull(trade, "trade must not be null");
        checkArgument(trade instanceof BuyerTrade, "Check failed: trade instanceof BuyerTrade");
        ((BuyerProtocol) tradeManager.getTradeProtocol(trade)).onPaymentStarted(resultHandler, errorMessageHandler);
    }

    public void onFiatPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
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

    Coin getTradeFeeInBTC() {
        Trade trade = getTrade();
        if (trade != null) {
            Offer offer = trade.getOffer();
            if (isMaker()) {
                if (offer != null) {
                    return offer.getMakerFee();
                } else {
                    log.error("offer is null");
                    return Coin.ZERO;
                }
            } else {
                return trade.getTakerFee();
            }
        } else {
            log.error("Trade is null at getTotalFees");
            return Coin.ZERO;
        }
    }

    Coin getTxFee() {
        Trade trade = getTrade();
        if (trade != null) {
            if (isMaker()) {
                Offer offer = trade.getOffer();
                if (offer != null) {
                    return offer.getTxFee();
                } else {
                    log.error("offer is null");
                    return Coin.ZERO;
                }
            } else {
                return trade.getTxFee().multiply(3);
            }
        } else {
            log.error("Trade is null at getTotalFees");
            return Coin.ZERO;
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
        list.clear();
        list.addAll(tradeManager.getObservableList().stream().map(PendingTradesListItem::new).collect(Collectors.toList()));

        // we sort by date, earliest first
        list.sort((o1, o2) -> o2.getTrade().getDate().compareTo(o1.getTrade().getDate()));

        selectBestItem();
    }

    private void selectBestItem() {
        if (list.size() == 1)
            doSelectItem(list.get(0));
        else if (list.size() > 1 && (selectedItemProperty.get() == null || !list.contains(selectedItemProperty.get())))
            doSelectItem(list.get(0));
        else if (list.size() == 0)
            doSelectItem(null);
    }

    private void selectItemByTradeId(String tradeId) {
        if (activated) {
            list.stream().filter(e -> e.getTrade().getId().equals(tradeId)).findAny().ifPresent(this::doSelectItem);
        }
    }

    private void doSelectItem(@Nullable PendingTradesListItem item) {
        if (selectedTrade != null)
            selectedTrade.stateProperty().removeListener(tradeStateChangeListener);

        if (item != null) {
            selectedTrade = item.getTrade();
            if (selectedTrade == null) {
                log.error("selectedTrade is null");
                return;
            }

            MoneroTxWallet makerDepositTx = selectedTrade.getMakerDepositTx();
            MoneroTxWallet takerDepositTx = selectedTrade.getTakerDepositTx();
            String tradeId = selectedTrade.getId();
            tradeStateChangeListener = (observable, oldValue, newValue) -> {
                if (makerDepositTx != null && takerDepositTx != null) { // TODO (woodser): this treats separate deposit ids as one unit, being both available or unavailable
                    makerTxId.set(makerDepositTx.getHash());
                    takerTxId.set(takerDepositTx.getHash());
                    notificationCenter.setSelectedTradeId(tradeId);
                    selectedTrade.stateProperty().removeListener(tradeStateChangeListener);
                } else {
                  makerTxId.set("");
                  takerTxId.set("");
                }
            };
            selectedTrade.stateProperty().addListener(tradeStateChangeListener);

            Offer offer = selectedTrade.getOffer();
            if (offer == null) {
                log.error("offer is null");
                return;
            }

            isMaker = tradeManager.isMyOffer(offer);
            if (makerDepositTx != null && takerDepositTx != null) {
              makerTxId.set(makerDepositTx.getHash());
              takerTxId.set(takerDepositTx.getHash());
            } else {
                makerTxId.set("");
                takerTxId.set("");
            }
            notificationCenter.setSelectedTradeId(tradeId);
        } else {
            selectedTrade = null;
            makerTxId.set("");
            takerTxId.set("");
            notificationCenter.setSelectedTradeId(null);
        }
        selectedItemProperty.set(item);
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

      // We do not support opening a dispute if the deposit tx is null. Traders have to use the support channel at keybase
      // in such cases. The mediators or arbitrators could not help anyway with a payout in such cases.
      String depositTxId = null;
      if (isMaker) {
        if (trade.getMaker().getDepositTxHash() == null) {
          log.error("Deposit tx must not be null");
          new Popup().instruction(Res.get("portfolio.pending.error.depositTxNull")).show();
          return;
        }
        depositTxId = trade.getMaker().getDepositTxHash();
      } else {
        if (trade.getTaker().getDepositTxHash() == null) {
          log.error("Deposit tx must not be null");
          new Popup().instruction(Res.get("portfolio.pending.error.depositTxNull")).show();
          return;
        }
        depositTxId = trade.getTaker().getDepositTxHash();
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
      MoneroTxWallet payoutTx = trade.getPayoutTx();
      MoneroWallet  multisigWallet = xmrWalletService.getMultisigWallet(trade.getId());
      String updatedMultisigHex = multisigWallet.getMultisigHex();
      if (payoutTx != null) {
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
          useMediation = disputeState == Trade.DisputeState.NO_DISPUTE || disputeState == Trade.DisputeState.MEDIATION_REQUESTED;
          // in case of arbitration disputeState == Trade.DisputeState.ARBITRATION_REQUESTED
          useArbitration = disputeState == Trade.DisputeState.MEDIATION_CLOSED || disputeState == Trade.DisputeState.DISPUTE_REQUESTED;
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
//                  (offer.getDirection() == OfferPayload.Direction.BUY) == isMaker,
//                  isMaker,
//                  pubKeyRing,
//                  trade.getDate().getTime(),
//                  trade.getMaxTradePeriodDate().getTime(),
//                  trade.getContract(),
//                  trade.getContractHash(),
//                  depositTxSerialized,
//                  payoutTxSerialized,
//                  depositTxHashAsString,
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
            PubKeyRing arbitratorPubKeyRing = trade.getArbitratorPubKeyRing();
            checkNotNull(arbitratorPubKeyRing, "arbitratorPubKeyRing must not be null");
            byte[] depositTxSerialized = null;  // depositTx.bitcoinSerialize();  // TODO (woodser): no serialized txs in xmr
            Dispute dispute = new Dispute(new Date().getTime(),
                    trade.getId(),
                    pubKeyRingProvider.get().hashCode(), // trader id
                    true,
                    (offer.getDirection() == OfferPayload.Direction.BUY) == isMaker,
                    isMaker,
                    pubKeyRingProvider.get(),
                    trade.getDate().getTime(),
                    trade.getMaxTradePeriodDate().getTime(),
                    trade.getContract(),
                    trade.getContractHash(),
                    depositTxSerialized,
                    payoutTxSerialized,
                    depositTxId,
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
            sendOpenNewDisputeMessage(dispute, false, disputeManager, updatedMultisigHex);
            tradeManager.requestPersistence();
        } else if (useArbitration) {
          // Only if we have completed mediation we allow arbitration
          disputeManager = arbitrationManager;
          Dispute dispute = disputesService.createDisputeForTrade(trade, offer, pubKeyRingProvider.get(), isMaker, isSupportTicket);
          sendOpenNewDisputeMessage(dispute, false, disputeManager, updatedMultisigHex);
          tradeManager.requestPersistence();
        } else {
            log.warn("Invalid dispute state {}", disputeState.name());
        }
    }

    private void sendOpenNewDisputeMessage(Dispute dispute, boolean reOpen, DisputeManager<? extends DisputeList<Dispute>> disputeManager, String senderMultisigHex) {
        disputeManager.sendOpenNewDisputeMessage(dispute, reOpen, senderMultisigHex,
                () -> navigation.navigateTo(MainView.class, SupportView.class, ArbitrationClientView.class), (errorMessage, throwable) -> {
                    if ((throwable instanceof DisputeAlreadyOpenException)) {
                        errorMessage += "\n\n" + Res.get("portfolio.pending.openAgainDispute.msg");
                        new Popup().warning(errorMessage)
                                .actionButtonText(Res.get("portfolio.pending.openAgainDispute.button"))
                                .onAction(() -> sendOpenNewDisputeMessage(dispute, true, disputeManager, senderMultisigHex))
                                .closeButtonText(Res.get("shared.cancel")).show();
                    } else {
                        new Popup().warning(errorMessage).show();
                    }
                });
    }

    public boolean isReadyForTxBroadcast() {
        return GUIUtil.isReadyForTxBroadcastOrShowPopup(p2PService, connectionService);
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

