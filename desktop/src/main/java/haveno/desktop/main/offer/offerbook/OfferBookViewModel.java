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

package haveno.desktop.main.offer.offerbook;

import com.google.common.base.Joiner;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.CoreApi;
import haveno.core.locale.BankUtil;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableViewModel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.settings.SettingsView;
import haveno.desktop.main.settings.preferences.PreferencesView;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

@Slf4j
abstract class OfferBookViewModel extends ActivatableViewModel {
    private final OpenOfferManager openOfferManager;
    private final User user;
    final OfferBook offerBook;
    final Preferences preferences;
    private final WalletsSetup walletsSetup;
    private final P2PService p2PService;
    final PriceFeedService priceFeedService;
    private final ClosedTradableManager closedTradableManager;
    final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final PriceUtil priceUtil;
    final OfferFilterService offerFilterService;
    private final CoinFormatter btcFormatter;

    private final FilteredList<OfferBookListItem> filteredItems;
    private final CoreApi coreApi;
    private final SortedList<OfferBookListItem> sortedItems;
    private final ListChangeListener<TradeCurrency> tradeCurrencyListChangeListener;
    private final ListChangeListener<OfferBookListItem> filterItemsListener;
    private TradeCurrency selectedTradeCurrency;
    @Getter
    private final ObservableList<TradeCurrency> tradeCurrencies = FXCollections.observableArrayList();
    @Getter
    private final ObservableList<TradeCurrency> allCurrencies = FXCollections.observableArrayList();

    private OfferDirection direction;

    final StringProperty tradeCurrencyCode = new SimpleStringProperty();
    
    private static final Object processOfferBookListItemsLock = new Object();
    @Nullable
    private static Timer processOfferBookListItemsTimer;
    private final String THREAD_ID = OfferBookViewModel.class.getSimpleName();

    private OfferView.OfferActionHandler offerActionHandler;

    // If id is empty string we ignore filter (display all methods)

    PaymentMethod selectedPaymentMethod = getShowAllEntryForPaymentMethod();

    boolean isTabSelected;
    String filterText = "";
    final BooleanProperty showAllTradeCurrenciesProperty = new SimpleBooleanProperty(true);
    final BooleanProperty disableMatchToggle = new SimpleBooleanProperty();
    final IntegerProperty maxPlacesForAmount = new SimpleIntegerProperty();
    final IntegerProperty maxPlacesForVolume = new SimpleIntegerProperty();
    final IntegerProperty maxPlacesForPrice = new SimpleIntegerProperty();
    final IntegerProperty maxPlacesForMarketPriceMargin = new SimpleIntegerProperty();
    boolean showAllPaymentMethods = true;
    boolean useOffersMatchingMyAccountsFilter;
    boolean showPrivateOffers;

    protected static final boolean SORT_CURRENCIES_BY_OFFER_COUNT = true; // TODO: make configurable via preferences?


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OfferBookViewModel(User user,
                              OpenOfferManager openOfferManager,
                              OfferBook offerBook,
                              Preferences preferences,
                              WalletsSetup walletsSetup,
                              P2PService p2PService,
                              PriceFeedService priceFeedService,
                              ClosedTradableManager closedTradableManager,
                              AccountAgeWitnessService accountAgeWitnessService,
                              Navigation navigation,
                              PriceUtil priceUtil,
                              OfferFilterService offerFilterService,
                              CoinFormatter btcFormatter,
                              CoreApi coreApi) {
        super();

        this.openOfferManager = openOfferManager;
        this.user = user;
        this.offerBook = offerBook;
        this.preferences = preferences;
        this.walletsSetup = walletsSetup;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.closedTradableManager = closedTradableManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.priceUtil = priceUtil;
        this.offerFilterService = offerFilterService;
        this.btcFormatter = btcFormatter;

        this.filteredItems = new FilteredList<>(offerBook.getOfferBookListItems());
        this.coreApi = coreApi;
        this.sortedItems = new SortedList<>(filteredItems);

        tradeCurrencyListChangeListener = c -> fillCurrencies();

        // refresh on offer changes
        offerBook.getOfferBookListItems().addListener((ListChangeListener<OfferBookListItem>) c -> {
            synchronized (processOfferBookListItemsLock) {
                if (processOfferBookListItemsTimer == null) {
                    processOfferBookListItemsTimer = UserThread.runAfter(() -> {
                        ThreadUtils.execute(() -> {
                            offerBook.fillOfferCountMaps();
                            fillCurrencies();
                            synchronized (processOfferBookListItemsLock) {
                                processOfferBookListItemsTimer = null;
                            }
                        }, THREAD_ID);
                    }, 100, TimeUnit.MILLISECONDS);
                }
            }

            // TODO: This is removed because it's expensive to re-filter offers for every change (high cpu for many offers).
            // This was used to ensure offer list is fully refreshed, but is unnecessary after refactoring OfferBookService to clone offers?
            //filterOffers();
        });

        filterItemsListener = c -> {
            final Optional<OfferBookListItem> highestAmountOffer = filteredItems.stream()
                    .max(Comparator.comparingLong(o -> o.getOffer().getAmount().longValueExact()));

            final boolean containsRangeAmount = filteredItems.stream().anyMatch(o -> o.getOffer().isRange());

            if (highestAmountOffer.isPresent()) {
                final OfferBookListItem item = highestAmountOffer.get();
                if (!item.getOffer().isRange() && containsRangeAmount) {
                    maxPlacesForAmount.set(formatAmount(item.getOffer(), false)
                            .length() * 2 + FormattingUtils.RANGE_SEPARATOR.length());
                    maxPlacesForVolume.set(formatVolume(item.getOffer(), false)
                            .length() * 2 + FormattingUtils.RANGE_SEPARATOR.length());
                } else {
                    maxPlacesForAmount.set(formatAmount(item.getOffer(), false).length());
                    maxPlacesForVolume.set(formatVolume(item.getOffer(), false).length());
                }
            }

            final Optional<OfferBookListItem> highestPriceOffer = filteredItems.stream()
                    .filter(o -> o.getOffer().getPrice() != null)
                    .max(Comparator.comparingLong(o -> o.getOffer().getPrice() != null ? o.getOffer().getPrice().getValue() : 0));

            highestPriceOffer.ifPresent(offerBookListItem -> maxPlacesForPrice.set(formatPrice(offerBookListItem.getOffer(), false).length()));

            final Optional<OfferBookListItem> highestMarketPriceMarginOffer = filteredItems.stream()
                    .filter(o -> o.getOffer().isUseMarketBasedPrice())
                    .max(Comparator.comparing(o -> new DecimalFormat("#0.00").format(o.getOffer().getMarketPriceMarginPct() * 100).length()));

            highestMarketPriceMarginOffer.ifPresent(offerBookListItem -> maxPlacesForMarketPriceMargin.set(formatMarketPriceMarginPct(offerBookListItem.getOffer()).length()));
        };
    }

    @Override
    protected void activate() {
        filteredItems.addListener(filterItemsListener);

        if (user != null) {
            disableMatchToggle.set(user.getPaymentAccounts() == null || user.getPaymentAccounts().isEmpty());
        }
        useOffersMatchingMyAccountsFilter = !disableMatchToggle.get() && isShowOffersMatchingMyAccounts();
        showPrivateOffers = preferences.isShowPrivateOffers();

        updateSelectedTradeCurrency();
        fillCurrencies();
        preferences.getTradeCurrenciesAsObservable().addListener(tradeCurrencyListChangeListener);
        offerBook.fillOfferBookListItems();
        filterOffers();
        setMarketPriceFeedCurrency();
    }

    @Override
    protected void deactivate() {
        filteredItems.removeListener(filterItemsListener);
        preferences.getTradeCurrenciesAsObservable().removeListener(tradeCurrencyListChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    void initWithDirection(OfferDirection direction) {
        this.direction = direction;
    }

    void onTabSelected(boolean isSelected) {
        this.isTabSelected = isSelected;
        setMarketPriceFeedCurrency();

        if (isTabSelected) {
            updateSelectedTradeCurrency();
            filterOffers();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onSetTradeCurrency(TradeCurrency tradeCurrency) {
        if (tradeCurrency != null) {
            String code = tradeCurrency.getCode();
            boolean showAllEntry = isShowAllEntry(code);
            showAllTradeCurrenciesProperty.set(showAllEntry);
            if (isEditEntry(code))
                navigation.navigateTo(MainView.class, SettingsView.class, PreferencesView.class);
            else if (!showAllEntry) {
                this.selectedTradeCurrency = tradeCurrency;
                tradeCurrencyCode.set(code);
            }

            setMarketPriceFeedCurrency();
            filterOffers();

            saveSelectedCurrencyCodeInPreferences(direction, code);
        }
    }

    void onFilterKeyTyped(String filterText) {
        this.filterText = filterText;
        filterOffers();
    }

    abstract void saveSelectedCurrencyCodeInPreferences(OfferDirection direction, String code);

    protected void onSetPaymentMethod(PaymentMethod paymentMethod) {
        if (paymentMethod == null)
            return;

        showAllPaymentMethods = isShowAllEntry(paymentMethod.getId());
        if (!showAllPaymentMethods) {
            this.selectedPaymentMethod = paymentMethod;

            // If we select Wise we switch to show all currencies as Wise supports
            // sending to most currencies.
            if (paymentMethod.getId().equals(PaymentMethod.TRANSFERWISE_ID)) {
                onSetTradeCurrency(new CryptoCurrency(GUIUtil.SHOW_ALL_FLAG, ""));
            }
        } else {
            this.selectedPaymentMethod = getShowAllEntryForPaymentMethod();
        }

        filterOffers();
    }

    void onRemoveOpenOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.removeOffer(offer, resultHandler, errorMessageHandler);
    }

    void onShowOffersMatchingMyAccounts(boolean isSelected) {
        useOffersMatchingMyAccountsFilter = isSelected;
        preferences.setShowOffersMatchingMyAccounts(useOffersMatchingMyAccountsFilter);
        filterOffers();
    }

    void onShowPrivateOffers(boolean isSelected) {
        showPrivateOffers = isSelected;
        preferences.setShowPrivateOffers(isSelected);
        filterOffers();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    boolean isShowOffersMatchingMyAccounts() {
        return preferences.isShowOffersMatchingMyAccounts();
    }

    SortedList<OfferBookListItem> getOfferList() {
        return sortedItems;
    }

    Map<String, Integer> getBuyOfferCounts() {
        return offerBook.getBuyOfferCountMap();
    }

    Map<String, Integer> getSellOfferCounts() {
        return offerBook.getSellOfferCountMap();
    }

    boolean isMyOffer(Offer offer) {
        return openOfferManager.isMyOffer(offer);
    }

    OfferDirection getDirection() {
        return direction;
    }

    boolean isBootstrappedOrShowPopup() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }

    TradeCurrency getSelectedTradeCurrency() {
        return selectedTradeCurrency;
    }

    Map<String, Integer> getOfferCounts() {
        return OfferViewUtil.isShownAsBuyOffer(getDirection(), getSelectedTradeCurrency()) ? getSellOfferCounts() : getBuyOfferCounts();
    }

    ObservableList<PaymentMethod> getPaymentMethods() {
        ObservableList<PaymentMethod> list = FXCollections.observableArrayList(PaymentMethod.paymentMethods);
        if (preferences.isHideNonAccountPaymentMethods() && user.getPaymentAccounts() != null) {
            Set<PaymentMethod> supportedPaymentMethods = user.getPaymentAccounts().stream()
                    .map(PaymentAccount::getPaymentMethod).collect(Collectors.toSet());
            if (!supportedPaymentMethods.isEmpty()) {
                list = FXCollections.observableArrayList(supportedPaymentMethods);
            }
        }

        list = filterPaymentMethods(list, selectedTradeCurrency);

        list.sort(Comparator.naturalOrder());
        list.add(0, getShowAllEntryForPaymentMethod());
        return list;
    }

    protected abstract ObservableList<PaymentMethod> filterPaymentMethods(ObservableList<PaymentMethod> list,
                                                                          TradeCurrency selectedTradeCurrency);

    String getAmount(OfferBookListItem item) {
        return formatAmount(item.getOffer(), true);
    }

    private String formatAmount(Offer offer, boolean decimalAligned) {
        return DisplayUtils.formatAmount(offer, GUIUtil.AMOUNT_DECIMALS, decimalAligned, maxPlacesForAmount.get(), btcFormatter);
    }


    String getPrice(OfferBookListItem item) {
        if ((item == null)) {
            return "";
        }

        Offer offer = item.getOffer();
        Price price = offer.getPrice();
        if (price != null) {
            return formatPrice(offer, true);
        } else {
            return Res.get("shared.na");
        }
    }

    String getAbsolutePriceMargin(Offer offer) {
        return FormattingUtils.formatPercentagePrice(Math.abs(offer.getMarketPriceMarginPct()));
    }

    private String formatPrice(Offer offer, boolean decimalAligned) {
        return DisplayUtils.formatPrice(offer.getPrice(), decimalAligned, maxPlacesForPrice.get());
    }

    String getPriceAsPercentage(OfferBookListItem item) {
        return getMarketBasedPrice(item.getOffer())
                .map(price -> "(" + FormattingUtils.formatPercentagePrice(price) + ")")
                .orElse("");
    }

    public Optional<Double> getMarketBasedPrice(Offer offer) {
        return priceUtil.getMarketBasedPrice(offer, direction);
    }

    String formatMarketPriceMarginPct(Offer offer) {
        String postFix = "";
        if (offer.isUseMarketBasedPrice()) {
            postFix = " (" + FormattingUtils.formatPercentagePrice(offer.getMarketPriceMarginPct()) + ")";
        }

        return postFix;
    }

    String getVolume(OfferBookListItem item) {
        return formatVolume(item.getOffer(), true);
    }

    String getVolumeAmount(OfferBookListItem item) {
        return formatVolume(item.getOffer(), true, false);
    }

    private String formatVolume(Offer offer, boolean decimalAligned) {
        return formatVolume(offer, decimalAligned, showAllTradeCurrenciesProperty.get());
    }

    private String formatVolume(Offer offer, boolean decimalAligned, boolean appendCurrencyCode) {
        Volume offerVolume = offer.getVolume();
        Volume minOfferVolume = offer.getMinVolume();
        if (offerVolume != null && minOfferVolume != null) {
            String postFix = appendCurrencyCode ? " " + offer.getCounterCurrencyCode() : "";
            decimalAligned = decimalAligned && !showAllTradeCurrenciesProperty.get();
            return VolumeUtil.formatVolume(offer, decimalAligned, maxPlacesForVolume.get()) + postFix;
        } else {
            return Res.get("shared.na");
        }
    }

    int getNumberOfDecimalsForVolume(OfferBookListItem item) {
        return CurrencyUtil.isVolumeRoundedToNearestUnit(item.getOffer().getCounterCurrencyCode()) ? GUIUtil.NUM_DECIMALS_UNIT : GUIUtil.NUM_DECIMALS_PRECISE;
    }

    String getPaymentMethod(OfferBookListItem item) {
        String result = "";
        if (item != null) {
            Offer offer = item.getOffer();
            String method = Res.get(offer.getPaymentMethod().getId() + "_SHORT");
            String methodCountryCode = offer.getCountryCode();
            if (isF2F(offer)) {
                result = method + " (" + methodCountryCode + ", " + offer.getF2FCity() + ")";
            } else {
                if (methodCountryCode != null)
                    result = method + " (" + methodCountryCode + ")";
                else
                    result = method;
            }

        }
        return result;
    }

    String getPaymentMethodToolTip(OfferBookListItem item) {
        String result = "";
        if (item != null) {
            Offer offer = item.getOffer();
            result = Res.getWithCol("shared.paymentMethod") + " " + Res.get(offer.getPaymentMethod().getId());
            result += "\n" + Res.getWithCol("shared.currency") + " " + CurrencyUtil.getNameAndCode(offer.getCounterCurrencyCode());

            String countryCode = offer.getCountryCode();
            if (isF2F(offer)) {
                if (countryCode != null) {
                    result += "\n" + Res.get("payment.f2f.offerbook.tooltip.countryAndCity",
                            CountryUtil.getNameByCode(countryCode), offer.getF2FCity());
                }
            } else {
                if (countryCode != null) {
                    String bankId = offer.getBankId();
                    if (bankId != null && !bankId.equals("null")) {
                        if (BankUtil.isBankIdRequired(countryCode))
                            result += "\n" + Res.get("offerbook.offerersBankId", bankId);
                        else if (BankUtil.isBankNameRequired(countryCode))
                            result += "\n" + Res.get("offerbook.offerersBankName", bankId);
                    }
                }

                if (countryCode != null)
                    result += "\n" + Res.get("offerbook.offerersBankSeat", CountryUtil.getNameByCode(countryCode));

                List<String> acceptedCountryCodes = offer.getAcceptedCountryCodes();
                List<String> acceptedBanks = offer.getAcceptedBankIds();
                if (acceptedCountryCodes != null && !acceptedCountryCodes.isEmpty()) {
                    if (CountryUtil.containsAllSepaEuroCountries(acceptedCountryCodes))
                        result += "\n" + Res.get("offerbook.offerersAcceptedBankSeatsEuro");
                    else
                        result += "\n" + Res.get("offerbook.offerersAcceptedBankSeats", CountryUtil.getNamesByCodesString(acceptedCountryCodes));
                } else if (acceptedBanks != null && !acceptedBanks.isEmpty()) {
                    if (offer.getPaymentMethod().equals(PaymentMethod.SAME_BANK))
                        result += "\n" + Res.getWithCol("shared.bankName") + " " + acceptedBanks.get(0);
                    else if (offer.getPaymentMethod().equals(PaymentMethod.SPECIFIC_BANKS))
                        result += "\n" + Res.getWithCol("shared.acceptedBanks") + " " + Joiner.on(", ").join(acceptedBanks);
                }
            }
            if (offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty())
                result += "\n" + Res.get("payment.shared.extraInfo.tooltip", offer.getCombinedExtraInfo());
        }
        return result;
    }

    private boolean isF2F(Offer offer) {
        return offer.getPaymentMethod().equals(PaymentMethod.F2F);
    }

    String getDirectionLabelTooltip(Offer offer) {
        return getDirectionWithCodeDetailed(offer.getMirroredDirection(), offer.getCounterCurrencyCode());
    }

    Optional<PaymentAccount> getMostMaturePaymentAccountForOffer(Offer offer) {
        return PaymentAccountUtil.getMostMaturePaymentAccountForOffer(offer, user.getPaymentAccounts(), accountAgeWitnessService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setMarketPriceFeedCurrency() {
        if (isTabSelected) {
            if (showAllTradeCurrenciesProperty.get())
                priceFeedService.setCurrencyCode(getDefaultTradeCurrency().getCode());
            else
                priceFeedService.setCurrencyCode(tradeCurrencyCode.get());
        }
    }

    private void fillCurrencies() {
        tradeCurrencies.clear();
        allCurrencies.clear();

        fillCurrencies(tradeCurrencies, allCurrencies);
    }

    abstract void fillCurrencies(ObservableList<TradeCurrency> tradeCurrencies,
                                 ObservableList<TradeCurrency> allCurrencies);

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Checks
    ///////////////////////////////////////////////////////////////////////////////////////////

    boolean hasPaymentAccountForCurrency() {
        return hasPaymentAccountForCurrency(selectedTradeCurrency);
    }

    boolean hasPaymentAccountForCurrency(TradeCurrency currency) {
        return user.hasPaymentAccountForCurrency(currency);
    }

    boolean canCreateOrTakeOffer() {
        return GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation) &&
                GUIUtil.isWalletSyncedWithinToleranceOrShowPopup(openOfferManager.getXmrWalletService()) &&
                GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Filters
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void filterOffers() {
        Predicate<OfferBookListItem> predicate = useOffersMatchingMyAccountsFilter ?
                getCurrencyAndMethodPredicate(direction, selectedTradeCurrency).and(getOffersMatchingMyAccountsPredicate()) :
                getCurrencyAndMethodPredicate(direction, selectedTradeCurrency);

        // filter private offers
        if (direction == OfferDirection.BUY) {
            predicate = predicate.and(offerBookListItem -> offerBookListItem.getOffer().isPrivateOffer() == showPrivateOffers);
        }

        if (!filterText.isEmpty()) {

            // filter node address
            Predicate<OfferBookListItem> nextPredicate = offerBookListItem ->
                    offerBookListItem.getOffer().getOfferPayload().getOwnerNodeAddress().getFullAddress().toLowerCase().contains(filterText.toLowerCase());

            // filter offer id
            nextPredicate = nextPredicate.or(offerBookListItem ->
                    offerBookListItem.getOffer().getId().toLowerCase().contains(filterText.toLowerCase()));

            // filter full payment method
            nextPredicate = nextPredicate.or(offerBookListItem ->
                    Res.get(offerBookListItem.getOffer().getPaymentMethod().getId()).toLowerCase().contains(filterText.toLowerCase()));

            // filter short payment method
            nextPredicate = nextPredicate.or(offerBookListItem -> {
                return getPaymentMethod(offerBookListItem).toLowerCase().contains(filterText.toLowerCase());
            });

            // filter currencies
            nextPredicate = nextPredicate.or(offerBookListItem -> {
                return offerBookListItem.getOffer().getCounterCurrencyCode().toLowerCase().contains(filterText.toLowerCase()) ||
                        offerBookListItem.getOffer().getBaseCurrencyCode().toLowerCase().contains(filterText.toLowerCase()) ||
                        CurrencyUtil.getNameAndCode(offerBookListItem.getOffer().getCounterCurrencyCode()).toLowerCase().contains(filterText.toLowerCase()) ||
                        CurrencyUtil.getNameAndCode(offerBookListItem.getOffer().getBaseCurrencyCode()).toLowerCase().contains(filterText.toLowerCase());
            });

            // filter extra info
            nextPredicate = nextPredicate.or(offerBookListItem -> {
                return offerBookListItem.getOffer().getCombinedExtraInfo() != null &&
                        offerBookListItem.getOffer().getCombinedExtraInfo().toLowerCase().contains(filterText.toLowerCase());
            });

            filteredItems.setPredicate(predicate.and(nextPredicate));
        } else {
            filteredItems.setPredicate(predicate);
        }
    }

    abstract Predicate<OfferBookListItem> getCurrencyAndMethodPredicate(OfferDirection direction,
                                                                        TradeCurrency selectedTradeCurrency);

    private Predicate<OfferBookListItem> getOffersMatchingMyAccountsPredicate() {
        // This code duplicates code in the view at the button column. We need there the different results for
        // display in popups so we cannot replace that with the predicate. Any change need to be applied in both
        // places.
        return offerBookListItem -> offerFilterService.canTakeOffer(offerBookListItem.getOffer(), false).isValid();
    }

    boolean isOfferBanned(Offer offer) {
        return offerFilterService.isOfferBanned(offer);
    }

    private boolean isShowAllEntry(String id) {
        return id.equals(GUIUtil.SHOW_ALL_FLAG);
    }

    private boolean isEditEntry(String id) {
        return id.equals(GUIUtil.EDIT_FLAG);
    }

    public int getNumTrades(Offer offer) {
        return closedTradableManager.getTradableList().stream()
                .filter(e -> e instanceof Trade)    // weed out canceled offers
                .filter(e -> {
                    final Optional<NodeAddress> tradePeerNodeAddress = e.getOptionalTradePeerNodeAddress();
                    return tradePeerNodeAddress.isPresent() &&
                            tradePeerNodeAddress.get().getFullAddress().equals(offer.getMakerNodeAddress().getFullAddress());
                })
                .collect(Collectors.toSet())
                .size();
    }

    public boolean hasSelectionAccountSigning() {
        if (showAllTradeCurrenciesProperty.get()) {
            if (isShowAllEntry(selectedPaymentMethod.getId()))
                return !(this instanceof CryptoOfferBookViewModel);
            else
                return PaymentMethod.hasChargebackRisk(selectedPaymentMethod);
        } else {
            if (isShowAllEntry(selectedPaymentMethod.getId()))
                return CurrencyUtil.getMatureMarketCurrencies().stream()
                        .anyMatch(c -> c.getCode().equals(selectedTradeCurrency.getCode()));
            else
                return PaymentMethod.hasChargebackRisk(selectedPaymentMethod, tradeCurrencyCode.get());
        }
    }

    private static String getDirectionWithCodeDetailed(OfferDirection direction, String currencyCode) {
        return (direction == OfferDirection.BUY) ? Res.get("shared.buyingXMRWith", currencyCode) : Res.get("shared.sellingXMRFor", currencyCode);
    }

    public String formatDepositString(BigInteger deposit, long amount) {
        var percentage = FormattingUtils.formatToRoundedPercentWithSymbol(HavenoUtils.divide(deposit, BigInteger.valueOf(amount)));
        return HavenoUtils.formatXmr(deposit) + " (" + percentage + ")";
    }

    PaymentMethod getShowAllEntryForPaymentMethod() {
        return PaymentMethod.getDummyPaymentMethod(GUIUtil.SHOW_ALL_FLAG);
    }

    public boolean isInstantPaymentMethod(Offer offer) {
        return offer.getPaymentMethod().equals(PaymentMethod.BLOCK_CHAINS_INSTANT);
    }

    public void setOfferActionHandler(OfferView.OfferActionHandler offerActionHandler) {
        this.offerActionHandler = offerActionHandler;
    }

    public void onCreateOffer() {
        offerActionHandler.onCreateOffer(getSelectedTradeCurrency(), selectedPaymentMethod);
    }

    public void onTakeOffer(Offer offer) {
        offerActionHandler.onTakeOffer(offer);
    }

    private void updateSelectedTradeCurrency() {
        String code = getCurrencyCodeFromPreferences(direction);
        if (code != null && !code.isEmpty() && !isShowAllEntry(code) &&
                CurrencyUtil.getTradeCurrency(code).isPresent()) {
            showAllTradeCurrenciesProperty.set(false);
            selectedTradeCurrency = CurrencyUtil.getTradeCurrency(code).get();
        } else {
            showAllTradeCurrenciesProperty.set(true);
            selectedTradeCurrency = getDefaultTradeCurrency();
        }
        tradeCurrencyCode.set(selectedTradeCurrency.getCode());
    }

    abstract TradeCurrency getDefaultTradeCurrency();

    public void updateSelectedPaymentMethod() {
        showAllPaymentMethods = getPaymentMethods().stream().noneMatch(paymentMethod ->
                paymentMethod.equals(selectedPaymentMethod));
    }

    abstract String getCurrencyCodeFromPreferences(OfferDirection direction);

    public OpenOffer getOpenOffer(Offer offer) {
        return openOfferManager.getOpenOffer(offer.getId()).orElse(null);
    }
}
