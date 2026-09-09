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

package haveno.desktop.main.presentation;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.UserThread;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.TxIdTextField;
import haveno.desktop.main.shared.PriceFeedComboBoxItem;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import lombok.Getter;

@Singleton
public class MarketPricePresentation {
    private static final Comparator<String> CURRENCY_CODE_COMPARATOR = Comparator.comparing(CurrencyUtil::isCryptoCurrency)
            .thenComparing(Comparator.naturalOrder());
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    @Getter
    private final ObservableList<PriceFeedComboBoxItem> priceFeedComboBoxItems = FXCollections.observableArrayList();
    @Getter // items with a known price and the active currency, shown in the selector
    private final ObservableList<PriceFeedComboBoxItem> availablePriceFeedComboBoxItems = FXCollections.observableArrayList();

    private final ObjectProperty<PriceFeedComboBoxItem> selectedPriceFeedComboBoxItemProperty = new SimpleObjectProperty<>();
    private final BooleanProperty isFiatCurrencyPriceFeedSelected = new SimpleBooleanProperty(true);
    private final BooleanProperty isCryptoCurrencyPriceFeedSelected = new SimpleBooleanProperty(false);
    private final BooleanProperty isExternallyProvidedPrice = new SimpleBooleanProperty(true);
    private final BooleanProperty isPriceAvailable = new SimpleBooleanProperty(false);
    private final IntegerProperty marketPriceUpdated = new SimpleIntegerProperty(0);
    private final StringProperty marketPrice = new SimpleStringProperty(Res.get("shared.na"));


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MarketPricePresentation(XmrWalletService xmrWalletService,
                                   PriceFeedService priceFeedService,
                                   Preferences preferences) {
        this.priceFeedService = priceFeedService;
        this.preferences = preferences;

        TxIdTextField.setPreferences(preferences);

        TxIdTextField.setXmrWalletService(xmrWalletService);
    }

    public void setup() {
        // initialize bound controls on the user thread after background startup
        UserThread.execute(() -> {
            fillPriceFeedComboBoxItems();
            setupMarketPriceFeed();
        });
    }

    public void setPriceFeedComboBoxItem(PriceFeedComboBoxItem item) {
        if (item != null && !item.currencyCode.equals(CurrencyUtil.getCurrencyCodeBase(priceFeedService.getCurrencyCode())))
            priceFeedService.setCurrencyCode(item.currencyCode);
    }

    private void fillPriceFeedComboBoxItems() {

        // collect unique currency code bases
        List<String> uniqueCurrencyCodeBases = preferences.getTradeCurrenciesAsObservable()
                .stream()
                .map(TradeCurrency::getCode)
                .map(CurrencyUtil::getCurrencyCodeBase)
                .distinct()
                .collect(Collectors.toList());

        // create price feed items sorted by code, fiat before cryptos
        List<PriceFeedComboBoxItem> currencyItems = uniqueCurrencyCodeBases
                .stream()
                .sorted(CURRENCY_CODE_COMPARATOR)
                .map(currencyCodeBase -> findPriceFeedComboBoxItem(currencyCodeBase).orElseGet(() -> {
                    PriceFeedComboBoxItem selectedItem = selectedPriceFeedComboBoxItemProperty.get();
                    return selectedItem != null && selectedItem.currencyCode.equals(currencyCodeBase)
                            ? selectedItem : new PriceFeedComboBoxItem(currencyCodeBase);
                }))
                .collect(Collectors.toList());
        if (!currencyItems.equals(priceFeedComboBoxItems)) priceFeedComboBoxItems.setAll(currencyItems);
    }

    private void setupMarketPriceFeed() {
        priceFeedService.currencyCodeProperty().addListener((observable, oldValue, newValue) ->
                UserThread.execute(this::updateSelectedPriceFeedComboBoxItem));
        priceFeedService.updateCounterProperty().addListener((observable, oldValue, newValue) ->
                UserThread.execute(this::setMarketPriceInItems));

        preferences.getTradeCurrenciesAsObservable().addListener((ListChangeListener<TradeCurrency>) c -> UserThread.runAfter(() -> {
            fillPriceFeedComboBoxItems();
            setMarketPriceInItems();
        }, 100, TimeUnit.MILLISECONDS));

        updateSelectedPriceFeedComboBoxItem();
        priceFeedService.startRequestingPrices(price -> marketPrice.set(FormattingUtils.formatMarketPrice(price, priceFeedService.getCurrencyCode())),
                (errorMessage, throwable) -> marketPrice.set(Res.get("shared.na")));
    }

    private void updateSelectedPriceFeedComboBoxItem() {
        String currencyCode = priceFeedService.getCurrencyCode();
        if (currencyCode == null) {
            selectedPriceFeedComboBoxItemProperty.set(null);
        } else {
            if (findPriceFeedComboBoxItem(currencyCode).isEmpty()) {
                // add currencies selected by other screens, but do not undo preference removals on price updates
                if (CurrencyUtil.isCryptoCurrency(currencyCode))
                    CurrencyUtil.getCryptoCurrency(currencyCode).ifPresent(preferences::addCryptoCurrency);
                else
                    CurrencyUtil.getTraditionalCurrency(currencyCode).ifPresent(preferences::addTraditionalCurrency);
                fillPriceFeedComboBoxItems();
            }
            PriceFeedComboBoxItem selectedItem = findPriceFeedComboBoxItem(currencyCode).orElseGet(() -> {
                PriceFeedComboBoxItem currentItem = selectedPriceFeedComboBoxItemProperty.get();
                String currencyCodeBase = CurrencyUtil.getCurrencyCodeBase(currencyCode);
                return currentItem != null && currentItem.currencyCode.equals(currencyCodeBase)
                        ? currentItem : new PriceFeedComboBoxItem(currencyCodeBase);
            });
            setMarketPriceInItem(selectedItem);
            selectedPriceFeedComboBoxItemProperty.set(selectedItem);
        }
        setMarketPriceInItems();
    }

    private Optional<PriceFeedComboBoxItem> findPriceFeedComboBoxItem(String currencyCode) {
        return priceFeedComboBoxItems.stream()
                .filter(item -> CurrencyUtil.getCurrencyCodeBase(item.currencyCode).equals(CurrencyUtil.getCurrencyCodeBase(currencyCode)))
                .findAny();
    }

    private void setMarketPriceInItems() {
        priceFeedComboBoxItems.forEach(this::setMarketPriceInItem);
        PriceFeedComboBoxItem selectedItem = selectedPriceFeedComboBoxItemProperty.get();
        if (selectedItem != null && !priceFeedComboBoxItems.contains(selectedItem))
            setMarketPriceInItem(selectedItem);

        // keep the active currency visible without a price or a preference entry
        List<PriceFeedComboBoxItem> availableItems = Stream.concat(priceFeedComboBoxItems.stream(), Stream.ofNullable(selectedItem))
                .filter(item -> item.isPriceAvailable() || item == selectedItem)
                .distinct()
                .sorted(Comparator.comparing(item -> item.currencyCode, CURRENCY_CODE_COMPARATOR))
                .collect(Collectors.toList());
        if (!availableItems.equals(availablePriceFeedComboBoxItems)) availablePriceFeedComboBoxItems.setAll(availableItems);

        String currencyCode = selectedItem == null ? null : selectedItem.currencyCode;
        boolean priceAvailable = selectedItem != null && selectedItem.isPriceAvailable();
        boolean externallyProvidedPrice = priceAvailable && selectedItem.isExternallyProvidedPrice();
        isFiatCurrencyPriceFeedSelected.set(externallyProvidedPrice && CurrencyUtil.isTraditionalCurrency(currencyCode) && CurrencyUtil.getTraditionalCurrency(currencyCode).isPresent());
        isCryptoCurrencyPriceFeedSelected.set(externallyProvidedPrice && CurrencyUtil.isCryptoCurrency(currencyCode) && CurrencyUtil.getCryptoCurrency(currencyCode).isPresent());
        isExternallyProvidedPrice.set(externallyProvidedPrice);
        isPriceAvailable.set(priceAvailable);
        marketPriceUpdated.set(marketPriceUpdated.get() + 1);
    }

    private void setMarketPriceInItem(PriceFeedComboBoxItem item) {
        String currencyCode = item.currencyCode;
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        boolean priceAvailable = marketPrice != null && marketPrice.isPriceAvailable();
        item.setPriceAvailable(priceAvailable);
        item.setExternallyProvidedPrice(priceAvailable && marketPrice.isExternallyProvidedPrice());
        String priceString = priceAvailable ? FormattingUtils.formatMarketPrice(marketPrice.getPrice(), currencyCode) : Res.get("shared.na");
        item.setDisplayString(CurrencyUtil.getCurrencyPair(currencyCode) + ": " + priceString);
    }

    public ObjectProperty<PriceFeedComboBoxItem> getSelectedPriceFeedComboBoxItemProperty() {
        return selectedPriceFeedComboBoxItemProperty;
    }

    public BooleanProperty getIsFiatCurrencyPriceFeedSelected() {
        return isFiatCurrencyPriceFeedSelected;
    }

    public BooleanProperty getIsCryptoCurrencyPriceFeedSelected() {
        return isCryptoCurrencyPriceFeedSelected;
    }

    public BooleanProperty getIsExternallyProvidedPrice() {
        return isExternallyProvidedPrice;
    }

    public BooleanProperty getIsPriceAvailable() {
        return isPriceAvailable;
    }

    public IntegerProperty getMarketPriceUpdated() {
        return marketPriceUpdated;
    }

    public StringProperty getMarketPrice() {
        return marketPrice;
    }

    public StringProperty getMarketPrice(String currencyCode) {
        SimpleStringProperty marketPrice = new SimpleStringProperty(Res.get("shared.na"));
        MarketPrice marketPriceValue = priceFeedService.getMarketPrice(currencyCode);
        // Market price might not be available yet:
        if (marketPriceValue != null) {
            marketPrice.set(String.valueOf(marketPriceValue.getPrice()));
        }
        return marketPrice;
    }
}
