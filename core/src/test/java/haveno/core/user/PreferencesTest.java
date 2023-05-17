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

package haveno.core.user;

import haveno.common.config.Config;
import haveno.common.persistence.PersistenceManager;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.xmr.nodes.LocalBitcoinNode;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PreferencesTest {

    private Preferences preferences;
    private PersistenceManager persistenceManager;

    @BeforeEach
    public void setUp() {
        final Locale en_US = new Locale("en", "US");
        Locale.setDefault(en_US);
        GlobalSettings.setLocale(en_US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");

        persistenceManager = mock(PersistenceManager.class);
        Config config = new Config();
        LocalBitcoinNode localBitcoinNode = new LocalBitcoinNode(config);
        preferences = new Preferences(
                persistenceManager, config, localBitcoinNode, null);
    }

    @Test
    public void testAddFiatCurrency() {
        final TraditionalCurrency usd = new TraditionalCurrency("USD");
        final TraditionalCurrency usd2 = new TraditionalCurrency("USD");
        final ObservableList<TraditionalCurrency> traditionalCurrencies = preferences.getTraditionalCurrenciesAsObservable();

        preferences.addTraditionalCurrency(usd);

        assertEquals(1, traditionalCurrencies.size());

        preferences.addTraditionalCurrency(usd2);

        assertEquals(1, traditionalCurrencies.size());
    }

    @Test
    public void testGetUniqueListOfFiatCurrencies() {
        PreferencesPayload payload = mock(PreferencesPayload.class);

        List<TraditionalCurrency> traditionalCurrencies = CurrencyUtil.getMainTraditionalCurrencies();
        final TraditionalCurrency usd = new TraditionalCurrency("USD");
        traditionalCurrencies.add(usd);

        when(persistenceManager.getPersisted(anyString())).thenReturn(payload);
        when(payload.getUserLanguage()).thenReturn("en");
        when(payload.getUserCountry()).thenReturn(CountryUtil.getDefaultCountry());
        when(payload.getPreferredTradeCurrency()).thenReturn(usd);
        when(payload.getTraditionalCurrencies()).thenReturn(traditionalCurrencies);

        preferences.readPersisted(() -> {
            assertEquals(7, preferences.getTraditionalCurrenciesAsObservable().size());
            assertTrue(preferences.getTraditionalCurrenciesAsObservable().contains(usd));
        });
    }

    @Test
    public void testGetUniqueListOfCryptoCurrencies() {
        PreferencesPayload payload = mock(PreferencesPayload.class);

        List<CryptoCurrency> cryptoCurrencies = CurrencyUtil.getMainCryptoCurrencies();
        final CryptoCurrency dash = new CryptoCurrency("DASH", "Dash");
        cryptoCurrencies.add(dash);

        when(persistenceManager.getPersisted(anyString())).thenReturn(payload);
        when(payload.getUserLanguage()).thenReturn("en");
        when(payload.getUserCountry()).thenReturn(CountryUtil.getDefaultCountry());
        when(payload.getPreferredTradeCurrency()).thenReturn(new TraditionalCurrency("USD"));
        when(payload.getCryptoCurrencies()).thenReturn(cryptoCurrencies);

        preferences.readPersisted(() -> {
            assertTrue(preferences.getCryptoCurrenciesAsObservable().contains(dash));
        });
    }

    @Test
    public void testUpdateOfPersistedFiatCurrenciesAfterLocaleChanged() {
        PreferencesPayload payload = mock(PreferencesPayload.class);

        List<TraditionalCurrency> traditionalCurrencies = new ArrayList<>();
        final TraditionalCurrency usd = new TraditionalCurrency(Currency.getInstance("USD"), new Locale("de", "AT"));
        traditionalCurrencies.add(usd);

        assertEquals("US-Dollar (USD)", usd.getNameAndCode());

        when(persistenceManager.getPersisted(anyString())).thenReturn(payload);
        when(payload.getUserLanguage()).thenReturn("en");
        when(payload.getUserCountry()).thenReturn(CountryUtil.getDefaultCountry());
        when(payload.getPreferredTradeCurrency()).thenReturn(usd);
        when(payload.getTraditionalCurrencies()).thenReturn(traditionalCurrencies);

        preferences.readPersisted(() -> {
            assertEquals("US Dollar (USD)", preferences.getTraditionalCurrenciesAsObservable().get(0).getNameAndCode());
        });
    }
}
