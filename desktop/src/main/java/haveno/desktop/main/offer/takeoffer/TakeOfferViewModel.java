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

package haveno.desktop.main.offer.takeoffer;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferRestrictions;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.CoinUtil;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.common.model.ViewModel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.deposit.DepositView;
import haveno.desktop.main.offer.OfferViewModelUtil;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionListener;
import java.math.BigInteger;
import static javafx.beans.binding.Bindings.createStringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.Callback;
import javax.annotation.Nullable;

class TakeOfferViewModel extends ActivatableWithDataModel<TakeOfferDataModel> implements ViewModel {
    final TakeOfferDataModel dataModel;
    private final OfferUtil offerUtil;
    private final XmrValidator xmrValidator;
    private final P2PService p2PService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final CoinFormatter xmrFormatter;

    private String amountRange;
    private String paymentLabel;
    private boolean takeOfferRequested;
    private Trade trade;
    private Offer offer;
    private String price;
    private String amountDescription;

    final StringProperty amount = new SimpleStringProperty();
    final StringProperty volume = new SimpleStringProperty();
    final StringProperty volumeDescriptionLabel = new SimpleStringProperty();
    final StringProperty totalToPay = new SimpleStringProperty();
    final StringProperty errorMessage = new SimpleStringProperty();
    final StringProperty offerWarning = new SimpleStringProperty();
    final StringProperty spinnerInfoText = new SimpleStringProperty("");
    final StringProperty tradeFee = new SimpleStringProperty();
    final StringProperty tradeFeeInXmrWithFiat = new SimpleStringProperty();
    final StringProperty tradeFeeDescription = new SimpleStringProperty();
    final BooleanProperty isTradeFeeVisible = new SimpleBooleanProperty(false);

    final BooleanProperty isOfferAvailable = new SimpleBooleanProperty();
    final BooleanProperty isTakeOfferButtonDisabled = new SimpleBooleanProperty(true);
    final BooleanProperty isNextButtonDisabled = new SimpleBooleanProperty(true);
    final BooleanProperty isWaitingForFunds = new SimpleBooleanProperty();
    final BooleanProperty showWarningInvalidBtcDecimalPlaces = new SimpleBooleanProperty();
    final BooleanProperty showTransactionPublishedScreen = new SimpleBooleanProperty();
    final BooleanProperty takeOfferCompleted = new SimpleBooleanProperty();
    final BooleanProperty showPayFundsScreenDisplayed = new SimpleBooleanProperty();

    final ObjectProperty<InputValidator.ValidationResult> amountValidationResult = new SimpleObjectProperty<>();

    private ChangeListener<String> amountStrListener;
    private ChangeListener<BigInteger> amountListener;
    private ChangeListener<Boolean> isWalletFundedListener;
    private ChangeListener<Trade.State> tradeStateListener;
    private ChangeListener<Offer.State> offerStateListener;
    private ConnectionListener connectionListener;
    //  private Subscription isFeeSufficientSubscription;
    private Runnable takeOfferResultHandler;
    String marketPriceMargin;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TakeOfferViewModel(TakeOfferDataModel dataModel,
                              OfferUtil offerUtil,
                              XmrValidator btcValidator,
                              P2PService p2PService,
                              AccountAgeWitnessService accountAgeWitnessService,
                              Navigation navigation,
                              @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter) {
        super(dataModel);
        this.dataModel = dataModel;
        this.offerUtil = offerUtil;
        this.xmrValidator = btcValidator;
        this.p2PService = p2PService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.xmrFormatter = btcFormatter;
        createListeners();
    }

    @Override
    protected void activate() {
        addBindings();
        addListeners();

        String buyVolumeDescriptionKey = offer.isTraditionalOffer() ? "createOffer.amountPriceBox.buy.volumeDescription" :
                "createOffer.amountPriceBox.buy.volumeDescriptionCrypto";
        String sellVolumeDescriptionKey = offer.isTraditionalOffer() ? "createOffer.amountPriceBox.sell.volumeDescription" :
                "createOffer.amountPriceBox.sell.volumeDescriptionCrypto";

        if (dataModel.getDirection() == OfferDirection.SELL) {
            volumeDescriptionLabel.set(Res.get(buyVolumeDescriptionKey, dataModel.getCurrencyCode()));
        } else {
            volumeDescriptionLabel.set(Res.get(sellVolumeDescriptionKey, dataModel.getCurrencyCode()));
        }

        amount.set(HavenoUtils.formatXmr(dataModel.getAmount().get()));
        showTransactionPublishedScreen.set(false);

        // when getting back to an open screen we want to re-check again
        isOfferAvailable.set(false);
        checkNotNull(offer, "offer must not be null");

        offer.stateProperty().addListener(offerStateListener);
        applyOfferState(offer.stateProperty().get());

        updateButtonDisableState();

        updateSpinnerInfo();

        isTradeFeeVisible.setValue(false);
    }

    @Override
    protected void deactivate() {
        removeBindings();
        removeListeners();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before doActivate
    void initWithData(Offer offer) {
        dataModel.initWithData(offer);
        this.offer = offer;

        String buyAmountDescriptionKey = offer.isTraditionalOffer() ? "takeOffer.amountPriceBox.buy.amountDescription" :
                "takeOffer.amountPriceBox.buy.amountDescriptionCrypto";
        String sellAmountDescriptionKey = offer.isTraditionalOffer() ? "takeOffer.amountPriceBox.sell.amountDescription" :
                "takeOffer.amountPriceBox.sell.amountDescriptionCrypto";

        amountDescription = offer.isBuyOffer()
                ? Res.get(buyAmountDescriptionKey)
                : Res.get(sellAmountDescriptionKey);

        amountRange = HavenoUtils.formatXmr(offer.getMinAmount()) + " - " + HavenoUtils.formatXmr(offer.getAmount());
        price = FormattingUtils.formatPrice(dataModel.tradePrice);
        marketPriceMargin = FormattingUtils.formatToPercent(offer.getMarketPriceMarginPct());
        paymentLabel = Res.get("takeOffer.fundsBox.paymentLabel", offer.getShortId());

        checkNotNull(dataModel.getAddressEntry(), "dataModel.getAddressEntry() must not be null");

        errorMessage.set(offer.getErrorMessage());

        xmrValidator.setMaxValue(offer.getAmount());
        xmrValidator.setMaxTradeLimit(BigInteger.valueOf(dataModel.getMaxTradeLimit()));
        xmrValidator.setMinValue(offer.getMinAmount());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onTakeOffer(Runnable resultHandler) {
        takeOfferResultHandler = resultHandler;
        takeOfferRequested = true;
        showTransactionPublishedScreen.set(false);
        dataModel.onTakeOffer(trade -> {
            this.trade = trade;
            takeOfferCompleted.set(true);
            trade.stateProperty().addListener(tradeStateListener);
            applyTradeState();
            applyTradeErrorMessage(trade.getErrorMessage());
            takeOfferCompleted.set(true);
        }, errMessage -> {
            applyTradeErrorMessage(errMessage);
        });

        updateButtonDisableState();
        updateSpinnerInfo();
    }

    public void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        dataModel.onPaymentAccountSelected(paymentAccount);
        xmrValidator.setMaxTradeLimit(BigInteger.valueOf(dataModel.getMaxTradeLimit()));
        updateButtonDisableState();
    }

    public void onShowPayFundsScreen() {
        dataModel.onShowPayFundsScreen();
        showPayFundsScreenDisplayed.set(true);
        updateSpinnerInfo();
    }

    boolean fundFromSavingsWallet() {
        dataModel.fundFromSavingsWallet();
        if (dataModel.getIsXmrWalletFunded().get()) {
            updateButtonDisableState();
            return true;
        } else {
            new Popup().warning(Res.get("shared.notEnoughFunds",
                    HavenoUtils.formatXmr(dataModel.getTotalToPay().get(), true),
                    HavenoUtils.formatXmr(dataModel.getTotalAvailableBalance(), true)))
                    .actionButtonTextWithGoTo("funds.tab.deposit")
                    .onAction(() -> navigation.navigateTo(MainView.class, FundsView.class, DepositView.class))
                    .show();
            return false;
        }
    }

    private void applyTakerFee() {
        tradeFeeDescription.set(Res.get("createOffer.tradeFee.descriptionXMROnly"));
        BigInteger takerFee = dataModel.getTakerFee();
        if (takerFee == null) {
            return;
        }

        isTradeFeeVisible.setValue(true);
        tradeFee.set(HavenoUtils.formatXmr(takerFee));
        tradeFeeInXmrWithFiat.set(OfferViewModelUtil.getTradeFeeWithFiatEquivalent(offerUtil,
                dataModel.getTakerFee(),
                xmrFormatter));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Handle focus
    ///////////////////////////////////////////////////////////////////////////////////////////

    // On focus out we do validation and apply the data to the model
    void onFocusOutAmountTextField(boolean oldValue, boolean newValue, String userInput) {
        if (oldValue && !newValue) {
            InputValidator.ValidationResult result = isXmrInputValid(amount.get());
            amountValidationResult.set(result);
            if (result.isValid) {
                showWarningInvalidBtcDecimalPlaces.set(!DisplayUtils.hasBtcValidDecimals(userInput, xmrFormatter));
                // only allow max 4 decimal places for xmr values
                setAmountToModel();
                // reformat input
                amount.set(HavenoUtils.formatXmr(dataModel.getAmount().get()));

                calculateVolume();

                Price tradePrice = dataModel.tradePrice;
                long maxTradeLimit = dataModel.getMaxTradeLimit();
                if (PaymentMethod.isRoundedForAtmCash(dataModel.getPaymentMethod().getId())) {
                    BigInteger adjustedAmountForAtm = CoinUtil.getRoundedAtmCashAmount(dataModel.getAmount().get(), tradePrice, maxTradeLimit);
                    dataModel.maybeApplyAmount(adjustedAmountForAtm);
                } else if (dataModel.getOffer().isTraditionalOffer()) {
                    BigInteger roundedAmount = CoinUtil.getRoundedAmount(dataModel.getAmount().get(), tradePrice, maxTradeLimit, dataModel.getOffer().getCurrencyCode(), dataModel.getOffer().getPaymentMethodId());
                    dataModel.maybeApplyAmount(roundedAmount);
                }
                amount.set(HavenoUtils.formatXmr(dataModel.getAmount().get()));

                if (!dataModel.isMinAmountLessOrEqualAmount())
                    amountValidationResult.set(new InputValidator.ValidationResult(false,
                            Res.get("takeOffer.validation.amountSmallerThanMinAmount")));

                if (dataModel.isAmountLargerThanOfferAmount())
                    amountValidationResult.set(new InputValidator.ValidationResult(false,
                            Res.get("takeOffer.validation.amountLargerThanOfferAmount")));

                if (dataModel.wouldCreateDustForMaker())
                    amountValidationResult.set(new InputValidator.ValidationResult(false,
                            Res.get("takeOffer.validation.amountLargerThanOfferAmountMinusFee")));
            } else if (xmrValidator.getMaxTradeLimit() != null && xmrValidator.getMaxTradeLimit().equals(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT)) {
                if (dataModel.getDirection() == OfferDirection.BUY) {
                    new Popup().information(Res.get("popup.warning.tradeLimitDueAccountAgeRestriction.seller",
                            HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true),
                            Res.get("offerbook.warning.newVersionAnnouncement")))
                            .width(900)
                            .show();
                } else {
                    new Popup().information(Res.get("popup.warning.tradeLimitDueAccountAgeRestriction.buyer",
                            HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true),
                            Res.get("offerbook.warning.newVersionAnnouncement")))
                            .width(900)
                            .show();
                }

            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // States
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void applyOfferState(Offer.State state) {
        UserThread.execute(() -> {
            offerWarning.set(null);

            // We have 2 situations handled here:
            // 1. when clicking take offer in the offerbook screen, we do the availability check
            // 2. Before actually taking the offer in the take offer screen, we check again the availability as some time might have passed in the meantime
            // So we use the takeOfferRequested flag to display different network_messages depending on the context.
            switch (state) {
                case UNKNOWN:
                    break;
                case OFFER_FEE_RESERVED:
                    // irrelevant for taker
                    break;
                case AVAILABLE:
                    isOfferAvailable.set(true);
                    updateButtonDisableState();
                    break;
                case NOT_AVAILABLE:
                    if (takeOfferRequested)
                        offerWarning.set(Res.get("takeOffer.failed.offerNotAvailable"));
                    else
                        offerWarning.set(Res.get("takeOffer.failed.offerTaken"));
                    takeOfferRequested = false;
                    break;
                case INVALID:
                    offerWarning.set(Res.get("takeOffer.failed.offerInvalid"));
                    takeOfferRequested = false;
                    break;
                case REMOVED:
                    // if (takeOfferRequested) // TODO: show any warning or removed is expected?
                    //     offerWarning.set(Res.get("takeOffer.failed.offerRemoved"));
    
                    takeOfferRequested = false;
                    break;
                case MAKER_OFFLINE:
                    if (takeOfferRequested)
                        offerWarning.set(Res.get("takeOffer.failed.offererNotOnline"));
                    else
                        offerWarning.set(Res.get("takeOffer.failed.offererOffline"));
                    takeOfferRequested = false;
                    break;
                default:
                    log.error("Unhandled offer state: " + state);
                    break;
            }
    
            updateSpinnerInfo();
    
            updateButtonDisableState();
        });
    }

    private void applyTradeErrorMessage(@Nullable String errorMessage) {
        if (errorMessage != null) {
            String appendMsg = "";
            if (trade != null) {
                if (trade.isPayoutPublished()) appendMsg = Res.get("takeOffer.error.payoutPublished");
                else {
                    switch (trade.getState().getPhase()) {
                    case INIT:
                        appendMsg = Res.get("takeOffer.error.noFundsLost");
                        break;
                    case DEPOSIT_REQUESTED:
                        appendMsg = Res.get("takeOffer.error.feePaid");
                        break;
                    case DEPOSITS_PUBLISHED:
                    case PAYMENT_SENT:
                    case PAYMENT_RECEIVED:
                        appendMsg = Res.get("takeOffer.error.depositPublished");
                        break;
                    default:
                        break;
                    }
                }
            }
            this.errorMessage.set(errorMessage + appendMsg);

            updateSpinnerInfo();

            if (takeOfferResultHandler != null)
                takeOfferResultHandler.run();
        } else {
            this.errorMessage.set(null);
        }
    }

    private void applyTradeState() {
        if (trade.isDepositRequested()) {
            if (takeOfferResultHandler != null)
                takeOfferResultHandler.run();

            showTransactionPublishedScreen.set(true);
            updateSpinnerInfo();
        }
    }

    private void updateButtonDisableState() {
        boolean inputDataValid = isXmrInputValid(amount.get()).isValid
                && dataModel.isMinAmountLessOrEqualAmount()
                && !dataModel.isAmountLargerThanOfferAmount()
                && isOfferAvailable.get()
                && !dataModel.wouldCreateDustForMaker();
        isNextButtonDisabled.set(!inputDataValid);
        isTakeOfferButtonDisabled.set(takeOfferRequested || !inputDataValid || !dataModel.getIsXmrWalletFunded().get());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addBindings() {
        volume.bind(createStringBinding(() -> VolumeUtil.formatVolume(dataModel.volume.get()), dataModel.volume));
        totalToPay.bind(createStringBinding(() -> HavenoUtils.formatXmr(dataModel.getTotalToPay().get(), true), dataModel.getTotalToPay()));
    }


    private void removeBindings() {
        volumeDescriptionLabel.unbind();
        volume.unbind();
        totalToPay.unbind();
    }

    private void createListeners() {
        amountStrListener = (ov, oldValue, newValue) -> {
            if (isXmrInputValid(newValue).isValid) {
                setAmountToModel();
                calculateVolume();
                dataModel.calculateTotalToPay();
                applyTakerFee();
            }
            updateButtonDisableState();
        };
        amountListener = (ov, oldValue, newValue) -> {
            amount.set(HavenoUtils.formatXmr(newValue));
            applyTakerFee();
        };
        isWalletFundedListener = (ov, oldValue, newValue) -> updateButtonDisableState();

        tradeStateListener = (ov, oldValue, newValue) -> applyTradeState();
        offerStateListener = (ov, oldValue, newValue) -> applyOfferState(newValue);

        connectionListener = new ConnectionListener() {
            @Override
            public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
                if (trade == null) return; // ignore if trade initializing
                if (connection.getPeersNodeAddressOptional().isPresent() &&
                        connection.getPeersNodeAddressOptional().get().equals(offer.getMakerNodeAddress())) {
                    offerWarning.set(Res.get("takeOffer.warning.connectionToPeerLost"));
                    updateSpinnerInfo();
                }
            }

            @Override
            public void onConnection(Connection connection) {
            }
        };
    }

    private void updateSpinnerInfo() {
        UserThread.execute(() -> {
            if (!showPayFundsScreenDisplayed.get() ||
                    offerWarning.get() != null ||
                    errorMessage.get() != null ||
                    showTransactionPublishedScreen.get()) {
                spinnerInfoText.set("");
            } else if (dataModel.getIsXmrWalletFunded().get()) {
                spinnerInfoText.set("");
               /* if (dataModel.isFeeFromFundingTxSufficient.get()) {
                    spinnerInfoText.set("");
                } else {
                    spinnerInfoText.set("Check if funding tx miner fee is sufficient...");
                }*/
            } else {
                spinnerInfoText.set(Res.get("shared.waitingForFunds"));
            }

            isWaitingForFunds.set(!spinnerInfoText.get().isEmpty());
        });
    }

    private void addListeners() {
        // Bidirectional bindings are used for all input fields: amount, price, volume and minAmount
        // We do volume/amount calculation during input, so user has immediate feedback
        amount.addListener(amountStrListener);

        // Binding with Bindings.createObjectBinding does not work because of bi-directional binding
        dataModel.getAmount().addListener(amountListener);

        dataModel.getIsXmrWalletFunded().addListener(isWalletFundedListener);
        p2PService.getNetworkNode().addConnectionListener(connectionListener);
       /* isFeeSufficientSubscription = EasyBind.subscribe(dataModel.isFeeFromFundingTxSufficient, newValue -> {
            updateButtonDisableState();
            updateSpinnerInfo();
        });*/
    }

    private void removeListeners() {
        amount.removeListener(amountStrListener);

        // Binding with Bindings.createObjectBinding does not work because of bi-directional binding
        dataModel.getAmount().removeListener(amountListener);

        dataModel.getIsXmrWalletFunded().removeListener(isWalletFundedListener);
        if (offer != null) {
            offer.stateProperty().removeListener(offerStateListener);
        }

        if (trade != null) {
            trade.stateProperty().removeListener(tradeStateListener);
        }
        p2PService.getNetworkNode().removeConnectionListener(connectionListener);
        //isFeeSufficientSubscription.unsubscribe();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void calculateVolume() {
        setAmountToModel();
        dataModel.calculateVolume();
    }

    private void setAmountToModel() {
        if (amount.get() != null && !amount.get().isEmpty()) {
            BigInteger amount = HavenoUtils.coinToAtomicUnits(DisplayUtils.parseToCoinWith4Decimals(this.amount.get(), xmrFormatter));
            long maxTradeLimit = dataModel.getMaxTradeLimit();
            Price price = dataModel.tradePrice;
            if (price != null) {
                if (dataModel.isRoundedForAtmCash()) {
                    amount = CoinUtil.getRoundedAtmCashAmount(amount, price, maxTradeLimit);
                } else if (dataModel.getOffer().isTraditionalOffer()) {
                    amount = CoinUtil.getRoundedAmount(amount, price, maxTradeLimit, dataModel.getOffer().getCurrencyCode(), dataModel.getOffer().getPaymentMethodId());
                }
            }
            dataModel.maybeApplyAmount(amount);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    CoinFormatter getXmrFormatter() {
        return xmrFormatter;
    }

    boolean isSeller() {
        return dataModel.getDirection() == OfferDirection.BUY;
    }

    public boolean isSellingToAnUnsignedAccount(Offer offer) {
        if (offer.getDirection() == OfferDirection.BUY &&
                PaymentMethod.hasChargebackRisk(offer.getPaymentMethod(), offer.getCurrencyCode())) {
            // considered risky when either UNSIGNED, PEER_INITIAL, or BANNED (see #5343)
            return accountAgeWitnessService.getSignState(offer) == AccountAgeWitnessService.SignState.UNSIGNED ||
                    accountAgeWitnessService.getSignState(offer) == AccountAgeWitnessService.SignState.PEER_INITIAL ||
                    accountAgeWitnessService.getSignState(offer) == AccountAgeWitnessService.SignState.BANNED;
        }
        return false;
    }

    private InputValidator.ValidationResult isXmrInputValid(String input) {
        return xmrValidator.validate(input);
    }

    public Offer getOffer() {
        return dataModel.getOffer();
    }

    public boolean isRange() {
        return dataModel.getOffer().isRange();
    }

    public String getAmountRange() {
        return amountRange;
    }

    public String getPaymentLabel() {
        return paymentLabel;
    }

    public String getPrice() {
        return price;
    }

    public String getAmountDescription() {
        return amountDescription;
    }

    String getTradeAmount() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalent(offerUtil,
                dataModel.getAmount().get(),
                xmrFormatter);
    }

    public String getSecurityDepositInfo() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalentAndPercentage(offerUtil,
                dataModel.getSecurityDeposit(),
                dataModel.getAmount().get(),
                xmrFormatter);
    }

    public String getSecurityDepositWithCode() {
        return HavenoUtils.formatXmr(dataModel.getSecurityDeposit(), true);
    }

    public String getTradeFee() {
            return OfferViewModelUtil.getTradeFeeWithFiatEquivalentAndPercentage(offerUtil,
                    dataModel.getTakerFee(),
                    dataModel.getAmount().get(),
                    xmrFormatter);
    }

    public String getTakerFeePercentage() {
        final BigInteger takerFee = dataModel.getTakerFee();
        return takerFee != null ? GUIUtil.getPercentage(takerFee, dataModel.getAmount().get()) : Res.get("shared.na");
    }

    public String getTotalToPayInfo() {
        final String totalToPay = this.totalToPay.get();
        return totalToPay;
    }

    ObservableList<PaymentAccount> getPossiblePaymentAccounts() {
        return dataModel.getPossiblePaymentAccounts();
    }

    public PaymentAccount getLastSelectedPaymentAccount() {
        return dataModel.getLastSelectedPaymentAccount();
    }

    public void resetOfferWarning() {
        offerWarning.set(null);
    }

    public Trade getTrade() {
        return trade;
    }

    public void resetErrorMessage() {
        offer.setErrorMessage(null);
    }

    public Callback<ListView<PaymentAccount>, ListCell<PaymentAccount>> getPaymentAccountListCellFactory(
            ComboBox<PaymentAccount> paymentAccountsComboBox) {
        return GUIUtil.getPaymentAccountListCellFactory(paymentAccountsComboBox, accountAgeWitnessService);
    }

    String getPercentagePriceDescription() {
        if (dataModel.isBuyOffer()) {
            return dataModel.isCryptoCurrency() ?
                    Res.get("shared.aboveInPercent") :
                    Res.get("shared.belowInPercent");
        } else {
            return dataModel.isCryptoCurrency() ?
                    Res.get("shared.belowInPercent") :
                    Res.get("shared.aboveInPercent");
        }
    }
}
