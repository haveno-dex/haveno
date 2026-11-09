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

package haveno.desktop.util;

import com.google.common.collect.Lists;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.user.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CurrencyListTest {
    private static final Locale locale = new Locale("en", "US");

    private static final TradeCurrency USD = new TraditionalCurrency(Currency.getInstance("USD"), locale);
    private static final TradeCurrency RUR = new TraditionalCurrency(Currency.getInstance("RUR"), locale);
    private static final TradeCurrency BTC = new CryptoCurrency("BTC", "Bitcoin");
    private static final TradeCurrency ETH = new CryptoCurrency("ETH", "Ether");
    private static final TradeCurrency XAU = new TraditionalCurrency("XAU", "Gold");

    private Preferences preferences;
    private List<CurrencyListItem> delegate;
    private CurrencyList testedEntity;

    @BeforeEach
    public void setUp() {
        Locale.setDefault(locale);

        CurrencyPredicates predicates = mock(CurrencyPredicates.class);
        when(predicates.isCryptoCurrency(USD)).thenReturn(false);
        when(predicates.isCryptoCurrency(RUR)).thenReturn(false);
        when(predicates.isCryptoCurrency(BTC)).thenReturn(true);
        when(predicates.isCryptoCurrency(ETH)).thenReturn(true);
        when(predicates.isCryptoCurrency(XAU)).thenReturn(false);

        when(predicates.isFiatCurrency(USD)).thenReturn(true);
        when(predicates.isFiatCurrency(RUR)).thenReturn(true);
        when(predicates.isFiatCurrency(BTC)).thenReturn(false);
        when(predicates.isFiatCurrency(ETH)).thenReturn(false);
        when(predicates.isFiatCurrency(XAU)).thenReturn(false);

        this.preferences = mock(Preferences.class);
        this.delegate = new ArrayList<>();
        this.testedEntity = new CurrencyList(delegate, preferences, predicates);
    }

    @Test
    public void testUpdateWhenSortNumerically() {
        when(preferences.isSortMarketCurrenciesNumerically()).thenReturn(true);

        List<TradeCurrency> currencies = Lists.newArrayList(USD, RUR, USD, ETH, ETH, BTC, XAU);
        testedEntity.updateWithCurrencies(currencies, null);

        List<CurrencyListItem> expected = Lists.newArrayList(
                new CurrencyListItem(USD, 2),
                new CurrencyListItem(ETH, 2),
                new CurrencyListItem(RUR, 1),
                new CurrencyListItem(BTC, 1),
                new CurrencyListItem(XAU, 1));

        assertEquals(expected, delegate);
    }

    @Test
    public void testUpdateWhenSortNumericallyPlacesPreferredWithoutOffersLast() {
        when(preferences.isSortMarketCurrenciesNumerically()).thenReturn(true);
        when(preferences.getTraditionalCurrencies()).thenReturn(Lists.newArrayList((TraditionalCurrency) RUR));

        List<TradeCurrency> currencies = Lists.newArrayList(USD, ETH, ETH, BTC);
        testedEntity.updateWithCurrencies(currencies, null);

        List<CurrencyListItem> expected = Lists.newArrayList(
                new CurrencyListItem(ETH, 2),
                new CurrencyListItem(USD, 1),
                new CurrencyListItem(BTC, 1),
                new CurrencyListItem(RUR, 0));

        assertEquals(expected, delegate);
    }

    @Test
    public void testUpdateWhenNotSortNumerically() {
        when(preferences.isSortMarketCurrenciesNumerically()).thenReturn(false);

        List<TradeCurrency> currencies = Lists.newArrayList(USD, RUR, USD, ETH, ETH, BTC, XAU);
        testedEntity.updateWithCurrencies(currencies, null);

        List<CurrencyListItem> expected = Lists.newArrayList(
                new CurrencyListItem(RUR, 1),
                new CurrencyListItem(USD, 2),
                new CurrencyListItem(BTC, 1),
                new CurrencyListItem(ETH, 2),
                new CurrencyListItem(XAU, 1));

        assertEquals(expected, delegate);
    }

    @Test
    public void testUpdateWhenSortNumericallyAndFirstSpecified() {
        when(preferences.isSortMarketCurrenciesNumerically()).thenReturn(true);

        List<TradeCurrency> currencies = Lists.newArrayList(USD, RUR, USD, ETH, ETH, BTC);
        CurrencyListItem first = new CurrencyListItem(BTC, 5);
        testedEntity.updateWithCurrencies(currencies, List.of(first));

        List<CurrencyListItem> expected = Lists.newArrayList(
                first,
                new CurrencyListItem(USD, 2),
                new CurrencyListItem(ETH, 2),
                new CurrencyListItem(RUR, 1),
                new CurrencyListItem(BTC, 1));

        assertEquals(expected, delegate);
    }
}
