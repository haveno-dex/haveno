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

package haveno.desktop.main.market.offerbook;

import com.google.common.math.LongMath;
import com.google.inject.Inject;

import haveno.common.UserThread;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.util.VolumeUtil;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ActivatableViewModel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.offer.BuyOfferView;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.offer.SellOfferView;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.main.settings.SettingsView;
import haveno.desktop.main.settings.preferences.PreferencesView;
import haveno.desktop.util.CurrencyList;
import haveno.desktop.util.CurrencyListItem;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.chart.XYChart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

class OfferBookChartViewModel extends ActivatableViewModel {
    private static final int TAB_INDEX = 0;

    private final OfferBook offerBook;
    private final OpenOfferManager openOfferManager;
    final Preferences preferences;
    final PriceFeedService priceFeedService;
    final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;

    final ObjectProperty<TradeCurrency> selectedTradeCurrencyProperty = new SimpleObjectProperty<>();
    private final List<XYChart.Data<Number, Number>> buyData = new ArrayList<>();
    private final List<XYChart.Data<Number, Number>> sellData = new ArrayList<>();
    private final ObservableList<OfferBookListItem> offerBookListItems;
    private final ListChangeListener<OfferBookListItem> offerBookListItemsListener;
    final CurrencyList currencyListItems;
    private final ObservableList<OfferListItem> topBuyOfferList = FXCollections.observableArrayList();
    private final ObservableList<OfferListItem> topSellOfferList = FXCollections.observableArrayList();
    private final ChangeListener<Number> currenciesUpdatedListener;
    private int selectedTabIndex;
    public final IntegerProperty maxPlacesForBuyPrice = new SimpleIntegerProperty();
    public final IntegerProperty maxPlacesForBuyVolume = new SimpleIntegerProperty();
    public final IntegerProperty maxPlacesForSellPrice = new SimpleIntegerProperty();
    public final IntegerProperty maxPlacesForSellVolume = new SimpleIntegerProperty();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    OfferBookChartViewModel(OfferBook offerBook,
                            OpenOfferManager openOfferManager,
                            Preferences preferences,
                            PriceFeedService priceFeedService,
                            AccountAgeWitnessService accountAgeWitnessService,
                            Navigation navigation) {
        this.offerBook = offerBook;
        this.openOfferManager = openOfferManager;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
        this.navigation = navigation;
        this.accountAgeWitnessService = accountAgeWitnessService;

        String code = preferences.getOfferBookChartScreenCurrencyCode();
        if (code != null) {
            Optional<TradeCurrency> tradeCurrencyOptional = CurrencyUtil.getTradeCurrency(code);
            if (tradeCurrencyOptional.isPresent())
                selectedTradeCurrencyProperty.set(tradeCurrencyOptional.get());
            else {
                selectedTradeCurrencyProperty.set(GlobalSettings.getDefaultTradeCurrency());
            }
        } else {
            selectedTradeCurrencyProperty.set(GlobalSettings.getDefaultTradeCurrency());
        }

        offerBookListItems = offerBook.getOfferBookListItems();
        offerBookListItemsListener = c -> {
            c.next();
            if (c.wasAdded() || c.wasRemoved()) {
                ArrayList<OfferBookListItem> list = new ArrayList<>(c.getRemoved());
                list.addAll(c.getAddedSubList());
                if (list.stream()
                        .map(OfferBookListItem::getOffer)
                        .anyMatch(e -> e.getCurrencyCode().equals(selectedTradeCurrencyProperty.get().getCode())))
                    updateChartData();
            }

            fillTradeCurrencies();
        };

        currenciesUpdatedListener = (observable, oldValue, newValue) -> {
            if (!isAnyPriceAbsent()) {
                UserThread.execute(() -> {
                    offerBook.fillOfferBookListItems();
                    updateChartData();
                    var self = this;
                    priceFeedService.updateCounterProperty().removeListener(self.currenciesUpdatedListener);
                });
            }
        };

        this.currencyListItems = new CurrencyList(preferences);
    }

    private void fillTradeCurrencies() {
        // Don't use a set as we need all entries
        synchronized (offerBookListItems) {
            List<TradeCurrency> tradeCurrencyList = offerBookListItems.stream()
                    .map(e -> {
                        String currencyCode = e.getOffer().getCurrencyCode();
                        Optional<TradeCurrency> tradeCurrencyOptional = CurrencyUtil.getTradeCurrency(currencyCode);
                        return tradeCurrencyOptional.orElse(null);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            currencyListItems.updateWithCurrencies(tradeCurrencyList, null);
        }
    }

    @Override
    protected void activate() {
        offerBookListItems.addListener(offerBookListItemsListener);

        offerBook.fillOfferBookListItems();
        fillTradeCurrencies();
        updateChartData();

        if (isAnyPriceAbsent())
            priceFeedService.updateCounterProperty().addListener(currenciesUpdatedListener);

        syncPriceFeedCurrency();
    }

    @Override
    protected void deactivate() {
        offerBookListItems.removeListener(offerBookListItemsListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onSetTradeCurrency(TradeCurrency tradeCurrency) {
        if (tradeCurrency != null) {
            final String code = tradeCurrency.getCode();

            if (isEditEntry(code)) {
                navigation.navigateTo(MainView.class, SettingsView.class, PreferencesView.class);
            } else {
                selectedTradeCurrencyProperty.set(tradeCurrency);
                preferences.setOfferBookChartScreenCurrencyCode(code);

                updateChartData();

                priceFeedService.setCurrencyCode(code);
            }
        }
    }

    public void setSelectedTabIndex(int selectedTabIndex) {
        this.selectedTabIndex = selectedTabIndex;
        syncPriceFeedCurrency();
    }

    public boolean isSellOffer(OfferDirection direction) {
        return direction == OfferDirection.SELL;
    }

    public double getTotalAmount(OfferDirection direction) {
        synchronized (offerBookListItems) {
            return offerBookListItems.stream()
                    .map(OfferBookListItem::getOffer)
                    .filter(e -> e.getCurrencyCode().equals(selectedTradeCurrencyProperty.get().getCode())
                            && e.getDirection().equals(direction))
                    .mapToDouble(item -> HavenoUtils.atomicUnitsToXmr(item.getAmount()))
                    .sum();
        }
    }

    public Volume getTotalVolume(OfferDirection direction) {
        synchronized (offerBookListItems) {
             List<Volume> volumes = offerBookListItems.stream()
                    .map(OfferBookListItem::getOffer)
                    .filter(e -> e.getCurrencyCode().equals(selectedTradeCurrencyProperty.get().getCode())
                            && e.getDirection().equals(direction))
                    .map(Offer::getVolume)
                    .collect(Collectors.toList());
            return VolumeUtil.sum(volumes);
        }
    }

    public boolean isMyOffer(Offer offer) {
        return openOfferManager.isMyOffer(offer);
    }

    public void goToOfferView(OfferDirection direction) {
        updateScreenCurrencyInPreferences(direction);
        Class<? extends OfferView> offerView = isSellOffer(direction) ? BuyOfferView.class : SellOfferView.class;
        navigation.navigateTo(MainView.class, offerView, OfferViewUtil.getOfferBookViewClass(getCurrencyCode()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public List<XYChart.Data<Number, Number>> getBuyData() {
        return buyData;
    }

    public List<XYChart.Data<Number, Number>> getSellData() {
        return sellData;
    }

    public String getCurrencyCode() {
        return selectedTradeCurrencyProperty.get().getCode();
    }

    public ObservableList<OfferBookListItem> getOfferBookListItems() {
        return offerBookListItems;
    }

    public ObservableList<OfferListItem> getTopBuyOfferList() {
        return topBuyOfferList;
    }

    public ObservableList<OfferListItem> getTopSellOfferList() {
        return topSellOfferList;
    }

    public ObservableList<CurrencyListItem> getCurrencyListItems() {
        return currencyListItems.getObservableList();
    }

    public Optional<CurrencyListItem> getSelectedCurrencyListItem() {
        return currencyListItems.getObservableList().stream()
                .filter(e -> e.tradeCurrency.equals(selectedTradeCurrencyProperty.get())).findAny();
    }

    public int getMaxNumberOfPriceZeroDecimalsToColorize(Offer offer) {
        return CurrencyUtil.isVolumeRoundedToNearestUnit(offer.getCurrencyCode())
                ? GUIUtil.NUM_DECIMALS_UNIT
                : GUIUtil.NUM_DECIMALS_PRECISE;
    }

    public int getZeroDecimalsForPrice(Offer offer) {
        return CurrencyUtil.isPricePrecise(offer.getCurrencyCode())
                ? GUIUtil.NUM_DECIMALS_PRECISE
                : GUIUtil.NUM_DECIMALS_PRICE_LESS_PRECISE;
    }

    public String getPrice(Offer offer) {
        return formatPrice(offer, true);
    }

    private String formatPrice(Offer offer, boolean decimalAligned) {
        return DisplayUtils.formatPrice(offer.getPrice(), decimalAligned, offer.isBuyOffer()
                ? maxPlacesForBuyPrice.get()
                : maxPlacesForSellPrice.get());
    }

    public String getVolume(Offer offer) {
        return formatVolume(offer, true);
    }

    private String formatVolume(Offer offer, boolean decimalAligned) {
        return VolumeUtil.formatVolume(offer,
                decimalAligned,
                offer.isBuyOffer() ? maxPlacesForBuyVolume.get() : maxPlacesForSellVolume.get(),
                false);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void syncPriceFeedCurrency() {
        if (selectedTabIndex == TAB_INDEX)
            priceFeedService.setCurrencyCode(getCurrencyCode());
    }

    private boolean isAnyPriceAbsent() {
        return offerBookListItems.stream().anyMatch(item -> item.getOffer().getPrice() == null);
    }

    private void updateChartData() {

        // Offer price can be null (if price feed unavailable), thus a null-tolerant comparator is used.
        Comparator<Offer> offerPriceComparator = Comparator.comparing(Offer::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));

        // Trading xmr-traditional is considered as buying/selling XMR, but trading xmr-crypto is
        // considered as buying/selling Crypto. Because of this, when viewing a xmr-crypto pair,
        // the buy column is actually the sell column and vice versa. To maintain the expected
        // ordering, we have to reverse the price comparator.
        boolean isCrypto = CurrencyUtil.isCryptoCurrency(getCurrencyCode());
//        if (isCrypto) offerPriceComparator = offerPriceComparator.reversed();

        // Offer amounts are used for the secondary sort. They are sorted from high to low.
        Comparator<Offer> offerAmountComparator = Comparator.comparing(Offer::getAmount).reversed();

        var buyOfferSortComparator =
                offerPriceComparator.reversed() // Buy offers, as opposed to sell offers, are primarily sorted from high price to low.
                        .thenComparing(offerAmountComparator);
        var sellOfferSortComparator =
                offerPriceComparator
                        .thenComparing(offerAmountComparator);

        OfferDirection buyOfferDirection = isCrypto ? OfferDirection.SELL : OfferDirection.BUY;

        List<Offer> allBuyOffers = offerBookListItems.stream()
                .map(OfferBookListItem::getOffer)
                .filter(e -> e.getCurrencyCode().equals(selectedTradeCurrencyProperty.get().getCode())
                        && e.getDirection().equals(buyOfferDirection))
                .sorted(buyOfferSortComparator)
                .collect(Collectors.toList());

        final Optional<Offer> highestBuyPriceOffer = allBuyOffers.stream()
                .filter(o -> o.getPrice() != null)
                .max(Comparator.comparingLong(o -> o.getPrice().getValue()));

        if (highestBuyPriceOffer.isPresent()) {
            final Offer offer = highestBuyPriceOffer.get();
            maxPlacesForBuyPrice.set(formatPrice(offer, false).length());
        } else {
            log.debug("highestBuyPriceOffer not present");
        }

        final Optional<Offer> highestBuyVolumeOffer = allBuyOffers.stream()
                .filter(o -> o.getVolume() != null)
                .max(Comparator.comparingLong(o -> o.getVolume().getValue()));

        if (highestBuyVolumeOffer.isPresent()) {
            final Offer offer = highestBuyVolumeOffer.get();
            maxPlacesForBuyVolume.set(formatVolume(offer, false).length());
        }

        buildChartAndTableEntries(allBuyOffers, OfferDirection.BUY, buyData, topBuyOfferList);

        OfferDirection sellOfferDirection = isCrypto ? OfferDirection.BUY : OfferDirection.SELL;

        List<Offer> allSellOffers = offerBookListItems.stream()
                .map(OfferBookListItem::getOffer)
                .filter(e -> e.getCurrencyCode().equals(selectedTradeCurrencyProperty.get().getCode())
                        && e.getDirection().equals(sellOfferDirection))
                .sorted(sellOfferSortComparator)
                .collect(Collectors.toList());

        final Optional<Offer> highestSellPriceOffer = allSellOffers.stream()
                .filter(o -> o.getPrice() != null)
                .max(Comparator.comparingLong(o -> o.getPrice().getValue()));

        if (highestSellPriceOffer.isPresent()) {
            final Offer offer = highestSellPriceOffer.get();
            maxPlacesForSellPrice.set(formatPrice(offer, false).length());
        }

        final Optional<Offer> highestSellVolumeOffer = allSellOffers.stream()
                .filter(o -> o.getVolume() != null)
                .max(Comparator.comparingLong(o -> o.getVolume().getValue()));

        if (highestSellVolumeOffer.isPresent()) {
            final Offer offer = highestSellVolumeOffer.get();
            maxPlacesForSellVolume.set(formatVolume(offer, false).length());
        }

        buildChartAndTableEntries(allSellOffers, OfferDirection.SELL, sellData, topSellOfferList);
    }

    private void buildChartAndTableEntries(List<Offer> sortedList,
                                           OfferDirection direction,
                                           List<XYChart.Data<Number, Number>> data,
                                           ObservableList<OfferListItem> offerTableList) {
        synchronized (data) {
            data.clear();
            double accumulatedAmount = 0;
            List<OfferListItem> offerTableListTemp = new ArrayList<>();
            for (Offer offer : sortedList) {
                Price price = offer.getPrice();
                if (price != null) {
                    double amount = (double) offer.getAmount().longValueExact() / LongMath.pow(10, HavenoUtils.XMR_SMALLEST_UNIT_EXPONENT);
                    accumulatedAmount += amount;
                    offerTableListTemp.add(new OfferListItem(offer, accumulatedAmount));
    
                    double priceAsDouble = (double) price.getValue() / LongMath.pow(10, price.smallestUnitExponent());
                    if (direction.equals(OfferDirection.BUY))
                        data.add(0, new XYChart.Data<>(priceAsDouble, accumulatedAmount));
                    else
                        data.add(new XYChart.Data<>(priceAsDouble, accumulatedAmount));
                }
            }
            offerTableList.setAll(offerTableListTemp);
        }
    }

    private boolean isEditEntry(String id) {
        return id.equals(GUIUtil.EDIT_FLAG);
    }

    private void updateScreenCurrencyInPreferences(OfferDirection direction) {
        if (isSellOffer(direction)) {
            if (CurrencyUtil.isFiatCurrency(getCurrencyCode())) {
                preferences.setBuyScreenCurrencyCode(getCurrencyCode());
            } else if (CurrencyUtil.isCryptoCurrency(getCurrencyCode())) {
                preferences.setBuyScreenCryptoCurrencyCode(getCurrencyCode());
            } else if (CurrencyUtil.isTraditionalCurrency(getCurrencyCode())) {
                preferences.setBuyScreenOtherCurrencyCode(getCurrencyCode());
            }
        } else {
            if (CurrencyUtil.isFiatCurrency(getCurrencyCode())) {
                preferences.setSellScreenCurrencyCode(getCurrencyCode());
            } else if (CurrencyUtil.isCryptoCurrency(getCurrencyCode())) {
                preferences.setSellScreenCryptoCurrencyCode(getCurrencyCode());
            } else if (CurrencyUtil.isTraditionalCurrency(getCurrencyCode())) {
                preferences.setSellScreenOtherCurrencyCode(getCurrencyCode());
            }
        }
    }
}
