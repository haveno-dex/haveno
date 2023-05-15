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

package haveno.desktop.main.offer;

import com.google.inject.Inject;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.util.MathUtils;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.handlers.TransactionResultHandler;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.CoinUtil;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.SetChangeListener;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import javax.inject.Named;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.payment.payload.PaymentMethod.HAL_CASH_ID;
import static java.util.Comparator.comparing;

public abstract class MutableOfferDataModel extends OfferDataModel {
    private final CreateOfferService createOfferService;
    protected final OpenOfferManager openOfferManager;
    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    protected final User user;
    private final P2PService p2PService;
    protected final PriceFeedService priceFeedService;
    final String shortOfferId;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final CoinFormatter btcFormatter;
    private final Navigation navigation;
    private final String offerId;
    private final XmrBalanceListener xmrBalanceListener;
    private final SetChangeListener<PaymentAccount> paymentAccountsChangeListener;

    protected OfferDirection direction;
    protected TradeCurrency tradeCurrency;
    protected final StringProperty tradeCurrencyCode = new SimpleStringProperty();
    protected final BooleanProperty useMarketBasedPrice = new SimpleBooleanProperty();
    protected final ObjectProperty<BigInteger> amount = new SimpleObjectProperty<>();
    protected final ObjectProperty<BigInteger> minAmount = new SimpleObjectProperty<>();
    protected final ObjectProperty<Price> price = new SimpleObjectProperty<>();
    protected final ObjectProperty<Volume> volume = new SimpleObjectProperty<>();
    protected final ObjectProperty<Volume> minVolume = new SimpleObjectProperty<>();

    // Percentage value of buyer security deposit. E.g. 0.01 means 1% of trade amount
    protected final DoubleProperty buyerSecurityDepositPct = new SimpleDoubleProperty();

    protected final ObservableList<PaymentAccount> paymentAccounts = FXCollections.observableArrayList();

    protected PaymentAccount paymentAccount;
    boolean isTabSelected;
    protected double marketPriceMargin = 0;
    @Getter
    private boolean marketPriceAvailable;
    protected boolean allowAmountUpdate = true;
    private final TradeStatisticsManager tradeStatisticsManager;

    private final Predicate<ObjectProperty<BigInteger>> isNonZeroAmount = (c) -> c.get() != null && c.get().compareTo(BigInteger.valueOf(0)) != 0;
    private final Predicate<ObjectProperty<Price>> isNonZeroPrice = (p) -> p.get() != null && !p.get().isZero();
    private final Predicate<ObjectProperty<Volume>> isNonZeroVolume = (v) -> v.get() != null && !v.get().isZero();
    @Getter
    protected long triggerPrice;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MutableOfferDataModel(CreateOfferService createOfferService,
                                 OpenOfferManager openOfferManager,
                                 OfferUtil offerUtil,
                                 XmrWalletService xmrWalletService,
                                 Preferences preferences,
                                 User user,
                                 P2PService p2PService,
                                 PriceFeedService priceFeedService,
                                 AccountAgeWitnessService accountAgeWitnessService,
                                 @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                 TradeStatisticsManager tradeStatisticsManager,
                                 Navigation navigation) {
        super(xmrWalletService, offerUtil);

        this.xmrWalletService = xmrWalletService;
        this.createOfferService = createOfferService;
        this.openOfferManager = openOfferManager;
        this.preferences = preferences;
        this.user = user;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.btcFormatter = btcFormatter;
        this.navigation = navigation;
        this.tradeStatisticsManager = tradeStatisticsManager;

        offerId = OfferUtil.getRandomOfferId();
        shortOfferId = Utilities.getShortId(offerId);
        addressEntry = xmrWalletService.getOrCreateAddressEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);

        useMarketBasedPrice.set(preferences.isUsePercentageBasedPrice());
        buyerSecurityDepositPct.set(Restrictions.getMinBuyerSecurityDepositAsPercent());

        xmrBalanceListener = new XmrBalanceListener(getAddressEntry().getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateBalance();
            }
        };

        paymentAccountsChangeListener = change -> fillPaymentAccounts();
    }

    @Override
    public void activate() {
        addListeners();

        if (isTabSelected)
            priceFeedService.setCurrencyCode(tradeCurrencyCode.get());

        updateBalance();
    }

    @Override
    protected void deactivate() {
        removeListeners();
    }

    private void addListeners() {
        xmrWalletService.addBalanceListener(xmrBalanceListener);
        user.getPaymentAccountsAsObservable().addListener(paymentAccountsChangeListener);
    }

    private void removeListeners() {
        xmrWalletService.removeBalanceListener(xmrBalanceListener);
        user.getPaymentAccountsAsObservable().removeListener(paymentAccountsChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before activate()
    public boolean initWithData(OfferDirection direction, TradeCurrency tradeCurrency) {
        this.direction = direction;
        this.tradeCurrency = tradeCurrency;

        fillPaymentAccounts();

        PaymentAccount account;

        PaymentAccount lastSelectedPaymentAccount = getPreselectedPaymentAccount();
        if (lastSelectedPaymentAccount != null &&
                lastSelectedPaymentAccount.getTradeCurrencies().contains(tradeCurrency) &&
                user.getPaymentAccounts() != null &&
                user.getPaymentAccounts().stream().anyMatch(paymentAccount -> paymentAccount.getId().equals(lastSelectedPaymentAccount.getId()))) {
            account = lastSelectedPaymentAccount;
        } else {
            account = user.findFirstPaymentAccountWithCurrency(tradeCurrency);
        }

        if (account != null) {
            this.paymentAccount = account;
        } else {
            Optional<PaymentAccount> paymentAccountOptional = getAnyPaymentAccount();
            if (paymentAccountOptional.isPresent()) {
                this.paymentAccount = paymentAccountOptional.get();

            } else {
                log.warn("PaymentAccount not available. Should never get called as in offer view you should not be able to open a create offer view");
                return false;
            }
        }

        setTradeCurrencyFromPaymentAccount(paymentAccount);
        tradeCurrencyCode.set(this.tradeCurrency.getCode());

        priceFeedService.setCurrencyCode(tradeCurrencyCode.get());

        calculateVolume();
        calculateTotalToPay();
        updateBalance();
        setSuggestedSecurityDeposit(getPaymentAccount());

        return true;
    }

    @NotNull
    private Optional<PaymentAccount> getAnyPaymentAccount() {
        if (CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode())) {
            return paymentAccounts.stream().filter(paymentAccount1 -> !paymentAccount1.getPaymentMethod().isCrypto()).findAny();
        } else {
            return paymentAccounts.stream().filter(paymentAccount1 -> paymentAccount1.getPaymentMethod().isCrypto() &&
                    paymentAccount1.getTradeCurrency().isPresent() &&
                    !Objects.equals(paymentAccount1.getTradeCurrency().get().getCode(), GUIUtil.TOP_CRYPTO.getCode())).findAny();
        }
    }

    protected PaymentAccount getPreselectedPaymentAccount() {
        return preferences.getSelectedPaymentAccountForCreateOffer();
    }

    void onTabSelected(boolean isSelected) {
        this.isTabSelected = isSelected;
        if (isTabSelected)
            priceFeedService.setCurrencyCode(tradeCurrencyCode.get());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected Offer createAndGetOffer() {
        return createOfferService.createAndGetOffer(offerId,
                direction,
                tradeCurrencyCode.get(),
                amount.get(),
                minAmount.get(),
                useMarketBasedPrice.get() ? null : price.get(),
                useMarketBasedPrice.get(),
                useMarketBasedPrice.get() ? marketPriceMargin : 0,
                buyerSecurityDepositPct.get(),
                paymentAccount);
    }

    void onPlaceOffer(Offer offer, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.placeOffer(offer,
                useSavingsWallet,
                triggerPrice,
                resultHandler,
                errorMessageHandler);
    }

    void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        if (paymentAccount != null && !this.paymentAccount.equals(paymentAccount)) {
            preferences.setSelectedPaymentAccountForCreateOffer(paymentAccount);
            this.paymentAccount = paymentAccount;

            setTradeCurrencyFromPaymentAccount(paymentAccount);
            setSuggestedSecurityDeposit(getPaymentAccount());

            if (amount.get() != null && this.allowAmountUpdate)
                this.amount.set(amount.get().min(BigInteger.valueOf(getMaxTradeLimit())));
        }
    }

    private void setSuggestedSecurityDeposit(PaymentAccount paymentAccount) {
        var minSecurityDeposit = Restrictions.getMinBuyerSecurityDepositAsPercent();
        try {
            if (getTradeCurrency() == null) {
                setBuyerSecurityDeposit(minSecurityDeposit);
                return;
            }
            // Get average historic prices over for the prior trade period equaling the lock time
            var blocksRange = Restrictions.getLockTime(paymentAccount.getPaymentMethod().isBlockchain());
            var startDate = new Date(System.currentTimeMillis() - blocksRange * 10L * 60000);
            var sortedRangeData = tradeStatisticsManager.getObservableTradeStatisticsSet().stream()
                    .filter(e -> e.getCurrency().equals(getTradeCurrency().getCode()))
                    .filter(e -> e.getDate().compareTo(startDate) >= 0)
                    .sorted(Comparator.comparing(TradeStatistics3::getDate))
                    .collect(Collectors.toList());
            var movingAverage = new MathUtils.MovingAverage(10, 0.2);
            double[] extremes = {Double.MAX_VALUE, Double.MIN_VALUE};
            sortedRangeData.forEach(e -> {
                var price = e.getTradePrice().getValue();
                movingAverage.next(price).ifPresent(val -> {
                    if (val < extremes[0]) extremes[0] = val;
                    if (val > extremes[1]) extremes[1] = val;
                });
            });
            var min = extremes[0];
            var max = extremes[1];
            if (min == 0d || max == 0d) {
                setBuyerSecurityDeposit(minSecurityDeposit);
                return;
            }
            // Suggested deposit is double the trade range over the previous lock time period, bounded by min/max deposit
            var suggestedSecurityDeposit =
                    Math.min(2 * (max - min) / max, Restrictions.getMaxBuyerSecurityDepositAsPercent());
            buyerSecurityDepositPct.set(Math.max(suggestedSecurityDeposit, minSecurityDeposit));
        } catch (Throwable t) {
            log.error(t.toString());
            buyerSecurityDepositPct.set(minSecurityDeposit);
        }
    }

    private void setTradeCurrencyFromPaymentAccount(PaymentAccount paymentAccount) {
        if (!paymentAccount.getTradeCurrencies().contains(tradeCurrency))
            tradeCurrency = paymentAccount.getTradeCurrency().orElse(tradeCurrency);

        checkNotNull(tradeCurrency, "tradeCurrency must not be null");
        tradeCurrencyCode.set(tradeCurrency.getCode());
    }

    void onCurrencySelected(TradeCurrency tradeCurrency) {
        if (tradeCurrency != null) {
            if (!this.tradeCurrency.equals(tradeCurrency)) {
                volume.set(null);
                minVolume.set(null);
                price.set(null);
                marketPriceMargin = 0;
            }

            this.tradeCurrency = tradeCurrency;
            final String code = this.tradeCurrency.getCode();
            tradeCurrencyCode.set(code);

            if (paymentAccount != null)
                paymentAccount.setSelectedTradeCurrency(tradeCurrency);

            priceFeedService.setCurrencyCode(code);

            Optional<TradeCurrency> tradeCurrencyOptional = preferences.getTradeCurrenciesAsObservable()
                    .stream().filter(e -> e.getCode().equals(code)).findAny();
            if (tradeCurrencyOptional.isEmpty()) {
                if (CurrencyUtil.isCryptoCurrency(code)) {
                    CurrencyUtil.getCryptoCurrency(code).ifPresent(preferences::addCryptoCurrency);
                } else {
                    CurrencyUtil.getTraditionalCurrency(code).ifPresent(preferences::addTraditionalCurrency);
                }
            }
        }
    }

    void fundFromSavingsWallet() {
        this.useSavingsWallet = true;
        updateBalance();
        if (!isXmrWalletFunded.get()) {
            this.useSavingsWallet = false;
            updateBalance();
        }
    }

    protected void setMarketPriceMarginPct(double marketPriceMargin) {
        this.marketPriceMargin = marketPriceMargin;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    boolean isMinAmountLessOrEqualAmount() {
        //noinspection SimplifiableIfStatement
        if (minAmount.get() != null && amount.get() != null)
            return minAmount.get().compareTo(amount.get()) <= 0;
        return true;
    }

    public OfferDirection getDirection() {
        return direction;
    }

    boolean isSellOffer() {
        return direction == OfferDirection.SELL;
    }

    boolean isBuyOffer() {
        return direction == OfferDirection.BUY;
    }

    XmrAddressEntry getAddressEntry() {
        return addressEntry;
    }

    protected TradeCurrency getTradeCurrency() {
        return tradeCurrency;
    }

    protected PaymentAccount getPaymentAccount() {
        return paymentAccount;
    }

    protected void setUseMarketBasedPrice(boolean useMarketBasedPrice) {
        this.useMarketBasedPrice.set(useMarketBasedPrice);
        preferences.setUsePercentageBasedPrice(useMarketBasedPrice);
    }

    public ObservableList<PaymentAccount> getPaymentAccounts() {
        return paymentAccounts;
    }

    public double getMarketPriceMarginPct() {
        return marketPriceMargin;
    }

    long getMaxTradeLimit() {
        if (paymentAccount != null) {
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, tradeCurrencyCode.get(), direction);
        } else {
            return 0;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    double calculateMarketPriceManual(double marketPrice, double volumeAsDouble, double amountAsDouble) {
        double manualPriceAsDouble = offerUtil.calculateManualPrice(volumeAsDouble, amountAsDouble);
        double percentage = offerUtil.calculateMarketPriceMarginPct(manualPriceAsDouble, marketPrice);

        setMarketPriceMarginPct(percentage);

        return manualPriceAsDouble;
    }

    void calculateVolume() {
        if (isNonZeroPrice.test(price) && isNonZeroAmount.test(amount)) {
            try {
                Volume volumeByAmount = calculateVolumeForAmount(amount);

                volume.set(volumeByAmount);

                calculateMinVolume();
            } catch (Throwable t) {
                log.error(t.toString());
            }
        }

        updateBalance();
    }

    void calculateMinVolume() {
        if (isNonZeroPrice.test(price) && isNonZeroAmount.test(minAmount)) {
            try {
                Volume volumeByAmount = calculateVolumeForAmount(minAmount);

                minVolume.set(volumeByAmount);

            } catch (Throwable t) {
                log.error(t.toString());
            }
        }
    }

    private Volume calculateVolumeForAmount(ObjectProperty<BigInteger> minAmount) {
        Volume volumeByAmount = price.get().getVolumeByAmount(minAmount.get());

        // For HalCash we want multiple of 10 EUR
        if (isUsingHalCashAccount())
            volumeByAmount = VolumeUtil.getAdjustedVolumeForHalCash(volumeByAmount);
        else if (CurrencyUtil.isFiatCurrency(tradeCurrencyCode.get()))
            volumeByAmount = VolumeUtil.getRoundedFiatVolume(volumeByAmount);
        return volumeByAmount;
    }

    void calculateAmount() {
        if (isNonZeroPrice.test(price) && isNonZeroVolume.test(volume) && allowAmountUpdate) {
            try {
                BigInteger value = HavenoUtils.coinToAtomicUnits(DisplayUtils.reduceTo4Decimals(HavenoUtils.atomicUnitsToCoin(price.get().getAmountByVolume(volume.get())), btcFormatter));
                if (isUsingHalCashAccount())
                    value = CoinUtil.getAdjustedAmountForHalCash(value, price.get(), getMaxTradeLimit());
                else if (CurrencyUtil.isFiatCurrency(tradeCurrencyCode.get()))
                    value = CoinUtil.getRoundedFiatAmount(value, price.get(), getMaxTradeLimit());

                calculateVolume();

                amount.set(value);
                calculateTotalToPay();
            } catch (Throwable t) {
                log.error(t.toString());
            }
        }
    }

    void calculateTotalToPay() {
        // Maker does not pay the mining fee for the trade txs because the mining fee might be different when maker
        // created the offer and reserved his funds, so that would not work well with dynamic fees.
        // The mining fee for the createOfferFee tx is deducted from the createOfferFee and not visible to the trader
        final BigInteger makerFee = getMakerFee();
        if (direction != null && amount.get() != null && makerFee != null) {
            BigInteger feeAndSecDeposit = getSecurityDeposit().add(makerFee);
            BigInteger total = isBuyOffer() ? feeAndSecDeposit : feeAndSecDeposit.add(amount.get());
            totalToPay.set(total);
            updateBalance();
        }
    }

    BigInteger getSecurityDeposit() {
        return isBuyOffer() ? getBuyerSecurityDeposit() : getSellerSecurityDeposit();
    }

    void swapTradeToSavings() {
        xmrWalletService.resetAddressEntriesForOpenOffer(offerId);
    }

    private void fillPaymentAccounts() {
        if (user.getPaymentAccounts() != null)
            paymentAccounts.setAll(new HashSet<>(user.getPaymentAccounts()));
        paymentAccounts.sort(comparing(PaymentAccount::getAccountName));
    }

    protected abstract Set<PaymentAccount> getUserPaymentAccounts();

    protected void setAmount(BigInteger amount) {
        this.amount.set(amount);
    }

    protected void setPrice(Price price) {
        this.price.set(price);
    }

    protected void setVolume(Volume volume) {
        this.volume.set(volume);
    }

    protected void setBuyerSecurityDeposit(double value) {
        this.buyerSecurityDepositPct.set(value);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected ReadOnlyObjectProperty<BigInteger> getAmount() {
        return amount;
    }

    protected ReadOnlyObjectProperty<BigInteger> getMinAmount() {
        return minAmount;
    }

    public ReadOnlyObjectProperty<Price> getPrice() {
        return price;
    }

    ReadOnlyObjectProperty<Volume> getVolume() {
        return volume;
    }

    ReadOnlyObjectProperty<Volume> getMinVolume() {
        return minVolume;
    }

    protected void setMinAmount(BigInteger minAmount) {
        this.minAmount.set(minAmount);
    }

    public ReadOnlyStringProperty getTradeCurrencyCode() {
        return tradeCurrencyCode;
    }

    public String getCurrencyCode() {
        return tradeCurrencyCode.get();
    }

    boolean isCryptoCurrency() {
        return CurrencyUtil.isCryptoCurrency(tradeCurrencyCode.get());
    }

    boolean isFiatCurrency() {
        return CurrencyUtil.isTraditionalCurrency(tradeCurrencyCode.get());
    }

    ReadOnlyBooleanProperty getUseMarketBasedPrice() {
        return useMarketBasedPrice;
    }

    ReadOnlyDoubleProperty getBuyerSecurityDepositPct() {
        return buyerSecurityDepositPct;
    }

    protected BigInteger getBuyerSecurityDeposit() {
        BigInteger percentOfAmount = CoinUtil.getPercentOfAmount(buyerSecurityDepositPct.get(), amount.get());
        return getBoundedBuyerSecurityDeposit(percentOfAmount);
    }

    private BigInteger getSellerSecurityDeposit() {
        BigInteger amount = this.amount.get();
        if (amount == null)
            amount = BigInteger.valueOf(0);

        BigInteger percentOfAmount = CoinUtil.getPercentOfAmount(
                createOfferService.getSellerSecurityDepositAsDouble(buyerSecurityDepositPct.get()), amount);
        return getBoundedSellerSecurityDeposit(percentOfAmount);
    }

    protected BigInteger getBoundedBuyerSecurityDeposit(BigInteger value) {
        // We need to ensure that for small amount values we don't get a too low BTC amount. We limit it with using the
        // MinBuyerSecurityDeposit from Restrictions.
        return Restrictions.getMinBuyerSecurityDeposit().max(value);
    }

    private BigInteger getBoundedSellerSecurityDeposit(BigInteger value) {
        // We need to ensure that for small amount values we don't get a too low BTC amount. We limit it with using the
        // MinSellerSecurityDeposit from Restrictions.
        return Restrictions.getMinSellerSecurityDeposit().max(value);
    }

    ReadOnlyObjectProperty<BigInteger> totalToPayAsProperty() {
        return totalToPay;
    }

    public void setMarketPriceAvailable(boolean marketPriceAvailable) {
        this.marketPriceAvailable = marketPriceAvailable;
    }

    public BigInteger getMakerFee() {
        return HavenoUtils.getMakerFee(amount.get());
    }

    boolean canPlaceOffer() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService) &&
                GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation);
    }

    public boolean isMinBuyerSecurityDeposit() {
        return getBuyerSecurityDeposit().compareTo(Restrictions.getMinBuyerSecurityDeposit()) <= 0;
    }

    public void setTriggerPrice(long triggerPrice) {
        this.triggerPrice = triggerPrice;
    }

    public boolean isUsingHalCashAccount() {
        return paymentAccount.hasPaymentMethodWithId(HAL_CASH_ID);
    }
}
