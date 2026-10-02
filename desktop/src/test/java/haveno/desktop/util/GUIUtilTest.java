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

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.reactfx.FxTimer;
import haveno.core.app.TorSetup;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.desktop.common.UITimer;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.network.p2p.network.NetworkNode;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


public class GUIUtilTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testCountrySearchIncludesGlobalPaymentMethods() throws Exception {
        Method queryFilter = TraditionalAccountsView.class.getDeclaredMethod("getPaymentMethodQueryFilter", List.class);
        queryFilter.setAccessible(true);
        Function<String, Predicate<PaymentMethod>> filter = (Function<String, Predicate<PaymentMethod>>) queryFilter.invoke(null,
                List.of(PaymentMethod.REVOLUT, PaymentMethod.SEPA, PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("US").test(PaymentMethod.REVOLUT));
        assertFalse(filter.apply("US").test(PaymentMethod.SEPA));
        assertFalse(filter.apply("US").test(PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("DE").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("JP").test(PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("Revolut").test(PaymentMethod.REVOLUT));
    }

    @Test
    public void testTorFilesAreKeptWhenShutdownFails() throws Exception {
        NetworkNode node = mock(NetworkNode.class);
        TorSetup torSetup = mock(TorSetup.class);
        TorNetworkSettingsWindow window = new TorNetworkSettingsWindow(mock(Preferences.class), node, torSetup);
        Runnable success = mock(Runnable.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        ArgumentCaptor<ErrorMessageHandler> shutdownFailure = ArgumentCaptor.forClass(ErrorMessageHandler.class);
        Method cleanTorDir = TorNetworkSettingsWindow.class.getDeclaredMethod("cleanTorDir", Runnable.class, ErrorMessageHandler.class);
        cleanTorDir.setAccessible(true);
        cleanTorDir.invoke(window, success, failure);
        verify(node).shutDown(any(Runnable.class), shutdownFailure.capture());

        shutdownFailure.getValue().handleErrorMessage("Tor shutdown is still in progress");
        verifyNoInteractions(torSetup);
        verify(success, never()).run();
        verify(failure).handleErrorMessage(Res.get("torNetworkSettingWindow.deleteFiles.shutdownFailed"));
    }

    @Test
    public void testTorFilesAreDeletedAfterSuccessfulShutdownAndGracePeriod() throws Exception {
        NetworkNode node = mock(NetworkNode.class);
        TorSetup torSetup = mock(TorSetup.class);
        TorNetworkSettingsWindow window = new TorNetworkSettingsWindow(mock(Preferences.class), node, torSetup);
        Runnable success = mock(Runnable.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        ArgumentCaptor<Runnable> shutdownComplete = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> delayedCleanup = ArgumentCaptor.forClass(Runnable.class);
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runAfter(delayedCleanup.capture(), eq(3L))).thenReturn(mock(Timer.class));
            Method cleanTorDir = TorNetworkSettingsWindow.class.getDeclaredMethod("cleanTorDir", Runnable.class, ErrorMessageHandler.class);
            cleanTorDir.setAccessible(true);
            cleanTorDir.invoke(window, success, failure);
            verify(node).shutDown(shutdownComplete.capture(), any(ErrorMessageHandler.class));
            shutdownComplete.getValue().run();
            verifyNoInteractions(torSetup);
            delayedCleanup.getValue().run();
            verify(torSetup).cleanupTorFiles(success, failure);
        }
    }

    @Test
    public void testStoppedUITimerIgnoresCallbackBeforeFxStopRuns() {
        for (boolean periodic : new boolean[]{false, true}) {
            try (MockedStatic<Platform> platform = mockStatic(Platform.class);
                 MockedStatic<FxTimer> fxTimers = mockStatic(FxTimer.class);
                 MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
                FxTimer fxTimer = mock(FxTimer.class);
                Runnable action = mock(Runnable.class);
                ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
                platform.when(Platform::isFxApplicationThread).thenReturn(true);
                if (periodic) fxTimers.when(() -> FxTimer.createPeriodic(any(Duration.class), callback.capture())).thenReturn(fxTimer);
                else fxTimers.when(() -> FxTimer.create(any(Duration.class), callback.capture())).thenReturn(fxTimer);
                UITimer timer = new UITimer();
                if (periodic) timer.runPeriodically(Duration.ofSeconds(1), action);
                else timer.runLater(Duration.ofSeconds(1), action);

                platform.when(Platform::isFxApplicationThread).thenReturn(false);
                timer.stop();
                userThread.verify(() -> UserThread.execute(any(Runnable.class)));
                verify(fxTimer, never()).stop();
                callback.getValue().run();
                verify(action, never()).run();
            }
        }
    }

    @BeforeEach
    public void setup() {
        Locale.setDefault(new Locale("en", "US"));
        GlobalSettings.setLocale(new Locale("en", "US"));
        Res.setBaseCurrencyCode("BTC");
        Res.setBaseCurrencyName("Bitcoin");
    }

    @Test
    public void testFiatTextUsesSharedPrecisionRoundingAndCurrencyLabels() {
        Preferences preferences = mock(Preferences.class);
        PriceFeedService feed = mock(PriceFeedService.class);
        when(preferences.getPreferredTradeCurrency()).thenReturn(new TraditionalCurrency("USD"));
        when(feed.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 200.1, System.currentTimeMillis(), true));
        assertEquals("≈ 30.02 USD", GUIUtil.getFiatText(new BigInteger("150000000000"), feed, preferences));

        BigInteger amount = new BigInteger("1000000000000");
        when(preferences.getPreferredTradeCurrency()).thenReturn(new CryptoCurrency("BTC", "Bitcoin"));
        when(feed.getMarketPrice("BTC")).thenReturn(new MarketPrice("BTC", 0.00123456789, System.currentTimeMillis(), true));
        assertEquals("≈ 0.00123457 BTC", GUIUtil.getFiatText(amount, feed, preferences));

        when(preferences.getPreferredTradeCurrency()).thenReturn(new CryptoCurrency("USDT-ERC20", "Tether USD"));
        when(feed.getMarketPrice("USDT-ERC20")).thenReturn(new MarketPrice("USDT", 200.1, System.currentTimeMillis(), true));
        assertEquals("≈ 200.10 USDT", GUIUtil.getFiatText(amount, feed, preferences));
    }

    @Test
    public void testFiatTextOmitsUnavailableInputsAndQuotes() {
        Preferences preferences = mock(Preferences.class);
        PriceFeedService feed = mock(PriceFeedService.class);
        BigInteger amount = new BigInteger("1000000000000");
        assertNull(GUIUtil.getFiatText(null, feed, preferences));
        assertNull(GUIUtil.getFiatText(BigInteger.ZERO, feed, preferences));
        assertNull(GUIUtil.getFiatText(BigInteger.ONE.negate(), feed, preferences));
        assertNull(GUIUtil.getFiatText(amount, null, preferences));
        assertNull(GUIUtil.getFiatText(amount, feed, null));
        assertNull(GUIUtil.getFiatText(amount, feed, preferences));
        verifyNoInteractions(feed);

        when(preferences.getPreferredTradeCurrency()).thenReturn(new TraditionalCurrency("USD"));
        for (MarketPrice price : new MarketPrice[]{null,
                new MarketPrice("USD", 200, System.currentTimeMillis(), false),
                new MarketPrice("USD", 200, System.currentTimeMillis() - MarketPrice.MARKET_PRICE_MAX_AGE_MS, true),
                new MarketPrice("USD", Double.POSITIVE_INFINITY, System.currentTimeMillis(), true)}) {
            when(feed.getMarketPrice("USD")).thenReturn(price);
            assertNull(GUIUtil.getFiatText(amount, feed, preferences));
        }
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
