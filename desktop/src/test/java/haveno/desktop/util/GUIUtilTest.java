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

import haveno.common.UserThread;
import haveno.common.reactfx.FxTimer;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.desktop.common.UITimer;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class GUIUtilTest {

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
