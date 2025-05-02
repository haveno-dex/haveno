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

package haveno.desktop.main.offer;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.util.MathUtils;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferRestrictions;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.validation.FiatVolumeValidator;
import haveno.core.payment.validation.SecurityDepositValidator;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.CoinUtil;
import haveno.core.util.validation.AmountValidator4Decimals;
import haveno.core.util.validation.AmountValidator8Decimals;
import haveno.core.util.validation.InputValidator;
import haveno.core.util.validation.InputValidator.ValidationResult;
import haveno.core.util.validation.MonetaryValidator;
import haveno.core.xmr.wallet.Restrictions;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.deposit.DepositView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.settings.SettingsView;
import haveno.desktop.main.settings.preferences.PreferencesView;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static javafx.beans.binding.Bindings.createStringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.Callback;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

@Slf4j
public abstract class MutableOfferViewModel<M extends MutableOfferDataModel> extends ActivatableWithDataModel<M> {
    private final XmrValidator xmrValidator;
    protected final SecurityDepositValidator securityDepositValidator;
    protected final PriceFeedService priceFeedService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final Preferences preferences;
    protected final CoinFormatter xmrFormatter;
    private final FiatVolumeValidator fiatVolumeValidator;
    private final AmountValidator4Decimals amountValidator4Decimals;
    private final AmountValidator8Decimals amountValidator8Decimals;
    protected final OfferUtil offerUtil;

    private String amountDescription;
    private String addressAsString;
    private final String paymentLabel;
    private boolean createOfferInProgress;
    public boolean createOfferCanceled;

    public final StringProperty amount = new SimpleStringProperty();
    public final StringProperty minAmount = new SimpleStringProperty();
    protected final StringProperty securityDeposit = new SimpleStringProperty();
    final StringProperty securityDepositInXMR = new SimpleStringProperty();
    final StringProperty securityDepositLabel = new SimpleStringProperty();

    // Price in the viewModel is always dependent on fiat/crypto: Fiat Fiat/BTC, for cryptos we use inverted price.
    // The domain (dataModel) uses always the same price model (otherCurrencyBTC)
    // If we would change the price representation in the domain we would not be backward compatible
    public final StringProperty price = new SimpleStringProperty();
    public final StringProperty triggerPrice = new SimpleStringProperty("");
    public final BooleanProperty reserveExactAmount = new SimpleBooleanProperty(true);
    final StringProperty tradeFee = new SimpleStringProperty();
    final StringProperty tradeFeeInXmrWithFiat = new SimpleStringProperty();
    final StringProperty tradeFeeCurrencyCode = new SimpleStringProperty();
    final StringProperty tradeFeeDescription = new SimpleStringProperty();
    final BooleanProperty isTradeFeeVisible = new SimpleBooleanProperty(false);

    // Positive % value means always a better price form the maker's perspective:
    // Buyer (with fiat): lower price as market
    // Buyer (with crypto): higher (display) price as market (display price is inverted)
    public final StringProperty marketPriceMargin = new SimpleStringProperty();
    public final StringProperty volume = new SimpleStringProperty();
    final StringProperty volumeDescriptionLabel = new SimpleStringProperty();
    final StringProperty volumePromptLabel = new SimpleStringProperty();
    final StringProperty tradeAmount = new SimpleStringProperty();
    final StringProperty totalToPay = new SimpleStringProperty();
    final StringProperty errorMessage = new SimpleStringProperty();
    final StringProperty tradeCurrencyCode = new SimpleStringProperty();
    final StringProperty waitingForFundsText = new SimpleStringProperty("");
    final StringProperty triggerPriceDescription = new SimpleStringProperty("");
    final StringProperty percentagePriceDescription = new SimpleStringProperty("");
    final StringProperty extraInfo = new SimpleStringProperty("");

    final BooleanProperty isPlaceOfferButtonDisabled = new SimpleBooleanProperty(true);
    final BooleanProperty cancelButtonDisabled = new SimpleBooleanProperty();
    public final BooleanProperty isNextButtonDisabled = new SimpleBooleanProperty(true);
    final BooleanProperty placeOfferCompleted = new SimpleBooleanProperty();
    final BooleanProperty showPayFundsScreenDisplayed = new SimpleBooleanProperty();
    private final BooleanProperty showTransactionPublishedScreen = new SimpleBooleanProperty();
    final BooleanProperty isWaitingForFunds = new SimpleBooleanProperty();
    final BooleanProperty isMinSecurityDeposit = new SimpleBooleanProperty();

    final ObjectProperty<InputValidator.ValidationResult> amountValidationResult = new SimpleObjectProperty<>();
    final ObjectProperty<InputValidator.ValidationResult> minAmountValidationResult = new SimpleObjectProperty<>();
    final ObjectProperty<InputValidator.ValidationResult> priceValidationResult = new SimpleObjectProperty<>();
    final ObjectProperty<InputValidator.ValidationResult> triggerPriceValidationResult = new SimpleObjectProperty<>(new InputValidator.ValidationResult(true));
    final ObjectProperty<InputValidator.ValidationResult> volumeValidationResult = new SimpleObjectProperty<>();
    final ObjectProperty<InputValidator.ValidationResult> securityDepositValidationResult = new SimpleObjectProperty<>();
    final ObjectProperty<InputValidator.ValidationResult> extraInfoValidationResult = new SimpleObjectProperty<>();

    private ChangeListener<String> amountStringListener;
    private ChangeListener<String> minAmountStringListener;
    private ChangeListener<String> priceStringListener, marketPriceMarginStringListener;
    private ChangeListener<String> volumeStringListener;
    private ChangeListener<String> securityDepositStringListener;
    private ChangeListener<String> extraInfoStringListener;

    private ChangeListener<BigInteger> amountListener;
    private ChangeListener<BigInteger> minAmountListener;
    private ChangeListener<Price> priceListener;
    private ChangeListener<Volume> volumeListener;
    private ChangeListener<Number> securityDepositAsDoubleListener;
    private ChangeListener<Boolean> buyerAsTakerWithoutDepositListener;

    private ChangeListener<Boolean> isWalletFundedListener;
    private ChangeListener<String> errorMessageListener;
    protected Offer offer;
    private boolean inputIsMarketBasedPrice;
    private ChangeListener<Boolean> useMarketBasedPriceListener;
    private boolean ignorePriceStringListener, ignoreVolumeStringListener, ignoreAmountStringListener, ignoreSecurityDepositStringListener;
    private MarketPrice marketPrice;
    final IntegerProperty marketPriceAvailableProperty = new SimpleIntegerProperty(-1);
    private ChangeListener<Number> currenciesUpdateListener;
    protected boolean syncMinAmountWithAmount = true;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MutableOfferViewModel(M dataModel,
                                 FiatVolumeValidator fiatVolumeValidator,
                                 AmountValidator4Decimals amountValidator4Decimals,
                                 AmountValidator8Decimals amountValidator8Decimals,
                                 XmrValidator xmrValidator,
                                 SecurityDepositValidator securityDepositValidator,
                                 PriceFeedService priceFeedService,
                                 AccountAgeWitnessService accountAgeWitnessService,
                                 Navigation navigation,
                                 Preferences preferences,
                                 @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter xmrFormatter,
                                 OfferUtil offerUtil) {
        super(dataModel);

        this.fiatVolumeValidator = fiatVolumeValidator;
        this.amountValidator4Decimals = amountValidator4Decimals;
        this.amountValidator8Decimals = amountValidator8Decimals;
        this.xmrValidator = xmrValidator;
        this.securityDepositValidator = securityDepositValidator;
        this.priceFeedService = priceFeedService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.preferences = preferences;
        this.xmrFormatter = xmrFormatter;
        this.offerUtil = offerUtil;

        paymentLabel = Res.get("createOffer.fundsBox.paymentLabel", dataModel.shortOfferId);

        createListeners();
    }

    @Override
    public void activate() {
        if (DevEnv.isDevMode()) {
            UserThread.runAfter(() -> {
                amount.set("0.001");
                price.set("210000");
                minAmount.set(amount.get());
                onFocusOutPriceAsPercentageTextField(true, false);
                applyMakerFee();
                setAmountToModel();
                setMinAmountToModel();
                setPriceToModel();
                dataModel.calculateVolume();
                dataModel.calculateTotalToPay();
                updateButtonDisableState();
                updateSpinnerInfo();
                setExtraInfoToModel();
            }, 100, TimeUnit.MILLISECONDS);
        }

        addBindings();
        addListeners();

        updateButtonDisableState();

        updateMarketPriceAvailable();
    }

    @Override
    protected void deactivate() {
        removeBindings();
        removeListeners();
    }

    private void addBindings() {
        if (dataModel.getDirection() == OfferDirection.BUY) {
            volumeDescriptionLabel.bind(createStringBinding(
                    () -> Res.get(CurrencyUtil.isTraditionalCurrency(dataModel.getTradeCurrencyCode().get()) ?
                            "createOffer.amountPriceBox.buy.volumeDescription" :
                            "createOffer.amountPriceBox.buy.volumeDescriptionCrypto", dataModel.getTradeCurrencyCode().get()),
                    dataModel.getTradeCurrencyCode()));
        } else {
            volumeDescriptionLabel.bind(createStringBinding(
                    () -> Res.get(CurrencyUtil.isTraditionalCurrency(dataModel.getTradeCurrencyCode().get()) ?
                            "createOffer.amountPriceBox.sell.volumeDescription" :
                            "createOffer.amountPriceBox.sell.volumeDescriptionCrypto", dataModel.getTradeCurrencyCode().get()),
                    dataModel.getTradeCurrencyCode()));
        }
        volumePromptLabel.bind(createStringBinding(
                () -> Res.get("createOffer.volume.prompt", CurrencyUtil.getCurrencyCodeBase(dataModel.getTradeCurrencyCode().get())),
                dataModel.getTradeCurrencyCode()));

        totalToPay.bind(createStringBinding(() -> HavenoUtils.formatXmr(dataModel.totalToPayAsProperty().get(), true),
                dataModel.totalToPayAsProperty()));

        tradeAmount.bind(createStringBinding(() -> HavenoUtils.formatXmr(dataModel.getAmount().get(), true),
                dataModel.getAmount()));

        tradeCurrencyCode.bind(dataModel.getTradeCurrencyCode());

        triggerPriceDescription.bind(createStringBinding(this::getTriggerPriceDescriptionLabel,
                dataModel.getTradeCurrencyCode()));
        percentagePriceDescription.bind(createStringBinding(this::getPercentagePriceDescription,
                dataModel.getTradeCurrencyCode()));
    }

    private void removeBindings() {
        totalToPay.unbind();
        tradeAmount.unbind();
        tradeCurrencyCode.unbind();
        volumeDescriptionLabel.unbind();
        volumePromptLabel.unbind();
        triggerPriceDescription.unbind();
        percentagePriceDescription.unbind();
    }

    private void createListeners() {
        amountStringListener = (ov, oldValue, newValue) -> {
            if (!ignoreAmountStringListener) {
                if (isXmrInputValid(newValue).isValid) {
                    setAmountToModel();
                    dataModel.calculateVolume();
                    dataModel.calculateTotalToPay();
                }
                updateSecurityDeposit();
                updateButtonDisableState();
            }
        };
        minAmountStringListener = (ov, oldValue, newValue) -> {
            if (isXmrInputValid(newValue).isValid)
                setMinAmountToModel();
            updateButtonDisableState();
        };
        priceStringListener = (ov, oldValue, newValue) -> {
            updateMarketPriceAvailable();
            final String currencyCode = dataModel.getTradeCurrencyCode().get();
            if (!ignorePriceStringListener) {
                if (isPriceInputValid(newValue).isValid) {
                    setPriceToModel();
                    dataModel.calculateVolume();
                    dataModel.calculateTotalToPay();

                    if (!inputIsMarketBasedPrice) {
                        if (marketPrice != null && marketPrice.isRecentExternalPriceAvailable()) {
                            double marketPriceAsDouble = marketPrice.getPrice();
                            try {
                                double priceAsDouble = ParsingUtils.parseNumberStringToDouble(price.get());
                                double relation = priceAsDouble / marketPriceAsDouble;
                                final OfferDirection compareDirection = CurrencyUtil.isCryptoCurrency(currencyCode) ?
                                        OfferDirection.SELL :
                                        OfferDirection.BUY;
                                double percentage = dataModel.getDirection() == compareDirection ? 1 - relation : relation - 1;
                                percentage = MathUtils.roundDouble(percentage, 4);
                                dataModel.setMarketPriceMarginPct(percentage);
                                marketPriceMargin.set(FormattingUtils.formatToPercent(percentage));
                                applyMakerFee();
                            } catch (NumberFormatException t) {
                                marketPriceMargin.set("");
                                new Popup().warning(Res.get("validation.NaN")).show();
                            }
                        } else {
                            log.debug("We don't have a market price. We use the static price instead.");
                        }
                    }
                }
            }
            updateButtonDisableState();
        };
        marketPriceMarginStringListener = (ov, oldValue, newValue) -> {
            if (inputIsMarketBasedPrice) {
                try {
                    if (!newValue.isEmpty() && !newValue.equals("-")) {
                        double percentage = ParsingUtils.parsePercentStringToDouble(newValue);
                        if (percentage >= 1 || percentage <= -1) {
                            new Popup().warning(Res.get("popup.warning.tooLargePercentageValue") + "\n" +
                                            Res.get("popup.warning.examplePercentageValue"))
                                    .show();
                        } else {
                            final String currencyCode = dataModel.getTradeCurrencyCode().get();
                            MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
                            if (marketPrice != null && marketPrice.isRecentExternalPriceAvailable()) {
                                percentage = MathUtils.roundDouble(percentage, 4);
                                double marketPriceAsDouble = marketPrice.getPrice();
                                final OfferDirection compareDirection = CurrencyUtil.isCryptoCurrency(currencyCode) ?
                                        OfferDirection.SELL :
                                        OfferDirection.BUY;
                                double factor = dataModel.getDirection() == compareDirection ?
                                        1 - percentage :
                                        1 + percentage;
                                double targetPrice = marketPriceAsDouble * factor;
                                int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                                        TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
                                // protect from triggering unwanted updates
                                ignorePriceStringListener = true;
                                price.set(FormattingUtils.formatRoundedDoubleWithPrecision(targetPrice, precision));
                                ignorePriceStringListener = false;
                                setPriceToModel();
                                dataModel.setMarketPriceMarginPct(percentage);
                                dataModel.calculateVolume();
                                dataModel.calculateTotalToPay();
                                updateButtonDisableState();
                                applyMakerFee();
                            } else {
                                marketPriceMargin.set("");
                                String id = "showNoPriceFeedAvailablePopup";
                                if (preferences.showAgain(id)) {
                                    new Popup().warning(Res.get("popup.warning.noPriceFeedAvailable"))
                                            .dontShowAgainId(id)
                                            .show();
                                }
                            }
                        }
                    }
                } catch (NumberFormatException t) {
                    log.error(t.toString());
                    t.printStackTrace();
                    new Popup().warning(Res.get("validation.NaN")).show();
                } catch (Throwable t) {
                    log.error(t.toString());
                    t.printStackTrace();
                    new Popup().warning(Res.get("validation.inputError", t.toString())).show();
                }
            }
        };
        useMarketBasedPriceListener = (observable, oldValue, newValue) -> {
            if (newValue)
                priceValidationResult.set(new InputValidator.ValidationResult(true));
        };

        volumeStringListener = (ov, oldValue, newValue) -> {
            if (!ignoreVolumeStringListener) {
                if (isVolumeInputValid(newValue).isValid) {
                    setVolumeToModel();
                    setPriceToModel();
                    dataModel.calculateAmount();
                    dataModel.calculateTotalToPay();
                }
                updateButtonDisableState();
            }
        };

        securityDepositStringListener = (ov, oldValue, newValue) -> {
            if (!ignoreSecurityDepositStringListener) {
                if (securityDepositValidator.validate(newValue).isValid) {
                    setSecurityDepositToModel();
                    dataModel.calculateTotalToPay();
                }
                updateButtonDisableState();
            }
        };

        amountListener = (ov, oldValue, newValue) -> {
            if (newValue != null) {
                amount.set(HavenoUtils.formatXmr(newValue));
                securityDepositInXMR.set(HavenoUtils.formatXmr(dataModel.getSecurityDeposit(), true));
            } else {
                amount.set("");
                securityDepositInXMR.set("");
            }

            applyMakerFee();
        };

        minAmountListener = (ov, oldValue, newValue) -> {
            if (newValue != null)
                minAmount.set(HavenoUtils.formatXmr(newValue));
            else
                minAmount.set("");
        };

        priceListener = (ov, oldValue, newValue) -> {
            ignorePriceStringListener = true;
            if (newValue != null)
                price.set(FormattingUtils.formatPrice(newValue));
            else
                price.set("");

            ignorePriceStringListener = false;
            applyMakerFee();
        };

        volumeListener = (ov, oldValue, newValue) -> {
            ignoreVolumeStringListener = true;
            if (newValue != null)
                volume.set(VolumeUtil.formatVolume(newValue));
            else
                volume.set("");

            ignoreVolumeStringListener = false;
            applyMakerFee();
        };

        securityDepositAsDoubleListener = (ov, oldValue, newValue) -> {
            if (newValue != null) {
                securityDeposit.set(FormattingUtils.formatToPercent((double) newValue));
                if (dataModel.getAmount().get() != null) {
                    securityDepositInXMR.set(HavenoUtils.formatXmr(dataModel.getSecurityDeposit(), true));
                }
                updateSecurityDeposit();
            } else {
                securityDeposit.set("");
                securityDepositInXMR.set("");
            }
        };

        buyerAsTakerWithoutDepositListener = (ov, oldValue, newValue) -> {
            if (dataModel.paymentAccount != null) xmrValidator.setMaxValue(dataModel.paymentAccount.getPaymentMethod().getMaxTradeLimit(dataModel.getTradeCurrencyCode().get()));
            xmrValidator.setMaxTradeLimit(BigInteger.valueOf(dataModel.getMaxTradeLimit()));
            if (amount.get() != null) amountValidationResult.set(isXmrInputValid(amount.get()));
            updateSecurityDeposit();
            setSecurityDepositToModel();
            onFocusOutSecurityDepositTextField(true, false); // refresh security deposit field
            applyMakerFee();
            dataModel.calculateTotalToPay();
            updateButtonDisableState();
        };

        extraInfoStringListener = (ov, oldValue, newValue) -> {
            if (newValue != null) {
                extraInfo.set(newValue);
                UserThread.execute(() -> onExtraInfoTextAreaChanged());
            }
        };

        isWalletFundedListener = (ov, oldValue, newValue) -> updateButtonDisableState();
       /* feeFromFundingTxListener = (ov, oldValue, newValue) -> {
            updateButtonDisableState();
        };*/

        currenciesUpdateListener = (observable, oldValue, newValue) -> {
            updateMarketPriceAvailable();
            updateButtonDisableState();
        };
    }

    private void applyMakerFee() {
        tradeFeeCurrencyCode.set(Res.getBaseCurrencyCode());
        tradeFeeDescription.set(Res.get("createOffer.tradeFee.descriptionXMROnly"));

        BigInteger makerFee = dataModel.getMaxMakerFee();
        if (makerFee == null) {
            return;
        }

        isTradeFeeVisible.setValue(true);
        tradeFee.set(HavenoUtils.formatXmr(makerFee));
        tradeFeeInXmrWithFiat.set(OfferViewModelUtil.getTradeFeeWithFiatEquivalent(offerUtil,
                dataModel.getMaxMakerFee(),
                xmrFormatter));
    }


    private void updateMarketPriceAvailable() {
        marketPrice = priceFeedService.getMarketPrice(dataModel.getTradeCurrencyCode().get());
        marketPriceAvailableProperty.set(marketPrice == null || !marketPrice.isExternallyProvidedPrice() ? 0 : 1);
        dataModel.setMarketPriceAvailable(marketPrice != null && marketPrice.isExternallyProvidedPrice());
    }

    private void addListeners() {
        // Bidirectional bindings are used for all input fields: amount, price, volume and minAmount
        // We do volume/amount calculation during input, so user has immediate feedback
        amount.addListener(amountStringListener);
        minAmount.addListener(minAmountStringListener);
        price.addListener(priceStringListener);
        marketPriceMargin.addListener(marketPriceMarginStringListener);
        dataModel.getUseMarketBasedPrice().addListener(useMarketBasedPriceListener);
        volume.addListener(volumeStringListener);
        securityDeposit.addListener(securityDepositStringListener);
        extraInfo.addListener(extraInfoStringListener);

        // Binding with Bindings.createObjectBinding does not work because of bi-directional binding
        dataModel.getAmount().addListener(amountListener);
        dataModel.getMinAmount().addListener(minAmountListener);
        dataModel.getPrice().addListener(priceListener);
        dataModel.getVolume().addListener(volumeListener);
        dataModel.getSecurityDepositPct().addListener(securityDepositAsDoubleListener);
        dataModel.getBuyerAsTakerWithoutDeposit().addListener(buyerAsTakerWithoutDepositListener);
        dataModel.getExtraInfo().addListener(extraInfoStringListener);

        // dataModel.feeFromFundingTxProperty.addListener(feeFromFundingTxListener);
        dataModel.getIsXmrWalletFunded().addListener(isWalletFundedListener);

        priceFeedService.updateCounterProperty().addListener(currenciesUpdateListener);
    }

    private void removeListeners() {
        amount.removeListener(amountStringListener);
        minAmount.removeListener(minAmountStringListener);
        price.removeListener(priceStringListener);
        marketPriceMargin.removeListener(marketPriceMarginStringListener);
        dataModel.getUseMarketBasedPrice().removeListener(useMarketBasedPriceListener);
        volume.removeListener(volumeStringListener);
        securityDeposit.removeListener(securityDepositStringListener);
        extraInfo.removeListener(extraInfoStringListener);

        // Binding with Bindings.createObjectBinding does not work because of bi-directional binding
        dataModel.getAmount().removeListener(amountListener);
        dataModel.getMinAmount().removeListener(minAmountListener);
        dataModel.getPrice().removeListener(priceListener);
        dataModel.getVolume().removeListener(volumeListener);
        dataModel.getSecurityDepositPct().removeListener(securityDepositAsDoubleListener);
        dataModel.getBuyerAsTakerWithoutDeposit().removeListener(buyerAsTakerWithoutDepositListener);
        dataModel.getExtraInfo().removeListener(extraInfoStringListener);

        //dataModel.feeFromFundingTxProperty.removeListener(feeFromFundingTxListener);
        dataModel.getIsXmrWalletFunded().removeListener(isWalletFundedListener);

        if (offer != null && errorMessageListener != null)
            offer.getErrorMessageProperty().removeListener(errorMessageListener);

        priceFeedService.updateCounterProperty().removeListener(currenciesUpdateListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    boolean initWithData(OfferDirection direction, TradeCurrency tradeCurrency, boolean initAddressEntry) {
        boolean result = dataModel.initWithData(direction, tradeCurrency, initAddressEntry);
        if (dataModel.getAddressEntry() != null) {
            addressAsString = dataModel.getAddressEntry().getAddressString();
        }
        if (dataModel.paymentAccount != null)
            xmrValidator.setMaxValue(dataModel.paymentAccount.getPaymentMethod().getMaxTradeLimit(dataModel.getTradeCurrencyCode().get()));
        xmrValidator.setMaxTradeLimit(BigInteger.valueOf(dataModel.getMaxTradeLimit()));
        xmrValidator.setMinValue(Restrictions.getMinTradeAmount());

        final boolean isBuy = dataModel.getDirection() == OfferDirection.BUY;

        boolean isTraditionalCurrency = CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode());

        if (isTraditionalCurrency) {
            amountDescription = Res.get("createOffer.amountPriceBox.amountDescription",
                    isBuy ? Res.get("shared.buy") : Res.get("shared.sell"));
        } else {
            amountDescription = Res.get(isBuy ? "createOffer.amountPriceBox.sell.amountDescriptionCrypto" :
                    "createOffer.amountPriceBox.buy.amountDescriptionCrypto");
        }

        securityDepositValidator.setPaymentAccount(dataModel.paymentAccount);
        validateAndSetSecurityDepositToModel();
        securityDeposit.set(FormattingUtils.formatToPercent(dataModel.getSecurityDepositPct().get()));
        securityDepositLabel.set(getSecurityDepositLabel());

        applyMakerFee();
        return result;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onPlaceOffer(Offer offer, Runnable resultHandler) {
        ThreadUtils.execute(() -> {
            errorMessage.set(null);
            createOfferInProgress = true;
            createOfferCanceled = false;

            dataModel.onPlaceOffer(offer, transaction -> {
                createOfferInProgress = false;
                resultHandler.run();
                if (!createOfferCanceled) placeOfferCompleted.set(true);
                errorMessage.set(null);
            }, errMessage -> {
                createOfferInProgress = false;
                if (offer.getState() == Offer.State.OFFER_FEE_RESERVED) errorMessage.set(errMessage + Res.get("createOffer.errorInfo"));
                else errorMessage.set(errMessage);

                UserThread.execute(() -> {
                    updateButtonDisableState();
                    updateSpinnerInfo();
                    resultHandler.run();
                });
            });

            UserThread.execute(() -> {
                updateButtonDisableState();
                updateSpinnerInfo();
            });
        }, getClass().getSimpleName());
    }

    public void onCancelOffer(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.info("Canceling posting offer {}", offer.getId());
        createOfferCanceled = true;
        OpenOfferManager openOfferManager = HavenoUtils.openOfferManager;
        Optional<OpenOffer> openOffer = openOfferManager.getOpenOffer(offer.getId());
        if (openOffer.isPresent()) {
            openOfferManager.cancelOpenOffer(openOffer.get(), () -> {
                UserThread.execute(() -> {
                    updateButtonDisableState();
                    updateSpinnerInfo();
                });
                if (resultHandler != null) resultHandler.handleResult();
            }, errorMessage -> {
                UserThread.execute(() -> {
                    updateButtonDisableState();
                    updateSpinnerInfo();
                    if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage(errorMessage);
                });
            });
        } else {
            if (resultHandler != null) resultHandler.handleResult();
            return;
        }
    }

    public void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        dataModel.onPaymentAccountSelected(paymentAccount);
        if (amount.get() != null)
            amountValidationResult.set(isXmrInputValid(amount.get()));

        xmrValidator.setMaxValue(dataModel.paymentAccount.getPaymentMethod().getMaxTradeLimit(dataModel.getTradeCurrencyCode().get()));
        xmrValidator.setMaxTradeLimit(BigInteger.valueOf(dataModel.getMaxTradeLimit()));

        securityDepositValidator.setPaymentAccount(paymentAccount);
    }

    public void onCurrencySelected(TradeCurrency tradeCurrency) {
        dataModel.onCurrencySelected(tradeCurrency);

        marketPrice = priceFeedService.getMarketPrice(dataModel.getTradeCurrencyCode().get());
        marketPriceAvailableProperty.set(marketPrice == null || !marketPrice.isExternallyProvidedPrice() ? 0 : 1);
        updateButtonDisableState();
    }

    void onShowPayFundsScreen(Runnable actionHandler) {
        actionHandler.run();
        showPayFundsScreenDisplayed.set(true);
        updateSpinnerInfo();
    }

    void fundFromSavingsWallet() {
        dataModel.fundFromSavingsWallet();
        if (dataModel.getIsXmrWalletFunded().get()) {
            updateButtonDisableState();
        } else {
            new Popup().warning(Res.get("shared.notEnoughFunds",
                            HavenoUtils.formatXmr(dataModel.totalToPayAsProperty().get(), true),
                            HavenoUtils.formatXmr(dataModel.getTotalBalance(), true)))
                    .actionButtonTextWithGoTo("funds.tab.deposit")
                    .onAction(() -> navigation.navigateTo(MainView.class, FundsView.class, DepositView.class))
                    .show();
        }

    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Handle focus
    ///////////////////////////////////////////////////////////////////////////////////////////

    // On focus out we do validation and apply the data to the model
    void onFocusOutAmountTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            InputValidator.ValidationResult result = isXmrInputValid(amount.get());
            amountValidationResult.set(result);
            if (result.isValid) {
                setAmountToModel();
                ignoreAmountStringListener = true;
                amount.set(HavenoUtils.formatXmr(dataModel.getAmount().get()));
                ignoreAmountStringListener = false;
                dataModel.calculateVolume();

                if (!dataModel.isMinAmountLessOrEqualAmount())
                    minAmount.set(amount.get());
                else
                    amountValidationResult.set(result);

                if (minAmount.get() != null)
                    minAmountValidationResult.set(isXmrInputValid(minAmount.get()));
            } else if (amount.get() != null && xmrValidator.getMaxTradeLimit() != null && xmrValidator.getMaxTradeLimit().longValueExact() == OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT.longValueExact()) {
                if (ParsingUtils.parseNumberStringToDouble(amount.get()) < HavenoUtils.atomicUnitsToXmr(Restrictions.getMinTradeAmount())) {
                    amountValidationResult.set(result);
                } else {
                    amount.set(HavenoUtils.formatXmr(xmrValidator.getMaxTradeLimit()));
                    boolean isBuy = dataModel.getDirection() == OfferDirection.BUY;
                    boolean isSellerWithinReleaseWindow = !isBuy && HavenoUtils.isReleasedWithinDays(HavenoUtils.RELEASE_LIMIT_DAYS);
                    if (isSellerWithinReleaseWindow) {

                        // format release date plus days
                        Date releaseDate = HavenoUtils.getReleaseDate();
                        Calendar c = Calendar.getInstance();
                        c.setTime(releaseDate);
                        c.add(Calendar.DATE, HavenoUtils.RELEASE_LIMIT_DAYS);
                        Date releaseDatePlusDays = c.getTime();
                        SimpleDateFormat formatter = new SimpleDateFormat("MMMM d, yyyy");
                        String releaseDatePlusDaysAsString = formatter.format(releaseDatePlusDays);

                        // popup temporary restriction
                        new Popup().information(Res.get("popup.warning.tradeLimitDueAccountAgeRestriction.seller.releaseLimit",
                                HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true),
                                releaseDatePlusDaysAsString,
                                Res.get("offerbook.warning.newVersionAnnouncement")))
                        .width(900)
                        .show();
                    } else {
                        new Popup().information(Res.get(isBuy ? "popup.warning.tradeLimitDueAccountAgeRestriction.buyer" : "popup.warning.tradeLimitDueAccountAgeRestriction.seller",
                                HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true),
                                Res.get("offerbook.warning.newVersionAnnouncement")))
                        .width(900)
                        .show();
                    }
                }
            }

            // trigger recalculation of the volume
            UserThread.execute(() -> {
                onFocusOutVolumeTextField(true, false);
                onFocusOutMinAmountTextField(true, false);
            });

            if (marketPriceMargin.get() == null && amount.get() != null && volume.get() != null) {
                updateMarketPriceToManual();
            }
        }
    }

    public void onFocusOutMinAmountTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            InputValidator.ValidationResult result = isXmrInputValid(minAmount.get());
            minAmountValidationResult.set(result);
            if (result.isValid) {
                BigInteger minAmount = dataModel.getMinAmount().get();
                syncMinAmountWithAmount = minAmount != null && minAmount.equals(dataModel.getAmount().get());
                setMinAmountToModel();

                dataModel.calculateMinVolume();

                if (dataModel.getMinVolume().get() != null) {
                    InputValidator.ValidationResult minVolumeResult = isVolumeInputValid(
                            VolumeUtil.formatVolume(dataModel.getMinVolume().get()));

                    volumeValidationResult.set(minVolumeResult);

                    updateButtonDisableState();
                }

                this.minAmount.set(HavenoUtils.formatXmr(minAmount));

                if (!dataModel.isMinAmountLessOrEqualAmount()) {
                    this.amount.set(this.minAmount.get());
                } else {
                    minAmountValidationResult.set(result);
                    if (this.amount.get() != null)
                        amountValidationResult.set(isXmrInputValid(this.amount.get()));
                }
            } else {
                syncMinAmountWithAmount = true;
            }

            // trigger recalculation of the security deposit
            UserThread.execute(() -> {
                onFocusOutSecurityDepositTextField(true, false);
            });
        }
    }

    public void onFocusOutExtraInfoTextArea(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            onExtraInfoTextAreaChanged();
        }
    }

    public void onExtraInfoTextAreaChanged() {
        extraInfoValidationResult.set(getExtraInfoValidationResult());
        updateButtonDisableState();
        if (extraInfoValidationResult.get().isValid) {
            setExtraInfoToModel();
        }
    }

    void onFocusOutTriggerPriceTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            onTriggerPriceTextFieldChanged();
        }
    }

    public void onTriggerPriceTextFieldChanged() {
        String triggerPriceAsString = triggerPrice.get();

        // Error field does not update if there was an error and then another different error
        // if not reset here. Not clear why...
        triggerPriceValidationResult.set(new InputValidator.ValidationResult(true));

        String currencyCode = dataModel.getTradeCurrencyCode().get();
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);

        InputValidator.ValidationResult result = PriceUtil.isTriggerPriceValid(triggerPriceAsString,
                marketPrice,
                dataModel.isSellOffer(),
                dataModel.getCurrencyCode()
        );
        triggerPriceValidationResult.set(result);
        updateButtonDisableState();
        if (result.isValid) {
            // In case of 0 or empty string we set the string to empty string and data value to 0
            long triggerPriceAsLong = PriceUtil.getMarketPriceAsLong(triggerPriceAsString, dataModel.getCurrencyCode());
            dataModel.setTriggerPrice(triggerPriceAsLong);
            if (dataModel.getTriggerPrice() == 0) {
                triggerPrice.set("");
            } else {
                triggerPrice.set(PriceUtil.formatMarketPrice(dataModel.getTriggerPrice(), dataModel.getCurrencyCode()));
            }
        }
    }

    public void onReserveExactAmountCheckboxChanged() {
        dataModel.setReserveExactAmount(reserveExactAmount.get());
    }

    void onFixPriceToggleChange(boolean fixedPriceSelected) {
        inputIsMarketBasedPrice = !fixedPriceSelected;
        updateButtonDisableState();
        if (!fixedPriceSelected) {
            onTriggerPriceTextFieldChanged();
        }
    }

    void onFocusOutPriceTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            InputValidator.ValidationResult result = isPriceInputValid(price.get());
            priceValidationResult.set(result);
            if (result.isValid) {
                setPriceToModel();
                ignorePriceStringListener = true;
                if (dataModel.getPrice().get() != null)
                    price.set(FormattingUtils.formatPrice(dataModel.getPrice().get()));
                ignorePriceStringListener = false;
                dataModel.calculateVolume();
                dataModel.calculateAmount();
                applyMakerFee();
            }

            // We want to trigger a recalculation of the volume and minAmount
            UserThread.execute(() -> {
                onFocusOutVolumeTextField(true, false);
                triggerFocusOutOnAmountFields();
            });
        }
    }

    public void triggerFocusOutOnAmountFields() {
        onFocusOutAmountTextField(true, false);
        onFocusOutMinAmountTextField(true, false);
    }

    public void onFocusOutPriceAsPercentageTextField(boolean oldValue, boolean newValue) {
        inputIsMarketBasedPrice = !oldValue && newValue;
        if (oldValue && !newValue) {
            if (marketPriceMargin.get() == null) {
                // field wasn't set manually
                inputIsMarketBasedPrice = true;
            }
            marketPriceMargin.set(FormattingUtils.formatRoundedDoubleWithPrecision(dataModel.getMarketPriceMarginPct() * 100, 2));
        }

        // We want to trigger a recalculation of the volume, as well as update trigger price validation
        UserThread.execute(() -> {
            onFocusOutVolumeTextField(true, false);
            onTriggerPriceTextFieldChanged();
        });
    }

    void onFocusOutVolumeTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue) {
            InputValidator.ValidationResult result = isVolumeInputValid(volume.get());
            volumeValidationResult.set(result);
            if (result.isValid) {
                setVolumeToModel();
                ignoreVolumeStringListener = true;

                Volume volume = dataModel.getVolume().get();
                if (volume != null) {
                    volume = VolumeUtil.getAdjustedVolume(volume, dataModel.getPaymentAccount().getPaymentMethod().getId());
                    this.volume.set(VolumeUtil.formatVolume(volume));
                }

                ignoreVolumeStringListener = false;

                dataModel.calculateAmount();

                if (!dataModel.isMinAmountLessOrEqualAmount()) {
                    minAmount.set(amount.getValue());
                } else {
                    if (amount.get() != null)
                        amountValidationResult.set(isXmrInputValid(amount.get()));

                    // We only check minAmountValidationResult if amountValidationResult is valid, otherwise we would get
                    // triggered a close of the popup when the minAmountValidationResult is applied
                    if (amountValidationResult.getValue() != null && amountValidationResult.getValue().isValid && minAmount.get() != null)
                        minAmountValidationResult.set(isXmrInputValid(minAmount.get()));
                }
            }

            if (marketPriceMargin.get() == null && amount.get() != null && volume.get() != null) {
                updateMarketPriceToManual();
            }

            // trigger recalculation of security deposit
            UserThread.execute(() -> {
                onFocusOutSecurityDepositTextField(true, false);
            });
        }
    }

    void onFocusOutSecurityDepositTextField(boolean oldValue, boolean newValue) {
        if (oldValue && !newValue && !isMinSecurityDeposit.get()) {
            InputValidator.ValidationResult result = securityDepositValidator.validate(securityDeposit.get());
            securityDepositValidationResult.set(result);
            if (result.isValid) {
                double defaultSecurityDeposit = Restrictions.getDefaultSecurityDepositAsPercent();
                String key = "buyerSecurityDepositIsLowerAsDefault";
                double depositAsDouble = ParsingUtils.parsePercentStringToDouble(securityDeposit.get());
                if (preferences.showAgain(key) && depositAsDouble < defaultSecurityDeposit) {
                    String postfix = dataModel.isBuyOffer() ?
                            Res.get("createOffer.tooLowSecDeposit.makerIsBuyer") :
                            Res.get("createOffer.tooLowSecDeposit.makerIsSeller");
                    new Popup()
                            .warning(Res.get("createOffer.tooLowSecDeposit.warning",
                                    FormattingUtils.formatToPercentWithSymbol(defaultSecurityDeposit)) + "\n\n" + postfix)
                            .width(800)
                            .actionButtonText(Res.get("createOffer.resetToDefault"))
                            .onAction(() -> {
                                dataModel.setSecurityDepositPct(defaultSecurityDeposit);
                                ignoreSecurityDepositStringListener = true;
                                securityDeposit.set(FormattingUtils.formatToPercent(dataModel.getSecurityDepositPct().get()));
                                ignoreSecurityDepositStringListener = false;
                            })
                            .closeButtonText(Res.get("createOffer.useLowerValue"))
                            .onClose(this::applySecurityDepositOnFocusOut)
                            .dontShowAgainId(key)
                            .show();
                } else {
                    applySecurityDepositOnFocusOut();
                }
            }
        }
    }

    private void applySecurityDepositOnFocusOut() {
        setSecurityDepositToModel();
        ignoreSecurityDepositStringListener = true;
        securityDeposit.set(FormattingUtils.formatToPercent(dataModel.getSecurityDepositPct().get()));
        ignoreSecurityDepositStringListener = false;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isPriceInRange() {
        if (marketPriceMargin.get() != null && !marketPriceMargin.get().isEmpty()) {
            if (Math.abs(ParsingUtils.parsePercentStringToDouble(marketPriceMargin.get())) > preferences.getMaxPriceDistanceInPercent()) {
                displayPriceOutOfRangePopup();
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    private void displayPriceOutOfRangePopup() {
        Popup popup = new Popup();
        popup.warning(Res.get("createOffer.priceOutSideOfDeviation",
                        FormattingUtils.formatToPercentWithSymbol(preferences.getMaxPriceDistanceInPercent())))
                .actionButtonText(Res.get("createOffer.changePrice"))
                .onAction(popup::hide)
                .closeButtonTextWithGoTo("settings.tab.preferences")
                .onClose(() -> navigation.navigateTo(MainView.class, SettingsView.class, PreferencesView.class))
                .show();
    }

    CoinFormatter getXmrFormatter() {
        return xmrFormatter;
    }

    public boolean isShownAsBuyOffer() {
        return OfferViewUtil.isShownAsBuyOffer(dataModel.getDirection(), dataModel.getTradeCurrency());
    }

    public boolean isSellOffer() {
        return dataModel.getDirection() == OfferDirection.SELL;
    }

    public TradeCurrency getTradeCurrency() {
        return dataModel.getTradeCurrency();
    }

    public String getTradeAmount() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalent(offerUtil,
                dataModel.getAmount().get(),
                xmrFormatter);
    }

    public String getSecurityDepositLabel() {
        return dataModel.buyerAsTakerWithoutDeposit.get() && dataModel.isSellOffer() ? Res.get("createOffer.myDeposit") :
                dataModel.isMinSecurityDeposit() ? Res.get("createOffer.minSecurityDepositUsed") :
                Preferences.USE_SYMMETRIC_SECURITY_DEPOSIT ? Res.get("createOffer.setDepositForBothTraders") :
                dataModel.isBuyOffer() ? Res.get("createOffer.setDepositAsBuyer") : Res.get("createOffer.setDeposit");
    }

    public String getSecurityDepositPopOverLabel(String depositInXMR) {
        return dataModel.buyerAsTakerWithoutDeposit.get() && dataModel.isSellOffer() ? Res.get("createOffer.myDepositInfo", depositInXMR) :
                dataModel.isBuyOffer() ? Res.get("createOffer.securityDepositInfoAsBuyer", depositInXMR) :
                Res.get("createOffer.securityDepositInfo", depositInXMR);
    }

    public String getSecurityDepositInfo() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalentAndPercentage(offerUtil,
                dataModel.getSecurityDeposit(),
                dataModel.getAmount().get(),
                xmrFormatter
        );
    }

    public String getSecurityDepositWithCode() {
        return HavenoUtils.formatXmr(dataModel.getSecurityDeposit(), true);
    }


    public String getTradeFee() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalentAndPercentage(offerUtil,
                dataModel.getMaxMakerFee(),
                dataModel.getAmount().get(),
                xmrFormatter);
    }

    public String getMakerFeePercentage() {
        final BigInteger makerFee = dataModel.getMaxMakerFee();
        return GUIUtil.getPercentage(makerFee, dataModel.getAmount().get());
    }

    public String getTotalToPayInfo() {
        return OfferViewModelUtil.getTradeFeeWithFiatEquivalent(offerUtil,
                dataModel.totalToPay.get(),
                xmrFormatter);
    }

    public String getFundsStructure() {
        String fundsStructure;
        fundsStructure = Res.get("createOffer.fundsBox.fundsStructure",
                getSecurityDepositWithCode(), getMakerFeePercentage());
        return fundsStructure;
    }

    public PaymentAccount getPaymentAccount() {
        return dataModel.getPaymentAccount();
    }

    public String getAmountDescription() {
        return amountDescription;
    }

    public String getAddressAsString() {
        return addressAsString;
    }

    public String getPaymentLabel() {
        return paymentLabel;
    }

    public Offer createAndGetOffer() {
        offer = dataModel.createAndGetOffer();
        return offer;
    }

    public Callback<ListView<PaymentAccount>, ListCell<PaymentAccount>> getPaymentAccountListCellFactory(
            ComboBox<PaymentAccount> paymentAccountsComboBox) {
        return GUIUtil.getPaymentAccountListCellFactory(paymentAccountsComboBox, accountAgeWitnessService);
    }

    public M getDataModel() {
        return dataModel;
    }

    String getTriggerPriceDescriptionLabel() {
        String details;
        if (dataModel.isBuyOffer()) {
            details = dataModel.isCryptoCurrency() ?
                    Res.get("account.notifications.marketAlert.message.msg.below") :
                    Res.get("account.notifications.marketAlert.message.msg.above");
        } else {
            details = dataModel.isCryptoCurrency() ?
                    Res.get("account.notifications.marketAlert.message.msg.above") :
                    Res.get("account.notifications.marketAlert.message.msg.below");
        }
        return Res.get("createOffer.triggerPrice.label", details);
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


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setAmountToModel() {
        if (amount.get() != null && !amount.get().isEmpty()) {
            BigInteger amount = HavenoUtils.coinToAtomicUnits(DisplayUtils.parseToCoinWith4Decimals(this.amount.get(), xmrFormatter));

            long maxTradeLimit = dataModel.getMaxTradeLimit();
            Price price = dataModel.getPrice().get();
            if (price != null && price.isPositive()) {
                amount = CoinUtil.getRoundedAmount(amount, price, maxTradeLimit, tradeCurrencyCode.get(), dataModel.getPaymentAccount().getPaymentMethod().getId());
            }
            dataModel.setAmount(amount);
            if (syncMinAmountWithAmount ||
                    dataModel.getMinAmount().get() == null ||
                    dataModel.getMinAmount().get().equals(Coin.ZERO)) {
                minAmount.set(this.amount.get());
                setMinAmountToModel();
            }
        } else {
            dataModel.setAmount(null);
        }
    }

    private void setMinAmountToModel() {
        if (minAmount.get() != null && !minAmount.get().isEmpty()) {
            BigInteger minAmount = HavenoUtils.coinToAtomicUnits(DisplayUtils.parseToCoinWith4Decimals(this.minAmount.get(), xmrFormatter));

            Price price = dataModel.getPrice().get();
            long maxTradeLimit = dataModel.getMaxTradeLimit();
            if (price != null && price.isPositive()) {
                minAmount = CoinUtil.getRoundedAmount(minAmount, price, maxTradeLimit, tradeCurrencyCode.get(), dataModel.getPaymentAccount().getPaymentMethod().getId());
            }

            dataModel.setMinAmount(minAmount);
        } else {
            dataModel.setMinAmount(null);
        }
    }

    private void setPriceToModel() {
        if (price.get() != null && !price.get().isEmpty()) {
            try {
                dataModel.setPrice(Price.parse(dataModel.getTradeCurrencyCode().get(), this.price.get()));
            } catch (Throwable t) {
                log.debug(t.getMessage());
            }
        } else {
            dataModel.setPrice(null);
        }
    }

    private void setVolumeToModel() {
        if (volume.get() != null && !volume.get().isEmpty()) {
            try {
                dataModel.setVolume(Volume.parse(volume.get(), dataModel.getTradeCurrencyCode().get()));
            } catch (Throwable t) {
                log.debug(t.getMessage());
            }
        } else {
            dataModel.setVolume(null);
        }
    }

    private void setSecurityDepositToModel() {
        if (securityDeposit.get() != null && !securityDeposit.get().isEmpty() && !isMinSecurityDeposit.get()) {
            dataModel.setSecurityDepositPct(ParsingUtils.parsePercentStringToDouble(securityDeposit.get()));
        } else {
            dataModel.setSecurityDepositPct(Restrictions.getDefaultSecurityDepositAsPercent());
        }
    }

    private void setExtraInfoToModel() {
        if (extraInfo.get() != null && !extraInfo.get().isEmpty()) {
            dataModel.setExtraInfo(extraInfo.get());
        } else {
            dataModel.setExtraInfo(null);
        }
    }

    private void validateAndSetSecurityDepositToModel() {
        // If the security deposit in the model is not valid percent
        String value = FormattingUtils.formatToPercent(dataModel.getSecurityDepositPct().get());
        if (!securityDepositValidator.validate(value).isValid) {
            dataModel.setSecurityDepositPct(Restrictions.getDefaultSecurityDepositAsPercent());
        }
    }

    private InputValidator.ValidationResult isXmrInputValid(String input) {
        return xmrValidator.validate("" + HavenoUtils.atomicUnitsToXmr(HavenoUtils.parseXmr(input)));
    }

    private InputValidator.ValidationResult isPriceInputValid(String input) {
        return getPriceValidator().validate(input);
    }

    private InputValidator.ValidationResult isVolumeInputValid(String input) {
        return getVolumeValidator().validate(input);
    }

    // TODO: replace with PriceUtils and VolumeUtils?

    private MonetaryValidator getPriceValidator() {
        return CurrencyUtil.isPricePrecise(getTradeCurrency().getCode()) ? amountValidator8Decimals : amountValidator4Decimals;
    }

    private MonetaryValidator getVolumeValidator() {
        final String code = getTradeCurrency().getCode();
        if (CurrencyUtil.isFiatCurrency(code)) {
            return fiatVolumeValidator;
        } else if (CurrencyUtil.isVolumeRoundedToNearestUnit(code)) {
            return amountValidator4Decimals;
        } else {
            return amountValidator8Decimals;
        }
    }

    private void updateSpinnerInfo() {
        if (!showPayFundsScreenDisplayed.get() ||
                errorMessage.get() != null ||
                showTransactionPublishedScreen.get()) {
            waitingForFundsText.set("");
        } else if (dataModel.getIsXmrWalletFunded().get()) {
            waitingForFundsText.set("");
        } else {
            waitingForFundsText.set(Res.get("shared.waitingForFunds"));
        }

        isWaitingForFunds.set(!waitingForFundsText.get().isEmpty());
    }

    private void updateSecurityDeposit() {
        isMinSecurityDeposit.set(dataModel.isMinSecurityDeposit());
        securityDepositLabel.set(getSecurityDepositLabel());
        if (dataModel.isMinSecurityDeposit()) {
            securityDeposit.set(HavenoUtils.formatXmr(Restrictions.getMinSecurityDeposit()));
            securityDepositValidationResult.set(new ValidationResult(true));
        } else {
            boolean hasBuyerAsTakerWithoutDeposit = dataModel.buyerAsTakerWithoutDeposit.get() && dataModel.isSellOffer();
            securityDeposit.set(FormattingUtils.formatToPercent(hasBuyerAsTakerWithoutDeposit ?
                    Restrictions.getDefaultSecurityDepositAsPercent() : // use default percent if no deposit from buyer
                    dataModel.getSecurityDepositPct().get()));
        }
    }

    void updateButtonDisableState() {
        dataModel.calculateVolume();
        dataModel.calculateTotalToPay();

        boolean inputDataValid = isXmrInputValid(amount.get()).isValid &&
                isXmrInputValid(minAmount.get()).isValid &&
                isPriceInputValid(price.get()).isValid &&
                dataModel.getPrice().get() != null &&
                dataModel.getPrice().get().getValue() != 0 &&
                isVolumeInputValid(volume.get()).isValid &&
                isVolumeInputValid(VolumeUtil.formatVolume(dataModel.getMinVolume().get())).isValid &&
                dataModel.isMinAmountLessOrEqualAmount();

        if (dataModel.useMarketBasedPrice.get() && dataModel.isMarketPriceAvailable()) {
            inputDataValid = inputDataValid && triggerPriceValidationResult.get().isValid;
        }

        // validating the percentage deposit value only makes sense if it is actually used
        if (!dataModel.isMinSecurityDeposit()) {
            inputDataValid = inputDataValid && securityDepositValidator.validate(securityDeposit.get()).isValid;
        }

        inputDataValid = inputDataValid && getExtraInfoValidationResult().isValid;

        isNextButtonDisabled.set(!inputDataValid);
        isPlaceOfferButtonDisabled.set(createOfferInProgress || !inputDataValid || !dataModel.getIsXmrWalletFunded().get());
    }

    private ValidationResult getExtraInfoValidationResult() {
        if (extraInfo.get() != null && !extraInfo.get().isEmpty() && extraInfo.get().length() > Restrictions.MAX_EXTRA_INFO_LENGTH) {
            return new InputValidator.ValidationResult(false, Res.get("createOffer.extraInfo.invalid.tooLong", Restrictions.MAX_EXTRA_INFO_LENGTH));
        } else {
            return new InputValidator.ValidationResult(true);
        }
    }

    private void updateMarketPriceToManual() {
        final String currencyCode = dataModel.getTradeCurrencyCode().get();
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        if (marketPrice != null && marketPrice.isRecentExternalPriceAvailable()) {
            double marketPriceAsDouble = marketPrice.getPrice();
            double amountAsDouble = ParsingUtils.parseNumberStringToDouble(amount.get());
            double volumeAsDouble = ParsingUtils.parseNumberStringToDouble(volume.get());
            double manualPriceAsDouble = dataModel.calculateMarketPriceManual(marketPriceAsDouble, volumeAsDouble, amountAsDouble);

            int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                    TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
            price.set(FormattingUtils.formatRoundedDoubleWithPrecision(manualPriceAsDouble, precision));
            setPriceToModel();
            dataModel.calculateTotalToPay();
            updateButtonDisableState();
            applyMakerFee();
        } else {
            marketPriceMargin.set("");
            String id = "showNoPriceFeedAvailablePopup";
            if (preferences.showAgain(id)) {
                new Popup().warning(Res.get("popup.warning.noPriceFeedAvailable"))
                        .dontShowAgainId(id)
                        .show();
            }
        }
    }
}
