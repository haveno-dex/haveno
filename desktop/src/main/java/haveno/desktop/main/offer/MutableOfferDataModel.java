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

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import haveno.common.UserThread;
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
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.Comparator;
import static java.util.Comparator.comparing;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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

public abstract class MutableOfferDataModel extends OfferDataModel {
    protected final CreateOfferService createOfferService;
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
    protected final ObjectProperty<String> extraInfo = new SimpleObjectProperty<>();

    // Percentage value of buyer security deposit. E.g. 0.01 means 1% of trade amount
    protected final DoubleProperty securityDepositPct = new SimpleDoubleProperty();
    protected final BooleanProperty buyerAsTakerWithoutDeposit = new SimpleBooleanProperty();

    protected final ObservableList<PaymentAccount> paymentAccounts = FXCollections.observableArrayList();

    protected PaymentAccount paymentAccount;
    boolean isTabSelected;
    protected double marketPriceMarginPct = 0;
    @Getter
    private boolean marketPriceAvailable;
    protected boolean allowAmountUpdate = true;
    private final TradeStatisticsManager tradeStatisticsManager;

    private final Predicate<ObjectProperty<BigInteger>> isNonZeroAmount = (c) -> c.get() != null && c.get().compareTo(BigInteger.ZERO) != 0;
    private final Predicate<ObjectProperty<Price>> isNonZeroPrice = (p) -> p.get() != null && !p.get().isZero();
    private final Predicate<ObjectProperty<Volume>> isNonZeroVolume = (v) -> v.get() != null && !v.get().isZero();
    @Getter
    protected long triggerPrice;
    @Getter
    protected boolean reserveExactAmount;
    private XmrBalanceListener xmrBalanceListener;


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

        reserveExactAmount = preferences.getSplitOfferOutput();

        useMarketBasedPrice.set(preferences.isUsePercentageBasedPrice());
        securityDepositPct.set(Restrictions.getMinSecurityDepositAsPercent());

        paymentAccountsChangeListener = change -> fillPaymentAccounts();
    }

    @Override
    public void activate() {
        addListeners();

        if (isTabSelected)
            priceFeedService.setCurrencyCode(tradeCurrencyCode.get());

        updateBalances();
    }

    @Override
    protected void deactivate() {
        removeListeners();
    }

    private void addListeners() {
        if (xmrBalanceListener != null) xmrWalletService.addBalanceListener(xmrBalanceListener);
        user.getPaymentAccountsAsObservable().addListener(paymentAccountsChangeListener);
    }

    private void removeListeners() {
        if (xmrBalanceListener != null) xmrWalletService.removeBalanceListener(xmrBalanceListener);
        user.getPaymentAccountsAsObservable().removeListener(paymentAccountsChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before activate()
    public boolean initWithData(OfferDirection direction, TradeCurrency tradeCurrency, boolean initAddressEntry) {
        if (initAddressEntry) {
            addressEntry = xmrWalletService.getOrCreateAddressEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
            xmrBalanceListener = new XmrBalanceListener(getAddressEntry().getSubaddressIndex()) {
                @Override
                public void onBalanceChanged(BigInteger balance) {
                    updateBalances();
                }
            };
        }

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
        updateBalances();
        setSuggestedSecurityDeposit(getPaymentAccount());

        return true;
    }

    @NotNull
    private Optional<PaymentAccount> getAnyPaymentAccount() {
        if (CurrencyUtil.isFiatCurrency(tradeCurrency.getCode())) {
            return paymentAccounts.stream().filter(paymentAccount1 -> paymentAccount1.isFiat()).findAny();
        } else if (CurrencyUtil.isCryptoCurrency(tradeCurrency.getCode())) {
            return paymentAccounts.stream().filter(paymentAccount1 -> paymentAccount1.isCryptoCurrency()).findAny();
        } else {
            return paymentAccounts.stream().filter(paymentAccount1 -> paymentAccount1.getTradeCurrency().isPresent()).findAny();
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

    protected void updateBalances() {
        if (addressEntry == null) return;
        super.updateBalances();

        // update remaining balance
        UserThread.await(() -> {
            missingCoin.set(offerUtil.getBalanceShortage(totalToPay.get(), balance.get()));
            isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPay.get(), balance.get()));
            if (totalToPay.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
                showWalletFundedNotification.set(true);
            }
        });
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
                useMarketBasedPrice.get() ? marketPriceMarginPct : 0,
                securityDepositPct.get(),
                paymentAccount,
                buyerAsTakerWithoutDeposit.get(), // private offer if buyer as taker without deposit
                buyerAsTakerWithoutDeposit.get(),
                extraInfo.get());
    }

    void onPlaceOffer(Offer offer, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.placeOffer(offer,
                useSavingsWallet,
                triggerPrice,
                reserveExactAmount,
                false, // desktop ui resets address entries on cancel
                null,
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
        var minSecurityDeposit = Restrictions.getMinSecurityDepositAsPercent();
        try {
            if (getTradeCurrency() == null) {
                setSecurityDepositPct(minSecurityDeposit);
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
                setSecurityDepositPct(minSecurityDeposit);
                return;
            }
            // Suggested deposit is double the trade range over the previous lock time period, bounded by min/max deposit
            var suggestedSecurityDeposit =
                    Math.min(2 * (max - min) / max, Restrictions.getMaxSecurityDepositAsPercent());
            securityDepositPct.set(Math.max(suggestedSecurityDeposit, minSecurityDeposit));
        } catch (Throwable t) {
            log.error(t.toString());
            securityDepositPct.set(minSecurityDeposit);
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
                marketPriceMarginPct = 0;
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
        updateBalances();
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

    protected void setBuyerAsTakerWithoutDeposit(boolean buyerAsTakerWithoutDeposit) {
        this.buyerAsTakerWithoutDeposit.set(buyerAsTakerWithoutDeposit);
    }

    public ObservableList<PaymentAccount> getPaymentAccounts() {
        return paymentAccounts;
    }

    public double getMarketPriceMarginPct() {
        return marketPriceMarginPct;
    }

    long getMaxTradeLimit() {

        // disallow offers which no buyer can take due to trade limits on release
        if (HavenoUtils.isReleasedWithinDays(HavenoUtils.RELEASE_LIMIT_DAYS)) {
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, tradeCurrencyCode.get(), OfferDirection.BUY, buyerAsTakerWithoutDeposit.get());
        }

        if (paymentAccount != null) {
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, tradeCurrencyCode.get(), direction, buyerAsTakerWithoutDeposit.get());
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

        updateBalances();
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
        volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, paymentAccount.getPaymentMethod().getId());
        return volumeByAmount;
    }

    void calculateAmount() {
        if (isNonZeroPrice.test(price) && isNonZeroVolume.test(volume) && allowAmountUpdate) {
            try {
                Volume volumeBefore = volume.get();
                calculateVolume();

                // if the volume != amount * price, we need to adjust the amount
                if (amount.get() == null || !volumeBefore.equals(price.get().getVolumeByAmount(amount.get()))) {
                    BigInteger value = price.get().getAmountByVolume(volumeBefore);
                    value = value.min(BigInteger.valueOf(getMaxTradeLimit())); // adjust if above maximum
                    value = value.max(Restrictions.getMinTradeAmount()); // adjust if below minimum
                    value = CoinUtil.getRoundedAmount(value, price.get(), getMaxTradeLimit(), tradeCurrencyCode.get(), paymentAccount.getPaymentMethod().getId());
                    amount.set(value);
                }

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
        final BigInteger makerFee = getMaxMakerFee();
        if (direction != null && amount.get() != null && makerFee != null) {
            BigInteger feeAndSecDeposit = getSecurityDeposit().add(makerFee);
            BigInteger total = isBuyOffer() ? feeAndSecDeposit : feeAndSecDeposit.add(amount.get());
            totalToPay.set(total);
            updateBalances();
        }
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

    protected void setMinAmount(BigInteger minAmount) {
        this.minAmount.set(minAmount);
    }

    protected void setPrice(Price price) {
        this.price.set(price);
    }

    protected void setVolume(Volume volume) {
        this.volume.set(volume);
    }

    protected void setSecurityDepositPct(double value) {
        this.securityDepositPct.set(value);
    }

    public void setMarketPriceAvailable(boolean marketPriceAvailable) {
        this.marketPriceAvailable = marketPriceAvailable;
    }

    public void setTriggerPrice(long triggerPrice) {
        this.triggerPrice = triggerPrice;
    }

    public void setMarketPriceMarginPct(double marketPriceMarginPct) {
        this.marketPriceMarginPct = marketPriceMarginPct;
    }

    public void setReserveExactAmount(boolean reserveExactAmount) {
        this.reserveExactAmount = reserveExactAmount;
    }

    protected void setExtraInfo(String extraInfo) {
        this.extraInfo.set(extraInfo);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BigInteger getMaxUnsignedBuyLimit() {
        return BigInteger.valueOf(accountAgeWitnessService.getUnsignedTradeLimit(paymentAccount.getPaymentMethod(), tradeCurrencyCode.get(), OfferDirection.BUY));
    }

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

    public ReadOnlyBooleanProperty getBuyerAsTakerWithoutDeposit() {
        return buyerAsTakerWithoutDeposit;
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

    boolean isTraditionalCurrency() {
        return CurrencyUtil.isTraditionalCurrency(tradeCurrencyCode.get());
    }

    ReadOnlyBooleanProperty getUseMarketBasedPrice() {
        return useMarketBasedPrice;
    }

    ReadOnlyDoubleProperty getSecurityDepositPct() {
        return securityDepositPct;
    }

    protected BigInteger getSecurityDeposit() {
        BigInteger amount = this.amount.get();
        if (amount == null) amount = BigInteger.ZERO;
        BigInteger percentOfAmount = CoinUtil.getPercentOfAmount(securityDepositPct.get(), amount);
        return getBoundedSecurityDeposit(percentOfAmount);
    }

    protected BigInteger getBoundedSecurityDeposit(BigInteger value) {
        return Restrictions.getMinSecurityDeposit().max(value);
    }

    protected double getSecurityAsPercent(Offer offer) {
        BigInteger offerSellerSecurityDeposit = getBoundedSecurityDeposit(offer.getMaxSellerSecurityDeposit());
        double offerSellerSecurityDepositAsPercent = CoinUtil.getAsPercentPerXmr(offerSellerSecurityDeposit,
                offer.getAmount());
        return Math.min(offerSellerSecurityDepositAsPercent,
                Restrictions.getMaxSecurityDepositAsPercent());
    }

    ReadOnlyObjectProperty<BigInteger> totalToPayAsProperty() {
        return totalToPay;
    }

    public BigInteger getMaxMakerFee() {
        return HavenoUtils.multiply(amount.get(), buyerAsTakerWithoutDeposit.get() ? HavenoUtils.MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT : HavenoUtils.MAKER_FEE_PCT);
    }

    boolean canPlaceOffer() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService) &&
                GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation);
    }

    public boolean isMinSecurityDeposit() {
        return getSecurityDeposit().compareTo(Restrictions.getMinSecurityDeposit()) <= 0;
    }

    public ReadOnlyObjectProperty<String> getExtraInfo() {
        return extraInfo;
    }
}
