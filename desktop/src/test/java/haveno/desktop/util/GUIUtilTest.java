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

import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import javafx.util.StringConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static haveno.desktop.maker.TradeCurrencyMakers.euro;
import static haveno.desktop.maker.TradeCurrencyMakers.monero;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class GUIUtilTest {

    @BeforeEach
    public void setup() {
        Locale.setDefault(new Locale("en", "US"));
        GlobalSettings.setLocale(new Locale("en", "US"));
        Res.setBaseCurrencyCode("BTC");
        Res.setBaseCurrencyName("Bitcoin");
    }

    @Test
    public void testTradeCurrencyConverter() {
        Map<String, Integer> offerCounts = new HashMap<>() {{
            put("XMR", 11);
            put("EUR", 10);
        }};
        StringConverter<TradeCurrency> tradeCurrencyConverter = GUIUtil.getTradeCurrencyConverter(
                Res.get("shared.oneOffer"),
                Res.get("shared.multipleOffers"),
                offerCounts
        );

        assertEquals("✦ Monero (XMR) - 11 offers", tradeCurrencyConverter.toString(monero));
        assertEquals("★ Euro (EUR) - 10 offers", tradeCurrencyConverter.toString(euro));
    }

    @Test
    public void testOpenURLWithCampaignParameters() {
        Preferences preferences = mock(Preferences.class);
        DontShowAgainLookup.setPreferences(preferences);
        GUIUtil.setPreferences(preferences);
        when(preferences.showAgain("warnOpenURLWhenTorEnabled")).thenReturn(false);
        when(preferences.getUserLanguage()).thenReturn("en");

/*        PowerMockito.mockStatic(Utilities.class);
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        PowerMockito.doNothing().when(Utilities.class, "openURI", captor.capture());
        GUIUtil.openWebPage("https://haveno.exchange");

        assertEquals("https://haveno.exchange?utm_source=desktop-client&utm_medium=in-app-link&utm_campaign=language_en", captor.getValue().toString());

        GUIUtil.openWebPage("https://docs.haveno.exchange/trading-rules.html#f2f-trading");

        assertEquals("https://docs.haveno.exchange/trading-rules.html?utm_source=desktop-client&utm_medium=in-app-link&utm_campaign=language_en#f2f-trading", captor.getValue().toString());
*/
    }

    @Test
    public void testOpenURLWithoutCampaignParameters() {
        Preferences preferences = mock(Preferences.class);
        DontShowAgainLookup.setPreferences(preferences);
        GUIUtil.setPreferences(preferences);
        when(preferences.showAgain("warnOpenURLWhenTorEnabled")).thenReturn(false);
/*
        PowerMockito.mockStatic(Utilities.class);
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        PowerMockito.doNothing().when(Utilities.class, "openURI", captor.capture());
        GUIUtil.openWebPage("https://www.github.com");

        assertEquals("https://www.github.com", captor.getValue().toString());
*/
    }

    @Test
    public void percentageOfTradeAmount1() {

        BigInteger fee = BigInteger.valueOf(200000000L);

        assertEquals(" (0.02% of trade amount)", GUIUtil.getPercentageOfTradeAmount(fee, HavenoUtils.xmrToAtomicUnits(1.0)));
    }

    @Test
    public void percentageOfTradeAmount2() {

        BigInteger fee = BigInteger.valueOf(100000000L);

        assertEquals(" (0.01% of trade amount)",
                GUIUtil.getPercentageOfTradeAmount(fee, HavenoUtils.xmrToAtomicUnits(1.0)));
    }
}
