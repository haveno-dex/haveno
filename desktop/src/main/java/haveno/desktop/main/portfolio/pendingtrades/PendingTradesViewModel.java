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

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferUtil;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeUtil;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.common.model.ViewModel;
import static haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel.SellerState.UNDEFINED;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.Date;
import java.util.stream.Collectors;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javax.annotation.Nullable;
import lombok.Getter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

public class PendingTradesViewModel extends ActivatableWithDataModel<PendingTradesDataModel> implements ViewModel {

    @Getter
    @Nullable
    private Trade trade;

    interface State {
    }

    enum BuyerState implements State {
        UNDEFINED,
        STEP1,
        STEP2,
        STEP3,
        STEP4
    }

    enum SellerState implements State {
        UNDEFINED,
        STEP1,
        STEP2,
        STEP3,
        STEP4
    }

    public final CoinFormatter btcFormatter;
    public final BtcAddressValidator btcAddressValidator;
    final AccountAgeWitnessService accountAgeWitnessService;
    public final P2PService p2PService;
    private final ClosedTradableManager closedTradableManager;
    private final OfferUtil offerUtil;
    private final TradeUtil tradeUtil;
    public final ClockWatcher clockWatcher;
    @Getter
    private final Navigation navigation;
    @Getter
    private final User user;

    private final ObjectProperty<BuyerState> buyerState = new SimpleObjectProperty<>();
    private final ObjectProperty<SellerState> sellerState = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<MessageState> paymentSentMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);
    private Subscription tradeStateSubscription;
    private Subscription paymentAccountDecryptedSubscription;
    private Subscription payoutStateSubscription;
    private Subscription messageStateSubscription;
    @Getter
    protected final IntegerProperty mempoolStatus = new SimpleIntegerProperty();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PendingTradesViewModel(PendingTradesDataModel dataModel,
                                  @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                  BtcAddressValidator btcAddressValidator,
                                  P2PService p2PService,
                                  ClosedTradableManager closedTradableManager,
                                  OfferUtil offerUtil,
                                  TradeUtil tradeUtil,
                                  AccountAgeWitnessService accountAgeWitnessService,
                                  ClockWatcher clockWatcher,
                                  Navigation navigation,
                                  User user) {
        super(dataModel);

        this.btcFormatter = btcFormatter;
        this.btcAddressValidator = btcAddressValidator;
        this.p2PService = p2PService;
        this.closedTradableManager = closedTradableManager;
        this.offerUtil = offerUtil;
        this.tradeUtil = tradeUtil;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.clockWatcher = clockWatcher;
        this.navigation = navigation;
        this.user = user;
    }


    @Override
    protected void deactivate() {
        synchronized (this) {
            if (tradeStateSubscription != null) {
                tradeStateSubscription.unsubscribe();
                tradeStateSubscription = null;
            }

            if (paymentAccountDecryptedSubscription != null) {
                paymentAccountDecryptedSubscription.unsubscribe();
                paymentAccountDecryptedSubscription = null;
            }

            if (payoutStateSubscription != null) {
                payoutStateSubscription.unsubscribe();
                payoutStateSubscription = null;
            }

            if (messageStateSubscription != null) {
                messageStateSubscription.unsubscribe();
                messageStateSubscription = null;
            }
        }
    }

    // Don't set own listener as we need to control the order of the calls
    public void onSelectedItemChanged(PendingTradesListItem selectedItem) {
        synchronized (this) {
            if (tradeStateSubscription != null) {
                tradeStateSubscription.unsubscribe();
                sellerState.set(SellerState.UNDEFINED);
                buyerState.set(BuyerState.UNDEFINED);
            }

            if (paymentAccountDecryptedSubscription != null) {
                paymentAccountDecryptedSubscription.unsubscribe();
            }

            if (payoutStateSubscription != null) {
                payoutStateSubscription.unsubscribe();
                sellerState.set(SellerState.UNDEFINED);
                buyerState.set(BuyerState.UNDEFINED);
            }

            if (messageStateSubscription != null) {
                messageStateSubscription.unsubscribe();
                paymentSentMessageStateProperty.set(MessageState.UNDEFINED);
            }

            if (selectedItem != null) {
                this.trade = selectedItem.getTrade();
                tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), state -> {
                    onTradeStateChanged(state);
                });
                paymentAccountDecryptedSubscription = EasyBind.subscribe(trade.getProcessModel().getPaymentAccountDecryptedProperty(), decrypted -> {
                    refresh();
                });
                payoutStateSubscription = EasyBind.subscribe(trade.payoutStateProperty(), state -> {
                    onPayoutStateChanged(state);
                });
                messageStateSubscription = EasyBind.subscribe(trade.getSeller().getPaymentSentMessageStateProperty(), this::onPaymentSentMessageStateChanged);
            }
        }
    }

    private void refresh() {
        UserThread.execute(() -> {
            sellerState.set(UNDEFINED);
            buyerState.set(BuyerState.UNDEFINED);
            onTradeStateChanged(trade.getState());
            if (trade.isPayoutPublished()) onPayoutStateChanged(trade.getPayoutState()); // TODO: payout state takes precedence in case PaymentReceivedMessage not processed
            else onTradeStateChanged(trade.getState());
        });
    }

    private void onPaymentSentMessageStateChanged(MessageState messageState) {
        paymentSentMessageStateProperty.set(messageState);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    ReadOnlyObjectProperty<BuyerState> getBuyerState() {
        return buyerState;
    }

    ReadOnlyObjectProperty<SellerState> getSellerState() {
        return sellerState;
    }

    public String getPayoutAmountBeforeCost() {
        return dataModel.getTrade() != null
                ? HavenoUtils.formatXmr(dataModel.getTrade().getPayoutAmountBeforeCost(), true)
                : "";
    }

    String getMarketLabel(PendingTradesListItem item) {
        return item == null ? "" : tradeUtil.getMarketDescription(item.getTrade());
    }

    public String getRemainingTradeDurationAsWords() {
        checkNotNull(dataModel.getTrade(), "model's trade must not be null");
        return tradeUtil.getRemainingTradeDurationAsWords(dataModel.getTrade());
    }

    public double getRemainingTradeDurationAsPercentage() {
        checkNotNull(dataModel.getTrade(), "model's trade must not be null");
        return tradeUtil.getRemainingTradeDurationAsPercentage(dataModel.getTrade());
    }

    public String getDateForOpenDispute() {
        checkNotNull(dataModel.getTrade(), "model's trade must not be null");
        return DisplayUtils.formatDateTime(tradeUtil.getDateForOpenDispute(dataModel.getTrade()));
    }

    public boolean showWarning() {
        checkNotNull(dataModel.getTrade(), "model's trade must not be null");
        Date halfTradePeriodDate = tradeUtil.getHalfTradePeriodDate(dataModel.getTrade());
        return halfTradePeriodDate != null && new Date().after(halfTradePeriodDate);
    }

    public boolean showDispute() {
        return getMaxTradePeriodDate() != null && new Date().after(getMaxTradePeriodDate());
    }

    //

    String getMyRole(PendingTradesListItem item) {
        return tradeUtil.getRole(item.getTrade());
    }

    String getPaymentMethod(PendingTradesListItem item) {
        return item == null ? "" : tradeUtil.getPaymentMethodNameWithCountryCode(item.getTrade());
    }

    // summary
    public String getTradeVolume() {
        return dataModel.getTrade() != null
                ? HavenoUtils.formatXmr(dataModel.getTrade().getAmount(), true)
                : "";
    }

    public String getFiatVolume() {
        return dataModel.getTrade() != null
                ? VolumeUtil.formatVolumeWithCode(dataModel.getTrade().getVolume())
                : "";
    }

    public String getTradeFee() {
        if (trade != null && dataModel.getOffer() != null && trade.getAmount() != null) {
            checkNotNull(dataModel.getTrade());

            BigInteger tradeFeeInXmr = dataModel.getTradeFee();

            String percentage = GUIUtil.getPercentageOfTradeAmount(tradeFeeInXmr, trade.getAmount());
            return HavenoUtils.formatXmr(tradeFeeInXmr, true) + percentage;
        } else {
            return "";
        }
    }

    public String getSecurityDeposit() {
        Offer offer = dataModel.getOffer();
        Trade trade = dataModel.getTrade();
        if (offer != null && trade != null && trade.getAmount() != null) {
            BigInteger securityDeposit = dataModel.isBuyer() ?
                    offer.getMaxBuyerSecurityDeposit()
                    : offer.getMaxSellerSecurityDeposit();

            String percentage = GUIUtil.getPercentageOfTradeAmount(securityDeposit, trade.getAmount());
            return HavenoUtils.formatXmr(securityDeposit, true) + percentage;
        } else {
            return "";
        }
    }

    public boolean isBlockChainMethod() {
        return offerUtil.isBlockChainPaymentMethod(dataModel.getOffer());
    }

    public int getNumPastTrades(Trade trade) {
        return closedTradableManager.getObservableList().stream()
                .filter(e -> {
                    if (e instanceof Trade) {
                        Trade t = (Trade) e;
                        return t.getTradePeerNodeAddress() != null &&
                                trade.getTradePeerNodeAddress() != null &&
                                t.getTradePeerNodeAddress().getFullAddress().equals(trade.getTradePeerNodeAddress().getFullAddress());
                    } else
                        return false;

                })
                .collect(Collectors.toSet())
                .size();
    }

    @Nullable
    private Date getMaxTradePeriodDate() {
        return dataModel.getTrade() != null
                ? dataModel.getTrade().getMaxTradePeriodDate()
                : null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // States
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onTradeStateChanged(Trade.State tradeState) {
        log.debug("UI tradeState={}, id={}",
                tradeState,
                trade != null ? trade.getShortId() : "trade is null");

        // arbitrator trade view only shows tx status
        if (trade instanceof ArbitratorTrade) {
            buyerState.set(BuyerState.STEP1);
            sellerState.set(SellerState.STEP1);
            return;
        }

        if (trade.isCompleted()) {
            sellerState.set(UNDEFINED);
            buyerState.set(BuyerState.UNDEFINED);
            return;
        }

        switch (tradeState) {

            // initialization
            case PREPARATION:
            case MULTISIG_PREPARED:
            case MULTISIG_MADE:
            case MULTISIG_EXCHANGED:
            case MULTISIG_COMPLETED:
            case CONTRACT_SIGNATURE_REQUESTED:
            case CONTRACT_SIGNED:
            case SENT_PUBLISH_DEPOSIT_TX_REQUEST:
            case SEND_FAILED_PUBLISH_DEPOSIT_TX_REQUEST:
            case SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST:
                buyerState.set(BuyerState.UNDEFINED);
                sellerState.set(UNDEFINED); // TODO: show view while trade initializes?
                break;

            case ARBITRATOR_PUBLISHED_DEPOSIT_TXS:
            case DEPOSIT_TXS_SEEN_IN_NETWORK:
            case DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN: // TODO: separate step to wait for first confirmation
                buyerState.set(BuyerState.STEP1);
                sellerState.set(SellerState.STEP1);
                break;

            // buyer and seller step 2
            // deposits unlocked
            case DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN:
                buyerState.set(BuyerState.STEP2);
                sellerState.set(SellerState.STEP2);
                break;

            // buyer step 3
            case BUYER_CONFIRMED_PAYMENT_SENT: // UI action
            case BUYER_SENT_PAYMENT_SENT_MSG:  // PAYMENT_SENT_MSG sent
            case BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG:  // PAYMENT_SENT_MSG arrived
                // We don't switch the UI before we got the feedback of the msg delivery
                buyerState.set(BuyerState.STEP2);
                sellerState.set(trade.isPayoutPublished() ? SellerState.STEP4 : SellerState.STEP3);
                break;
            case BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG: // PAYMENT_SENT_MSG in mailbox
            case SELLER_RECEIVED_PAYMENT_SENT_MSG: // PAYMENT_SENT_MSG acked
                buyerState.set(BuyerState.STEP3);
                break;
            case BUYER_SEND_FAILED_PAYMENT_SENT_MSG:  // PAYMENT_SENT_MSG failed
                // if failed we need to repeat sending so back to step 2
                buyerState.set(BuyerState.STEP2);
                break;

            // payment received
            case SELLER_SENT_PAYMENT_RECEIVED_MSG:
                if (trade instanceof BuyerTrade) {
                    buyerState.set(BuyerState.UNDEFINED); // TODO: resetting screen to populate summary information which can be missing before payout message processed
                    buyerState.set(BuyerState.STEP4);
                } else if (trade instanceof SellerTrade) {
                    sellerState.set(trade.isPayoutPublished() ? SellerState.STEP4 : SellerState.STEP3);
                }
                break;

            // seller step 3 or 4 if published
            case SELLER_CONFIRMED_PAYMENT_RECEIPT:
            case SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG:
            case SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG:
            case SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG:
            case BUYER_RECEIVED_PAYMENT_RECEIVED_MSG:
                sellerState.set(trade.isPayoutPublished() ? SellerState.STEP4 : SellerState.STEP3);
                break;

            default:
                sellerState.set(UNDEFINED);
                buyerState.set(BuyerState.UNDEFINED);
                log.warn("unhandled processState " + tradeState);
                DevEnv.logErrorAndThrowIfDevMode("unhandled processState " + tradeState);
                break;
        }
    }

    private void onPayoutStateChanged(Trade.PayoutState payoutState) {
        log.debug("UI payoutState={}, id={}",
                payoutState,
                trade != null ? trade.getShortId() : "trade is null");

        if (trade instanceof ArbitratorTrade) return;

        switch (payoutState) {
            case PAYOUT_PUBLISHED:
            case PAYOUT_CONFIRMED:
            case PAYOUT_UNLOCKED:
                sellerState.set(SellerState.STEP4);
                buyerState.set(BuyerState.STEP4);
                break;
            default:
                break;
        }
    }
}
