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

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.reactfx.FxTimer;
import haveno.common.util.Utilities;
import haveno.core.app.TorSetup;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.payment.CryptoCurrencyAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountList;
import haveno.core.payment.RevolutAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.desktop.common.UITimer;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.network.p2p.network.NetworkNode;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;


public class GUIUtilTest {

    @Test
    public void testPaymentAccountExportSnapshotsAndRoundTrips(@TempDir Path dir) throws Exception {
        RevolutAccount fiat = new RevolutAccount();
        fiat.init();
        fiat.setAccountName("fiat account");
        fiat.setUsername("username");
        fiat.setSingleTradeCurrency(new TraditionalCurrency("EUR"));
        CryptoCurrencyAccount crypto = new CryptoCurrencyAccount();
        crypto.init();
        crypto.setAccountName("crypto account");
        crypto.setAddress("address");
        crypto.setSingleTradeCurrency(new CryptoCurrency("BTC", "Bitcoin"));

        try (PaymentAccountIOFixture fixture = new PaymentAccountIOFixture(dir)) {
            for (PaymentAccount account : List.of(fiat, crypto)) {
                fixture.file = dir.resolve(account == fiat ? "FiatPaymentAccounts" : "CryptoPaymentAccounts");
                byte[] expected = paymentAccountBytes(account);
                byte[] previous = "previous export".getBytes(StandardCharsets.UTF_8);
                Files.write(fixture.file, previous);
                CountDownLatch release = new CountDownLatch(1);
                Future<?> blocker = ThreadUtils.execute(() -> {
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }, "PaymentAccountIO");
                try {
                    GUIUtil.exportAccounts(new ArrayList<>(List.of(account)), fixture.file.getFileName().toString(), fixture.preferences, fixture.stage);
                    account.setAccountName("changed after export");
                    assertArrayEquals(previous, Files.readAllBytes(fixture.file));
                    assertTrue(fixture.callbacks.isEmpty());
                } finally {
                    release.countDown();
                    blocker.get(10, TimeUnit.SECONDS);
                }
                fixture.runCallback();
                assertArrayEquals(expected, Files.readAllBytes(fixture.file));
                verify(fixture.lastPopup()).feedback(Res.get("guiUtil.accountExport.savedToPath", fixture.file.toString()));
                verify(fixture.lastPopup(), never()).warning(any(String.class));
                if (Files.getFileStore(fixture.file).supportsFileAttributeView(PosixFileAttributeView.class)) {
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(fixture.file));
                }

                User user = mock(User.class);
                Thread caller = Thread.currentThread();
                AtomicReference<Collection<PaymentAccount>> imported = new AtomicReference<>();
                doAnswer(invocation -> {
                    assertSame(caller, Thread.currentThread());
                    imported.set(invocation.getArgument(0));
                    return null;
                }).when(user).addImportedPaymentAccounts(any());
                GUIUtil.importAccounts(user, fixture.file.getFileName().toString(), fixture.preferences, fixture.stage, fixture.resolver);
                verifyNoInteractions(user);
                fixture.runCallback();
                assertEquals(1, imported.get().size());
                PaymentAccount restored = imported.get().iterator().next();
                assertArrayEquals(expected, paymentAccountBytes(restored));
                when(user.getPaymentAccount(restored.getId())).thenReturn(restored);
                GUIUtil.importAccounts(user, fixture.file.getFileName().toString(), fixture.preferences, fixture.stage, fixture.resolver);
                fixture.runCallback();
                assertTrue(imported.get().isEmpty());
            }
        }
    }

    @Test
    public void testPaymentAccountSerializationFailurePreservesExport(@TempDir Path dir) throws Exception {
        try (PaymentAccountIOFixture fixture = new PaymentAccountIOFixture(dir)) {
            Files.writeString(fixture.file, "previous export");
            PaymentAccount account = mock(PaymentAccount.class);
            when(account.toProtoMessage()).thenThrow(new IllegalStateException("serialization failed"));

            GUIUtil.exportAccounts(new ArrayList<>(List.of(account)), fixture.file.getFileName().toString(), fixture.preferences, fixture.stage);

            assertEquals("previous export", Files.readString(fixture.file));
            verify(fixture.lastPopup()).warning(Res.get("guiUtil.accountExport.saveFailed", fixture.file.toString(), "serialization failed"));
            verify(fixture.lastPopup(), never()).feedback(any(String.class));
        }
    }

    @Test
    public void testPaymentAccountReplacementFailurePreservesExport(@TempDir Path dir) throws Exception {
        try (PaymentAccountIOFixture fixture = new PaymentAccountIOFixture(dir)) {
            Files.writeString(fixture.file, "previous export");
            PaymentAccount account = mock(PaymentAccount.class);
            when(account.toProtoMessage()).thenReturn(protobuf.PaymentAccount.getDefaultInstance());
            AtomicReference<MockedStatic<Files>> workerFiles = new AtomicReference<>();
            ThreadUtils.execute(() -> {
                workerFiles.set(mockStatic(Files.class, CALLS_REAL_METHODS));
                workerFiles.get().when(() -> Files.move(any(Path.class), eq(fixture.file), eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)))
                        .thenThrow(new IOException("replacement failed"));
            }, "PaymentAccountIO").get(10, TimeUnit.SECONDS);
            try {
                GUIUtil.exportAccounts(new ArrayList<>(List.of(account)), fixture.file.getFileName().toString(), fixture.preferences, fixture.stage);
                fixture.runCallback();
                assertEquals("previous export", Files.readString(fixture.file));
                verify(fixture.lastPopup()).warning(Res.get("guiUtil.accountExport.saveFailed", fixture.file.toString(), "replacement failed"));
                verify(fixture.lastPopup(), never()).feedback(any(String.class));
                try (Stream<Path> remaining = Files.list(dir)) {
                    assertEquals(List.of(fixture.file), remaining.toList());
                }
            } finally {
                ThreadUtils.execute(() -> workerFiles.get().close(), "PaymentAccountIO").get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void testInvalidPaymentAccountImportsPreserveFileAndUser(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream wrongType = new ByteArrayOutputStream();
        protobuf.PersistableEnvelope.newBuilder().setNavigationPath(protobuf.NavigationPath.getDefaultInstance()).build().writeDelimitedTo(wrongType);
        try (PaymentAccountIOFixture fixture = new PaymentAccountIOFixture(dir)) {
            for (byte[] bytes : List.of(new byte[0], new byte[]{127, 1, 2, 3}, wrongType.toByteArray())) {
                Files.write(fixture.file, bytes);
                User user = mock(User.class);

                GUIUtil.importAccounts(user, fixture.file.getFileName().toString(), fixture.preferences, fixture.stage, fixture.resolver);
                fixture.runCallback();

                assertArrayEquals(bytes, Files.readAllBytes(fixture.file));
                verifyNoInteractions(user);
                verify(fixture.lastPopup()).warning(any(String.class));
                verify(fixture.lastPopup(), never()).feedback(any(String.class));
                try (Stream<Path> remaining = Files.list(dir)) {
                    assertEquals(List.of(fixture.file), remaining.toList());
                }
            }
        }
    }

    @Test
    public void testPaymentAccountImportParsesOffUserThread(@TempDir Path dir) throws Exception {
        try (PaymentAccountIOFixture fixture = new PaymentAccountIOFixture(dir)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new PaymentAccountList(List.of()).toProtoMessage().writeDelimitedTo(bytes);
            Files.write(fixture.file, bytes.toByteArray());
            Thread caller = Thread.currentThread();
            CorePersistenceProtoResolver resolver = mock(CorePersistenceProtoResolver.class);
            when(resolver.fromProto(any(protobuf.PersistableEnvelope.class))).thenAnswer(invocation -> {
                assertNotSame(caller, Thread.currentThread());
                return new PaymentAccountList(List.of());
            });
            User user = mock(User.class);

            GUIUtil.importAccounts(user, fixture.file.getFileName().toString(), fixture.preferences, fixture.stage, resolver);
            verifyNoInteractions(user);
            fixture.runCallback();

            verify(resolver).fromProto(any(protobuf.PersistableEnvelope.class));
            verify(user).addImportedPaymentAccounts(Set.of());
        }
    }

    private static byte[] paymentAccountBytes(PaymentAccount account) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new PaymentAccountList(List.of(account)).toProtoMessage().writeDelimitedTo(bytes);
        return bytes.toByteArray();
    }

    private static class PaymentAccountIOFixture implements AutoCloseable {
        private Path file;
        private final Preferences preferences = mock(Preferences.class);
        private final Stage stage = mock(Stage.class);
        private final CorePersistenceProtoResolver resolver = new CorePersistenceProtoResolver(() -> null, () -> null, null);
        private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        private final Executor originalExecutor = UserThread.getExecutor();
        private final MockedConstruction<DirectoryChooser> directories;
        private final MockedConstruction<FileChooser> files;
        private final MockedConstruction<Popup> popups;

        private PaymentAccountIOFixture(Path dir) {
            file = dir.resolve("FiatPaymentAccounts");
            when(preferences.getDirectoryChooserPath()).thenReturn(dir.toString());
            directories = mockConstruction(DirectoryChooser.class, (chooser, context) -> when(chooser.showDialog(any())).thenReturn(dir.toFile()));
            files = mockConstruction(FileChooser.class, (chooser, context) -> when(chooser.showOpenDialog(any())).thenAnswer(invocation -> file.toFile()));
            popups = mockConstruction(Popup.class, withSettings().defaultAnswer(RETURNS_SELF));
            UserThread.setExecutor(callbacks::add);
        }

        private void runCallback() throws InterruptedException {
            Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
        }

        private Popup lastPopup() {
            return popups.constructed().get(popups.constructed().size() - 1);
        }

        @Override
        public void close() {
            ThreadUtils.shutDown("PaymentAccountIO", 10000L);
            UserThread.setExecutor(originalExecutor);
            popups.close();
            files.close();
            directories.close();
        }
    }

    @Test
    public void testRestoreRepaintsCurrentSceneWithoutChangingColor() {
        for (Color color : List.of(Color.BLACK, Color.WHITE, Color.TRANSPARENT)) {
            Stage stage = mock(Stage.class);
            Scene scene = mock(Scene.class);
            SimpleBooleanProperty iconified = new SimpleBooleanProperty();
            SimpleObjectProperty<Paint> fill = new SimpleObjectProperty<>(color);
            AtomicInteger invalidations = new AtomicInteger();
            AtomicInteger changes = new AtomicInteger();
            fill.addListener((InvalidationListener) observable -> invalidations.incrementAndGet());
            fill.addListener((observable, oldValue, newValue) -> changes.incrementAndGet());
            when(stage.iconifiedProperty()).thenReturn(iconified);
            when(stage.isIconified()).thenAnswer(invocation -> iconified.get());
            when(stage.isShowing()).thenReturn(true);
            when(scene.fillProperty()).thenReturn(fill);
            when(scene.getFill()).thenAnswer(invocation -> fill.get());
            doAnswer(invocation -> {
                fill.set(invocation.getArgument(0));
                return null;
            }).when(scene).setFill(any(Paint.class));
            ArgumentCaptor<Runnable> repaint = ArgumentCaptor.forClass(Runnable.class);
            try (MockedStatic<Platform> platform = mockStatic(Platform.class)) {
                GUIUtil.repaintOnRestore(stage);
                iconified.set(true);
                platform.verifyNoInteractions();
                iconified.set(false);
                platform.verify(() -> Platform.runLater(repaint.capture()));
                verifyNoInteractions(scene);
                when(stage.getScene()).thenReturn(scene); // use the scene present when the deferred repaint runs
                repaint.getValue().run();
                assertEquals(color, fill.get());
                assertNotSame(color, fill.get());
                assertEquals(1, invalidations.get());
                assertEquals(0, changes.get());
            }
        }
    }

    @Test
    public void testRestoreSkipsUnavailableWindowAndBoundFill() {
        for (String state : List.of("hidden", "minimized", "no scene", "bound fill")) {
            Stage stage = mock(Stage.class);
            Scene scene = mock(Scene.class);
            SimpleBooleanProperty iconified = new SimpleBooleanProperty(true);
            SimpleObjectProperty<Paint> fill = new SimpleObjectProperty<>(Color.BLACK);
            when(stage.iconifiedProperty()).thenReturn(iconified);
            when(stage.isIconified()).thenAnswer(invocation -> iconified.get());
            when(stage.isShowing()).thenReturn(!state.equals("hidden"));
            when(stage.getScene()).thenReturn(state.equals("no scene") ? null : scene);
            when(scene.fillProperty()).thenReturn(fill);
            when(scene.getFill()).thenReturn(Color.BLACK);
            if (state.equals("bound fill")) fill.bind(new SimpleObjectProperty<>(Color.BLACK));
            ArgumentCaptor<Runnable> repaint = ArgumentCaptor.forClass(Runnable.class);
            try (MockedStatic<Platform> platform = mockStatic(Platform.class)) {
                GUIUtil.repaintOnRestore(stage);
                iconified.set(false);
                platform.verify(() -> Platform.runLater(repaint.capture()));
                if (state.equals("minimized")) iconified.set(true);
                repaint.getValue().run();
                verify(scene, never()).setFill(any(Paint.class));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPaymentMethodNameSearchDoesNotMatchPartialCountries() throws Exception {
        List<PaymentMethod> methods = PaymentMethod.paymentMethods.stream().filter(PaymentMethod::isTraditional).toList();
        Method queryFilter = TraditionalAccountsView.class.getDeclaredMethod("getPaymentMethodQueryFilter", List.class);
        queryFilter.setAccessible(true);
        Function<String, Predicate<PaymentMethod>> filter = (Function<String, Predicate<PaymentMethod>>) queryFilter.invoke(null, methods);
        for (String query : List.of("Western", "western", "WESTERN", "Wester", "Western Union")) {
            assertEquals(List.of(PaymentMethod.WESTERN_UNION_ID),
                    methods.stream().filter(filter.apply(query)).map(PaymentMethod::getId).toList(), query);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCountrySearchIncludesGlobalPaymentMethods() throws Exception {
        Method queryFilter = TraditionalAccountsView.class.getDeclaredMethod("getPaymentMethodQueryFilter", List.class);
        queryFilter.setAccessible(true);
        Function<String, Predicate<PaymentMethod>> filter = (Function<String, Predicate<PaymentMethod>>) queryFilter.invoke(null,
                List.of(PaymentMethod.REVOLUT, PaymentMethod.SEPA, PaymentMethod.JAPAN_BANK,
                        PaymentMethod.WESTERN_UNION, PaymentMethod.CASH_DEPOSIT, PaymentMethod.US_POSTAL_MONEY_ORDER));
        assertTrue(filter.apply("US").test(PaymentMethod.REVOLUT));
        assertFalse(filter.apply("US").test(PaymentMethod.SEPA));
        assertFalse(filter.apply("US").test(PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("DE").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("JP").test(PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("Revolut").test(PaymentMethod.REVOLUT));
        assertTrue(filter.apply("Japan").test(PaymentMethod.JAPAN_BANK));
        assertTrue(filter.apply("Japan").test(PaymentMethod.REVOLUT));
        assertFalse(filter.apply("Japan").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("Germ").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("Germ").test(PaymentMethod.REVOLUT));
        assertTrue(filter.apply("Western Sahara").test(PaymentMethod.REVOLUT));
        assertFalse(filter.apply("Western Sahara").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("Sahara").test(PaymentMethod.REVOLUT));
        assertTrue(filter.apply("USD").test(PaymentMethod.WESTERN_UNION));
        assertFalse(filter.apply("USD").test(PaymentMethod.SEPA));
        assertTrue(filter.apply("Euro").test(PaymentMethod.SEPA));
        assertFalse(filter.apply("no such payment method").test(PaymentMethod.REVOLUT));
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
    public void testBrowserPromptRequiresActionOrCopiesUrlOnClose() {
        String target = "https://haveno.exchange";
        String preferenceKey = "warnOpenURLWhenTorEnabled";
        for (boolean open : List.of(false, true)) {
            Runnable closeHandler = mock(Runnable.class);
            try (MockedStatic<DontShowAgainLookup> lookup = mockStatic(DontShowAgainLookup.class);
                    MockedStatic<Utilities> utilities = mockStatic(Utilities.class);
                    MockedConstruction<Popup> popups = mockConstruction(Popup.class, withSettings().defaultAnswer(RETURNS_SELF))) {
                lookup.when(() -> DontShowAgainLookup.showAgain(preferenceKey)).thenReturn(true);
                GUIUtil.openWebPage(target, false, closeHandler);
                assertEquals(1, popups.constructed().size());
                Popup popup = popups.constructed().get(0);
                verify(popup).nested();
                verify(popup).show();
                utilities.verifyNoInteractions();
                verifyNoInteractions(closeHandler);
                ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
                if (open) {
                    verify(popup).onAction(action.capture());
                    action.getValue().run();
                    lookup.verify(() -> DontShowAgainLookup.dontShowAgain(preferenceKey, true));
                    utilities.verify(() -> Utilities.openURI(URI.create(target)));
                    utilities.verify(() -> Utilities.copyToClipboard(any()), never());
                    verifyNoInteractions(closeHandler);
                } else {
                    verify(popup).onClose(action.capture());
                    action.getValue().run();
                    utilities.verify(() -> Utilities.copyToClipboard(target));
                    utilities.verify(() -> Utilities.openURI(any()), never());
                    lookup.verify(() -> DontShowAgainLookup.dontShowAgain(preferenceKey, true), never());
                    verify(closeHandler).run();
                }
            }
        }
    }

    @Test
    public void testAcknowledgedBrowserWarningPreservesReferrerAndCloseHandler() {
        Preferences preferences = mock(Preferences.class);
        GUIUtil.setPreferences(preferences);
        when(preferences.getUserLanguage()).thenReturn("en");
        Runnable closeHandler = mock(Runnable.class);
        try (MockedStatic<DontShowAgainLookup> lookup = mockStatic(DontShowAgainLookup.class);
                MockedStatic<Utilities> utilities = mockStatic(Utilities.class);
                MockedConstruction<Popup> popups = mockConstruction(Popup.class)) {
            lookup.when(() -> DontShowAgainLookup.showAgain("warnOpenURLWhenTorEnabled")).thenReturn(false);
            GUIUtil.openWebPage("https://haveno.network", true, closeHandler);
            utilities.verify(() -> Utilities.openURI(URI.create(
                    "https://haveno.network?utm_source=desktop-client&utm_medium=in-app-link&utm_campaign=language_en")));
            verify(closeHandler).run();
            GUIUtil.openWebPage("https://www.github.com", false);
            utilities.verify(() -> Utilities.openURI(URI.create("https://www.github.com")));
            assertTrue(popups.constructed().isEmpty());
        }
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
