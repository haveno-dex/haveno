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

package haveno.core.user;

import com.google.protobuf.UnknownFieldSet;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreContext;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrKeyImagePoller;
import haveno.core.api.XmrLocalNode;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.P2PService;
import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroDaemonInfo;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodesSetupPreferences;
import haveno.core.user.Preferences.NetworkSettings;
import haveno.core.user.Preferences.UseTorForXmr;
import haveno.core.xmr.nodes.XmrNodes.MoneroNodesOption;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PreferencesTest {

    private Preferences preferences;
    private Config config;
    private PersistenceManager persistenceManager;
    private XmrNodes xmrNodes;

    @BeforeEach
    public void setUp() {
        final Locale en_US = new Locale("en", "US");
        Locale.setDefault(en_US);
        GlobalSettings.setLocale(en_US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        persistenceManager = mock(PersistenceManager.class);
        config = new Config();
        preferences = new Preferences(
                persistenceManager, config, null, null);
        xmrNodes = new XmrNodes();
        XmrLocalNode xmrLocalNode = new XmrLocalNode(config, preferences, xmrNodes);
    }

    @Test
    public void testExplicitNativeWalletOptionAppliesBeforePreferencesAreRead() {
        assertFalse(preferences.isUseNativeXmrWallet());
        preferences.setUseNativeXmrWallet(true);
        assertTrue(preferences.isUseNativeXmrWallet());
        Preferences pinned = new Preferences(persistenceManager, new Config("--useNativeXmrWallet=true"), null, null);
        assertTrue(pinned.isUseNativeXmrWallet());
        pinned.setUseNativeXmrWallet(false);
        assertTrue(pinned.isUseNativeXmrWallet());
    }

    @Test
    public void testConcurrentFallbackDoesNotOverwriteSavedNetworkSettings() throws Exception {
        PreferencesPayload payload = spy(new PreferencesPayload());
        payload.setMoneroNodesOptionOrdinal(MoneroNodesOption.PUBLIC.ordinal());
        payload.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        preferences.setPrefPayload(payload);
        preferences.captureEffectiveNetworkSettings();
        CountDownLatch fallbackWriting = new CountDownLatch(1);
        CountDownLatch releaseFallback = new CountDownLatch(1);
        CountDownLatch saveStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            fallbackWriting.countDown();
            assertTrue(releaseFallback.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(payload).setMoneroNodesOptionOrdinal(MoneroNodesOption.PROVIDED.ordinal());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NetworkSettings saved = new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.OFF, "custom.example:18081");
        try {
            Future<?> fallback = executor.submit(() -> preferences.setEffectiveMoneroNodesOption(MoneroNodesOption.PROVIDED));
            assertTrue(fallbackWriting.await(5, TimeUnit.SECONDS));
            Future<?> saving = executor.submit(() -> {
                saveStarted.countDown();
                preferences.applyNodeSettings(saved.nodes(), saved.customNodes());
                preferences.setTorForNextStart(saved.tor());
            });
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            try {
                saving.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // saving waits for the in-progress fallback write
            }
            releaseFallback.countDown();
            fallback.get(5, TimeUnit.SECONDS);
            saving.get(5, TimeUnit.SECONDS);
            assertEquals(saved, preferences.getNetworkSettings());
            assertEquals(MoneroNodesOption.CUSTOM, preferences.getEffectiveNetworkSettings().nodes());
            assertEquals(UseTorForXmr.ON, preferences.getEffectiveNetworkSettings().tor());
        } finally {
            releaseFallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testSavedNetworkSettingsBecomeEffectiveAfterReload() throws IOException {
        PreferencesPayload payload = PreferencesPayload.fromProto(protobuf.PreferencesPayload.newBuilder()
                .setBlockChainExplorerMainNet((protobuf.BlockChainExplorer) new BlockChainExplorer("main", "https://example.com/").toProtoMessage())
                .setBlockChainExplorerTestNet((protobuf.BlockChainExplorer) new BlockChainExplorer("test", "https://example.com/").toProtoMessage())
                .build(), null);
        payload.setUserLanguage("en");
        payload.setUserCountry(CountryUtil.getDefaultCountry());
        payload.setPreferredTradeCurrency(new TraditionalCurrency("USD"));
        payload.setMoneroNodesOptionOrdinal(MoneroNodesOption.CUSTOM.ordinal());
        payload.setMoneroNodes("old.example:18081");
        payload.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        PersistenceManager<PreferencesPayload> manager = mock(PersistenceManager.class);
        Preferences original = new Preferences(manager, config, null, xmrNodes);
        doAnswer(invocation -> {
            Consumer<PreferencesPayload> resultHandler = invocation.getArgument(1);
            resultHandler.accept(payload);
            return null;
        }).when(manager).readPersisted(eq("PreferencesPayload"), any(), any());
        original.readPersisted(() -> {});
        original.captureEffectiveNetworkSettings();
        NetworkSettings saved = new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.OFF, "new.example:18089");
        original.applyNodeSettings(saved.nodes(), saved.customNodes());
        original.setTorForNextStart(saved.tor());
        assertEquals(UseTorForXmr.ON, original.getUseTorForXmr());

        protobuf.PersistableEnvelope persisted = (protobuf.PersistableEnvelope) payload.toProtoMessage();
        PreferencesPayload reloadedPayload = PreferencesPayload.fromProto(
                protobuf.PersistableEnvelope.parseFrom(persisted.toByteArray()).getPreferencesPayload(), null);
        PersistenceManager<PreferencesPayload> reloadedManager = mock(PersistenceManager.class);
        Preferences reloaded = new Preferences(reloadedManager, config, null, xmrNodes);
        doAnswer(invocation -> {
            Consumer<PreferencesPayload> resultHandler = invocation.getArgument(1);
            resultHandler.accept(reloadedPayload);
            return null;
        }).when(reloadedManager).readPersisted(eq("PreferencesPayload"), any(), any());
        reloaded.readPersisted(() -> {});
        reloaded.captureEffectiveNetworkSettings();
        assertEquals(saved, reloaded.getEffectiveNetworkSettings());
        assertFalse(reloaded.isProxyApplied(true));
    }

    @Test
    public void testTorWaitsForNextStartWhileNodesApplyImmediately() {
        preferences.setMoneroNodesOptionOrdinal(MoneroNodesOption.PUBLIC.ordinal());
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        preferences.setMoneroNodes("old.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.OFF);

        assertEquals(UseTorForXmr.OFF, preferences.getNetworkSettings().tor());
        assertEquals(UseTorForXmr.ON, preferences.getUseTorForXmr());
        assertTrue(preferences.isProxyApplied(false));
        assertTrue(preferences.isProxyApplied(true));
        preferences.applyNodeSettings(MoneroNodesOption.CUSTOM, "new.example:18089");
        assertEquals(new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.OFF, "new.example:18089"), preferences.getNetworkSettings());
        assertEquals(new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.ON, "new.example:18089"), preferences.getEffectiveNetworkSettings());
        assertTrue(new XmrNodesSetupPreferences(preferences).isUseCustomNodes());
        assertEquals("http://new.example:18089", new XmrNodesSetupPreferences(preferences).selectPreferredNodes(xmrNodes).get(0).getUri());

        preferences.setTorForNextStart(UseTorForXmr.ON);
        assertEquals(preferences.getNetworkSettings(), preferences.getEffectiveNetworkSettings());
    }

    @Test
    public void testChangingSourcePreservesCustomNodesAndPendingTor() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.AFTER_SYNC.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.CUSTOM, "custom.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.ON);
        preferences.applyNodeSettings(MoneroNodesOption.PUBLIC, preferences.getEffectiveNetworkSettings().customNodes());
        assertEquals(new NetworkSettings(MoneroNodesOption.PUBLIC, UseTorForXmr.ON, "custom.example:18081"), preferences.getNetworkSettings());
        assertEquals(new NetworkSettings(MoneroNodesOption.PUBLIC, UseTorForXmr.AFTER_SYNC, "custom.example:18081"), preferences.getEffectiveNetworkSettings());
        assertFalse(preferences.isProxyApplied(false));
        assertTrue(preferences.isProxyApplied(true));
    }

    @Test
    public void testRuntimeFallbackPersistsAndPreservesPendingTor() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.OFF.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.CUSTOM, "custom.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.ON);
        for (MoneroNodesOption option : List.of(MoneroNodesOption.PROVIDED, MoneroNodesOption.PUBLIC)) {
            preferences.setEffectiveMoneroNodesOption(option);
            assertEquals(new NetworkSettings(option, UseTorForXmr.ON, "custom.example:18081"), preferences.getNetworkSettings());
            assertEquals(new NetworkSettings(option, UseTorForXmr.OFF, "custom.example:18081"), preferences.getEffectiveNetworkSettings());
        }
    }

    @Test
    public void testFilterStillOverridesPublicNodes() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.OFF.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.PUBLIC, "custom.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.ON);
        preferences.setPublicMoneroNodesBlocked(true);
        assertEquals(new NetworkSettings(MoneroNodesOption.PROVIDED, UseTorForXmr.ON, "custom.example:18081"), preferences.getNetworkSettings());
        assertEquals(new NetworkSettings(MoneroNodesOption.PROVIDED, UseTorForXmr.OFF, "custom.example:18081"), preferences.getEffectiveNetworkSettings());
    }

    @Test
    public void testFilterPreservesCustomNodesAndPendingTor() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.CUSTOM, "custom.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.OFF);
        preferences.setPublicMoneroNodesBlocked(true);
        assertEquals(new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.OFF, "custom.example:18081"), preferences.getNetworkSettings());
        assertEquals(new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.ON, "custom.example:18081"), preferences.getEffectiveNetworkSettings());
    }

    @Test
    public void testBlockedPublicNodeEditDoesNotChangeSettings() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.CUSTOM, "custom.example:18081");
        preferences.setTorForNextStart(UseTorForXmr.OFF);
        preferences.setPublicMoneroNodesBlocked(true);
        NetworkSettings saved = preferences.getNetworkSettings();
        NetworkSettings effective = preferences.getEffectiveNetworkSettings();

        assertFalse(preferences.applyNodeSettingsUnlessBlocked(MoneroNodesOption.PUBLIC, "other.example:18081"));
        assertEquals(saved, preferences.getNetworkSettings());
        assertEquals(effective, preferences.getEffectiveNetworkSettings());
        for (MoneroNodesOption option : List.of(MoneroNodesOption.CUSTOM, MoneroNodesOption.PROVIDED)) {
            assertTrue(preferences.applyNodeSettingsUnlessBlocked(option, "other.example:18081"));
            assertEquals(new NetworkSettings(option, UseTorForXmr.OFF, "other.example:18081"), preferences.getNetworkSettings());
            assertEquals(new NetworkSettings(option, UseTorForXmr.ON, "other.example:18081"), preferences.getEffectiveNetworkSettings());
        }
    }

    @Test
    public void testClearingFilterAllowsPublicNodeEdits() {
        preferences.applyNodeSettings(MoneroNodesOption.PUBLIC, "custom.example:18081");
        preferences.setPublicMoneroNodesBlocked(true);
        preferences.setPublicMoneroNodesBlocked(false);

        assertEquals(MoneroNodesOption.PROVIDED, preferences.getEffectiveNetworkSettings().nodes());
        assertTrue(preferences.applyNodeSettingsUnlessBlocked(MoneroNodesOption.PUBLIC, "custom.example:18081"));
        assertEquals(MoneroNodesOption.PUBLIC, preferences.getNetworkSettings().nodes());
        assertEquals(MoneroNodesOption.PUBLIC, preferences.getEffectiveNetworkSettings().nodes());
    }

    @Test
    public void testNodeRollbackPreservesNewFilterAndPendingTor() {
        preferences.setUseTorForXmrOrdinal(UseTorForXmr.ON.ordinal());
        preferences.applyNodeSettings(MoneroNodesOption.PUBLIC, "old.example:18081");
        NetworkSettings previous = preferences.getEffectiveNetworkSettings();
        assertTrue(preferences.applyNodeSettingsUnlessBlocked(MoneroNodesOption.CUSTOM, "new.example:18081"));
        preferences.setTorForNextStart(UseTorForXmr.OFF);
        preferences.setPublicMoneroNodesBlocked(true);

        preferences.restoreNodeSettings(previous);

        assertEquals(new NetworkSettings(MoneroNodesOption.PROVIDED, UseTorForXmr.OFF, "old.example:18081"), preferences.getNetworkSettings());
        assertEquals(new NetworkSettings(MoneroNodesOption.PROVIDED, UseTorForXmr.ON, "old.example:18081"), preferences.getEffectiveNetworkSettings());
        preferences.setPublicMoneroNodesBlocked(false);
        preferences.restoreNodeSettings(previous);
        assertEquals(new NetworkSettings(MoneroNodesOption.PUBLIC, UseTorForXmr.OFF, "old.example:18081"), preferences.getNetworkSettings());
        assertEquals(previous, preferences.getEffectiveNetworkSettings());
    }

    @Test
    public void testPublicNodeEditWaitsForConcurrentFilterUpdate() throws Exception {
        PreferencesPayload payload = spy(new PreferencesPayload());
        payload.setMoneroNodesOptionOrdinal(MoneroNodesOption.PUBLIC.ordinal());
        payload.setMoneroNodes("old.example:18081");
        preferences.setPrefPayload(payload);
        preferences.captureEffectiveNetworkSettings();
        CountDownLatch filterWriting = new CountDownLatch(1);
        CountDownLatch releaseFilter = new CountDownLatch(1);
        CountDownLatch editStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            filterWriting.countDown();
            assertTrue(releaseFilter.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(payload).setMoneroNodesOptionOrdinal(MoneroNodesOption.PROVIDED.ordinal());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> filter = executor.submit(() -> preferences.setPublicMoneroNodesBlocked(true));
            assertTrue(filterWriting.await(5, TimeUnit.SECONDS));
            Future<Boolean> edit = executor.submit(() -> {
                editStarted.countDown();
                return preferences.applyNodeSettingsUnlessBlocked(MoneroNodesOption.PUBLIC, "new.example:18081");
            });
            assertTrue(editStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> edit.get(100, TimeUnit.MILLISECONDS));
            releaseFilter.countDown();
            filter.get(5, TimeUnit.SECONDS);
            assertFalse(edit.get(5, TimeUnit.SECONDS));
            assertEquals(MoneroNodesOption.PROVIDED, preferences.getNetworkSettings().nodes());
            assertEquals(MoneroNodesOption.PROVIDED, preferences.getEffectiveNetworkSettings().nodes());
            assertEquals("old.example:18081", preferences.getEffectiveNetworkSettings().customNodes());
        } finally {
            releaseFilter.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testLiveNodeSwitchRejectsFilterDuringProbe() throws Exception {
        for (MoneroNodesOption previousSource : List.of(MoneroNodesOption.CUSTOM, MoneroNodesOption.PROVIDED)) {
            try (NodeSettingsContext context = new NodeSettingsContext(previousSource)) {
                CountDownLatch probing = new CountDownLatch(1);
                CountDownLatch releaseProbe = new CountDownLatch(1);
                doAnswer(invocation -> {
                    MoneroRpcConnection candidate = spy(context.service.getConnection(context.publicUri));
                    doReturn(Map.of("result", Map.of("status", "OK", "height", BigInteger.valueOf(1000),
                            "target_height", BigInteger.ZERO, "outgoing_connections_count", BigInteger.valueOf(8))))
                            .when(candidate).sendJsonRequest("get_info");
                    context.service.removeConnection(context.publicUri);
                    context.service.addConnection(candidate);
                    probing.countDown();
                    assertTrue(releaseProbe.await(5, TimeUnit.SECONDS));
                    return candidate;
                }).when(context.service).getBestConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<?> switching = executor.submit(() -> context.service.setNodeSettings(MoneroNodesOption.PUBLIC, context.previous.getUri()));
                    assertTrue(probing.await(5, TimeUnit.SECONDS));
                    preferences.setPublicMoneroNodesBlocked(true);
                    assertFalse(context.service.requestConnectionSwitch(context.previous, null, true));
                    assertSame(context.previous, context.service.getConnection());
                    releaseProbe.countDown();
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> switching.get(5, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof IllegalStateException);
                    assertSame(context.previous, context.service.getConnection());
                    assertSame(context.previous, context.service.getMonerod().getRpcConnection());
                    assertEquals(List.of(context.previous), context.service.getConnections());
                    assertEquals(previousSource, preferences.getEffectiveNetworkSettings().nodes());
                    assertEquals(UseTorForXmr.OFF, preferences.getEffectiveNetworkSettings().tor());
                    assertEquals(UseTorForXmr.ON, preferences.getNetworkSettings().tor());
                    assertFalse(context.service.isReloadingConnections());
                    assertThrows(IllegalStateException.class, () -> context.service.setNodeSettings(MoneroNodesOption.PUBLIC, context.previous.getUri()));
                } finally {
                    releaseProbe.countDown();
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    public void testManualNodeSwitchRejectsPublicNodeAfterFilter() throws Exception {
        for (boolean filterBeforeProbe : new boolean[]{true, false}) {
            try (NodeSettingsContext context = new NodeSettingsContext(MoneroNodesOption.PUBLIC)) {
                MoneroRpcConnection publicNode = new MoneroRpcConnection(context.publicUri);
                context.service.addConnection(publicNode);
                if (filterBeforeProbe) preferences.setPublicMoneroNodesBlocked(true);
                MoneroRpcConnection checked = context.checkedConnection(publicNode);
                if (!filterBeforeProbe) preferences.setPublicMoneroNodesBlocked(true);

                assertThrows(IllegalStateException.class, () -> context.service.connectToNode(publicNode, checked, () -> true));
                assertSame(context.previous, context.service.getConnection());
                assertSame(context.previous, context.service.getMonerod().getRpcConnection());
            }
        }
    }

    @Test
    public void testManualNodeSwitchAllowsConfiguredNodes() throws Exception {
        for (MoneroNodesOption source : MoneroNodesOption.values()) {
            try (NodeSettingsContext context = new NodeSettingsContext(source)) {
                MoneroRpcConnection original = context.previous;
                if (source == MoneroNodesOption.CUSTOM) original = context.service.addCustomNode(context.publicUri);
                if (source == MoneroNodesOption.PUBLIC) {
                    original = new MoneroRpcConnection(context.publicUri);
                    context.service.addConnection(original);
                } else {
                    preferences.setPublicMoneroNodesBlocked(true);
                }
                MoneroRpcConnection checked = context.checkedConnection(original);

                context.service.connectToNode(original, checked, () -> true);

                assertSame(checked, context.service.getConnection());
                assertSame(checked, context.service.getMonerod().getRpcConnection());
            }
        }
    }

    @Test
    public void testLiveNodeCommitSerializesWithFilterWithoutLockingListeners() throws Exception {
        try (NodeSettingsContext context = new NodeSettingsContext(MoneroNodesOption.CUSTOM)) {
            CountDownLatch committing = new CountDownLatch(1);
            CountDownLatch releaseCommit = new CountDownLatch(1);
            CountDownLatch filterStarted = new CountDownLatch(1);
            AtomicBoolean listenerHeldPreferences = new AtomicBoolean(true);
            AtomicBoolean propertyListenerHeldPreferences = new AtomicBoolean(true);
            context.service.addConnectionListener(connection -> listenerHeldPreferences.set(Thread.holdsLock(preferences)));
            context.service.connectionProperty().addListener((observable, oldValue, newValue) -> propertyListenerHeldPreferences.set(Thread.holdsLock(preferences)));
            doAnswer(invocation -> {
                committing.countDown();
                assertTrue(releaseCommit.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(context.connectionList).setCurrentConnectionUri(context.publicUri);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> switching = executor.submit(() -> context.service.setNodeSettings(MoneroNodesOption.PUBLIC, context.previous.getUri()));
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                Future<?> filter = executor.submit(() -> {
                    filterStarted.countDown();
                    preferences.setPublicMoneroNodesBlocked(true);
                });
                assertTrue(filterStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> filter.get(100, TimeUnit.MILLISECONDS));
                releaseCommit.countDown();
                switching.get(5, TimeUnit.SECONDS);
                filter.get(5, TimeUnit.SECONDS);
                assertFalse(listenerHeldPreferences.get());
                assertFalse(propertyListenerHeldPreferences.get());
                assertEquals(MoneroNodesOption.PROVIDED, preferences.getEffectiveNetworkSettings().nodes());
            } finally {
                releaseCommit.countDown();
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void testCustomNodeEditsPreserveCurrentConnectionAndPendingTor() throws Exception {
        try (NodeSettingsContext context = new NodeSettingsContext(MoneroNodesOption.CUSTOM)) {
            MoneroRpcConnection added = context.service.addCustomNode(context.publicUri);
            assertEquals(context.previous.getUri() + "," + context.publicUri, preferences.getMoneroNodes());
            assertSame(context.previous, context.service.getConnection());
            assertThrows(IllegalArgumentException.class, () -> context.service.addCustomNode(context.publicUri));
            assertEquals(2, context.service.getConnections().size());

            context.service.removeCustomNode(added.getUri());

            assertEquals(context.previous.getUri(), preferences.getMoneroNodes());
            assertEquals(List.of(context.previous), context.service.getConnections());
            assertSame(context.previous, context.service.getMonerod().getRpcConnection());
            assertEquals(UseTorForXmr.ON, preferences.getNetworkSettings().tor());
            assertEquals(UseTorForXmr.OFF, preferences.getEffectiveNetworkSettings().tor());
            assertThrows(IllegalStateException.class, () -> context.service.removeCustomNode(context.previous.getUri()));
            assertEquals(context.previous.getUri(), preferences.getMoneroNodes());
            assertSame(context.previous, context.service.getConnection());
        }
    }

    @Test
    public void testRemovingActiveCustomNodeKeepsOnlyConfiguredOfflineReplacement() throws Exception {
        try (NodeSettingsContext context = new NodeSettingsContext(MoneroNodesOption.CUSTOM)) {
            MoneroRpcConnection replacement = spy(context.service.addCustomNode(context.publicUri));
            doThrow(new IllegalStateException("offline")).when(replacement).sendJsonRequest("get_info");
            context.service.removeConnection(replacement.getUri());
            context.service.addConnection(replacement);
            context.previous.setPriority(1);

            context.service.removeCustomNode(context.previous.getUri());

            assertNull(context.service.getConnection(context.previous.getUri()));
            assertEquals(List.of(replacement), context.service.getConnections());
            assertSame(replacement, context.service.getConnection());
            assertSame(replacement, context.service.getMonerod().getRpcConnection());
            assertFalse(Boolean.TRUE.equals(context.service.isConnected()));
            assertEquals(context.publicUri, preferences.getMoneroNodes());
            assertFalse(context.service.isReloadingConnections());
        }
    }

    @Test
    public void testManualNodeSwitchRejectsUnavailableUnsyncedAndReplacedCandidates() throws Exception {
        try (NodeSettingsContext context = new NodeSettingsContext(MoneroNodesOption.CUSTOM)) {
            MoneroRpcConnection added = context.service.addCustomNode(context.publicUri);
            MoneroRpcConnection checked = context.checkedConnection(added);
            checked.setOnline(false);
            assertThrows(IllegalStateException.class, () -> context.service.connectToNode(added, checked, () -> true));
            checked.setOnline(true);
            XmrConnectionService.getCachedDaemonInfo(checked).setTargetHeight(1100L);
            assertThrows(IllegalStateException.class, () -> context.service.connectToNode(added, checked, () -> true));
            XmrConnectionService.getCachedDaemonInfo(checked).setTargetHeight(0L);

            context.service.removeCustomNode(added.getUri());
            context.service.addCustomNode(added.getUri());

            assertThrows(IllegalStateException.class, () -> context.service.connectToNode(added, checked, () -> true));
            assertSame(context.previous, context.service.getConnection());
            assertSame(context.previous, context.service.getMonerod().getRpcConnection());
        }
    }

    private final class NodeSettingsContext implements AutoCloseable {
        private final XmrConnectionService previousService = HavenoUtils.xmrConnectionService;
        private final Preferences previousPreferences = HavenoUtils.preferences;
        private final String publicUri = "http://127.0.0.1:18089";
        private final MoneroRpcConnection previous = new MoneroRpcConnection("http://127.0.0.1:18081");
        private final EncryptedConnectionList connectionList;
        private final XmrConnectionService service;

        private NodeSettingsContext(MoneroNodesOption source) throws Exception {
            preferences.setUseTorForXmrOrdinal(UseTorForXmr.OFF.ordinal());
            preferences.setPublicMoneroNodesBlocked(false);
            preferences.applyNodeSettings(source, previous.getUri());
            preferences.setTorForNextStart(UseTorForXmr.ON);
            XmrNodes nodes = spy(new XmrNodes());
            doReturn(List.of(new XmrNodes.XmrNode(MoneroNodesOption.PROVIDED, null, null, "127.0.0.1", 18081, 2, null),
                    new XmrNodes.XmrNode(MoneroNodesOption.PUBLIC, null, null, "127.0.0.1", 18089, 1, null))).when(nodes).getAllXmrNodes();
            CoreAccountService account = mock(CoreAccountService.class);
            when(account.isAccountOpen()).thenReturn(true);
            connectionList = spy(new EncryptedConnectionList(mock(PersistenceManager.class), account));
            connectionList.setRefreshPeriod(-1L);
            service = spy(new XmrConnectionService(mock(P2PService.class), config, mock(CoreContext.class), preferences,
                    mock(WalletsSetup.class), account, nodes, mock(XmrLocalNode.class), connectionList, mock(Socks5ProxyProvider.class)));
            setField("isInitialized", true);
            setField("wasMonerodSynced", true);
            setField("keyImagePoller", mock(XmrKeyImagePoller.class));
            service.setConnection(previous);
            doAnswer(invocation -> {
                MoneroRpcConnection candidate = service.getConnection(publicUri);
                return candidate == null ? service.getConnections().stream().findFirst().orElse(null) : candidate;
            }).when(service).getBestConnection();
        }

        private void setField(String name, Object value) throws Exception {
            Field field = XmrConnectionService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }

        private MoneroRpcConnection checkedConnection(MoneroRpcConnection connection) {
            MoneroRpcConnection checked = new MoneroRpcConnection(connection);
            checked.setOnline(true);
            checked.setAuthenticated(true);
            checked.setAttribute("checkedSettings", preferences.getEffectiveNetworkSettings());
            MoneroDaemonInfo info = new MoneroDaemonInfo();
            info.setHeight(1000L);
            info.setTargetHeight(0L);
            checked.setAttribute("lastInfo", info);
            return checked;
        }

        @Override
        public void close() {
            service.onShutDownStarted();
            service.shutDown();
            HavenoUtils.xmrConnectionService = previousService;
            HavenoUtils.preferences = previousPreferences;
        }
    }

    @Test
    public void testLegacyTacAcceptanceDoesNotAcceptCurrentRevision() throws IOException {
        for (int legacyAcceptance : new int[]{0, 1}) {
            protobuf.PreferencesPayload legacy = protobuf.PreferencesPayload.newBuilder()
                    .setTacAccepted(true)
                    .setTacAcceptedV120(true)
                    .setUseNativeXmrWallet(true)
                    .setUnknownFields(UnknownFieldSet.newBuilder().mergeVarintField(79, legacyAcceptance).build()) // tac_accepted_v190
                    .build();
            PreferencesPayload payload = PreferencesPayload.fromProto(protobuf.PreferencesPayload.parseFrom(legacy.toByteArray()), null);
            assertEquals(legacyAcceptance, payload.getTacAcceptedVersion());
            assertNotEquals(Version.TAC_VERSION, payload.getTacAcceptedVersion());

            payload.setTacAcceptedVersion(Version.TAC_VERSION);
            protobuf.PersistableEnvelope persisted = (protobuf.PersistableEnvelope) payload.toProtoMessage();
            PreferencesPayload reloaded = PreferencesPayload.fromProto(
                    protobuf.PersistableEnvelope.parseFrom(persisted.toByteArray()).getPreferencesPayload(), null);
            assertEquals(Version.TAC_VERSION, reloaded.getTacAcceptedVersion());
            assertTrue(reloaded.isUseNativeXmrWallet());
        }
    }

    @Test
    public void testTacAcceptancePersistsDuringStartup() {
        assertEquals(0, preferences.getTacAcceptedVersion());
        preferences.setTacAcceptedVersion(Version.TAC_VERSION);
        assertEquals(Version.TAC_VERSION, preferences.getTacAcceptedVersion());
        verify(persistenceManager).forcePersistNow();
    }

    @Test
    public void testMaxPriceDistanceAcceptsZeroThroughOneHundredPercent() {
        for (double deviation : new double[]{0, 0.0001, 0.3, 1}) {
            preferences.setMaxPriceDistanceInPercent(deviation);
            assertEquals(deviation, preferences.getMaxPriceDistanceInPercent());
        }
    }

    @Test
    public void testInvalidMaxPriceDistancePreservesPreviousValue() {
        preferences.setMaxPriceDistanceInPercent(0.3);
        for (double deviation : new double[]{-0.01, -Double.MIN_VALUE, 1.0001,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> preferences.setMaxPriceDistanceInPercent(deviation));
            assertEquals(0.3, preferences.getMaxPriceDistanceInPercent());
        }
    }

    @Test
    public void testPersistedMaxPriceDistance() {
        for (double[] deviations : new double[][]{{0, 0}, {0.3, 0.3}, {1, 1}, {-0.01, 0}, {1.0001, 0},
                {Double.NaN, 0}, {Double.NEGATIVE_INFINITY, 0}, {Double.POSITIVE_INFINITY, 0}}) {
            PreferencesPayload payload = new PreferencesPayload();
            payload.setUserLanguage("en");
            payload.setUserCountry(CountryUtil.getDefaultCountry());
            payload.setPreferredTradeCurrency(new TraditionalCurrency("USD"));
            payload.setMaxPriceDistanceInPercent(deviations[0]);
            PersistenceManager<PreferencesPayload> manager = mock(PersistenceManager.class);
            Preferences reloaded = new Preferences(manager, config, null, xmrNodes);
            doAnswer(invocation -> {
                Consumer<PreferencesPayload> resultHandler = invocation.getArgument(1);
                resultHandler.accept(payload);
                return null;
            }).when(manager).readPersisted(eq("PreferencesPayload"), any(), any());

            Runnable completeHandler = mock(Runnable.class);
            reloaded.readPersisted(completeHandler);

            assertEquals(deviations[1], reloaded.getMaxPriceDistanceInPercent());
            assertEquals(deviations[1], payload.getMaxPriceDistanceInPercent());
            verify(completeHandler).run();
            verify(manager).requestPersistence();
        }
    }

    @Test
    public void testSoftwareRenderingPersistsForNextStartup() {
        String currentRenderer = System.getProperty("prism.order");
        assertFalse(preferences.isUseSoftwareRendering());
        assertFalse(StartupSettings.read(config.appDataDir)
                .getAsOptionalBoolean(CookieKey.USE_SOFTWARE_RENDERING).orElse(false));

        preferences.setCssTheme(true);
        assertTrue(preferences.setUseSoftwareRendering(true));
        Preferences reloaded = new Preferences(persistenceManager, config, null, null);
        assertTrue(reloaded.isUseSoftwareRendering());
        assertTrue(StartupSettings.read(config.appDataDir)
                .getAsOptionalBoolean(CookieKey.USE_SOFTWARE_RENDERING).orElse(false));
        assertEquals(currentRenderer, System.getProperty("prism.order"));

        preferences.setUserLanguage("de");
        assertTrue(reloaded.isUseSoftwareRendering());
        assertTrue(reloaded.setUseSoftwareRendering(false));
        assertFalse(new Preferences(persistenceManager, config, null, null).isUseSoftwareRendering());
        Cookie settings = StartupSettings.read(config.appDataDir);
        assertTrue(settings.getAsOptionalBoolean(CookieKey.CSS_THEME).orElse(false));
        assertEquals("de", settings.get(CookieKey.USER_LANGUAGE));
        assertEquals(currentRenderer, System.getProperty("prism.order"));
    }

    @Test
    public void testPreferencesIgnoresJvmPrismOrderOverride() {
        String originalRenderer = System.getProperty("prism.order");
        try {
            for (String renderer : new String[]{"es2", "sw"}) {
                System.setProperty("prism.order", renderer);

                assertFalse(preferences.isUseSoftwareRendering());
                assertTrue(preferences.setUseSoftwareRendering(true));
                assertTrue(preferences.isUseSoftwareRendering());
                assertTrue(new Preferences(persistenceManager, config, null, null).isUseSoftwareRendering());
                assertEquals(renderer, System.getProperty("prism.order"));

                assertTrue(preferences.setUseSoftwareRendering(false));
                assertFalse(preferences.isUseSoftwareRendering());
                assertFalse(new Preferences(persistenceManager, config, null, null).isUseSoftwareRendering());
                assertEquals(renderer, System.getProperty("prism.order"));
            }
        } finally {
            if (originalRenderer != null) {
                System.setProperty("prism.order", originalRenderer);
            } else {
                System.clearProperty("prism.order");
            }
        }
    }

    @Test
    public void testSoftwareRenderingReportsPersistenceFailure() throws IOException {
        Files.createDirectory(config.appDataDir.toPath().resolve("startup_settings"));

        assertFalse(preferences.setUseSoftwareRendering(true));
        assertFalse(preferences.isUseSoftwareRendering());
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
