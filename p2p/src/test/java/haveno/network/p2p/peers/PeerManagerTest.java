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

package haveno.network.p2p.peers;

import com.google.common.base.Ticker;
import com.google.common.util.concurrent.SettableFuture;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Capabilities;
import haveno.network.p2p.MockNode;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.InboundConnection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.network.OutboundConnection;
import haveno.network.p2p.network.PeerType;
import haveno.network.p2p.network.RuleViolation;
import haveno.network.p2p.network.Statistic;
import haveno.network.p2p.peers.getdata.RequestDataManager;
import haveno.network.p2p.peers.keepalive.KeepAliveManager;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import haveno.network.p2p.peers.peerexchange.Peer;
import haveno.network.p2p.peers.peerexchange.PeerExchangeManager;
import haveno.network.p2p.seed.SeedNodeRepository;
import haveno.network.p2p.storage.P2PDataStorage;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PeerManagerTest {
    private MockNode node;
    private int maxConnectionsPeer;
    private int maxConnectionsNonDirect;

    @BeforeEach
    public void setUp() throws IOException {
        node = new MockNode(2);
        maxConnectionsPeer = Math.max(4, (int) Math.round(node.getMaxConnections() * 1.3));
        maxConnectionsNonDirect = Math.max(8, (int) Math.round(node.getMaxConnections() * 1.7));
    }

    @AfterEach
    public void tearDown() {
        node.getPersistenceManager().shutdown();
    }

    @Test
    public void testFailedPeerIsNotReintroducedByReports() {
        NodeAddress address = new NodeAddress("failed.onion:9999");
        PeerManager manager = node.getPeerManager();
        for (int i = 0; i < 8; i++) {
            manager.addToReportedPeers(Set.of(new Peer(address, null)), mock(Connection.class), new Capabilities());
            assertEquals(i, manager.getPersistedPeers().iterator().next().getFailedConnectionAttempts());
            OutboundConnection connection = outboundConnection(address, 0);
            manager.onDisconnect(CloseConnectionReason.NO_PROTO_BUFFER_ENV, connection);
            manager.onDisconnect(CloseConnectionReason.NO_PROTO_BUFFER_ENV, connection);
            assertEquals(i == 7, manager.isPeerUnavailable(address));
        }
        assertTrue(manager.getPersistedPeers().isEmpty());
        manager.addToReportedPeers(Set.of(new Peer(address, null)), mock(Connection.class), new Capabilities());

        assertTrue(manager.getPersistedPeers().isEmpty());
        assertTrue(manager.getReportedPeers().isEmpty());
        assertFalse(manager.isWrongNetworkPeer(address));
    }

    @Test
    public void testFailedPeerCooldownExpires() throws IOException {
        AtomicLong now = new AtomicLong();
        Ticker ticker = mock(Ticker.class);
        when(ticker.read()).thenAnswer(invocation -> now.get());
        try (MockedStatic<Ticker> tickerClass = mockStatic(Ticker.class)) {
            tickerClass.when(Ticker::systemTicker).thenReturn(ticker);
            node.getPeerManager().shutDown();
            node.getPersistenceManager().shutdown();
            node = new MockNode(2);
            PeerManager manager = node.getPeerManager();
            NodeAddress address = new NodeAddress("failed.onion:9999");
            reportFailingPeer(manager, address);
            OutboundConnection connection = outboundConnection(address, 0);
            manager.onDisconnect(CloseConnectionReason.RESET, connection);
            now.set(TimeUnit.MINUTES.toNanos(59));
            manager.onDisconnect(CloseConnectionReason.RESET, connection);
            assertTrue(manager.isPeerUnavailable(address));
            now.set(TimeUnit.HOURS.toNanos(1));
            assertFalse(manager.isPeerUnavailable(address));
            manager.addToReportedPeers(Set.of(new Peer(address, null)), mock(Connection.class), new Capabilities());
            assertEquals(1, manager.getPersistedPeers().size());
        }
    }

    @Test
    public void testInboundTrafficCannotClearCooldownButOutboundTrafficCan() {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("failed.onion:9999");
        reportFailingPeer(manager, address);
        manager.onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 0));
        InboundConnection inbound = mock(InboundConnection.class);
        when(inbound.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        manager.onDisconnect(CloseConnectionReason.RESET, inbound);
        assertTrue(manager.isPeerUnavailable(address));

        manager.onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 1));
        assertFalse(manager.isPeerUnavailable(address));
    }

    @Test
    public void testIntendedClosesAndInboundFaultsDoNotSuppressPeers() {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("peer.onion:9999");
        reportFailingPeer(manager, address);
        manager.onDisconnect(CloseConnectionReason.APP_SHUT_DOWN, outboundConnection(address, 0));
        InboundConnection inbound = mock(InboundConnection.class);
        when(inbound.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        when(inbound.getRuleViolation()).thenReturn(RuleViolation.WRONG_NETWORK_ID);
        manager.onDisconnect(CloseConnectionReason.RULE_VIOLATION, inbound);

        assertFalse(manager.isPeerUnavailable(address));
        assertEquals(7, manager.getPersistedPeers().iterator().next().getFailedConnectionAttempts());
    }

    @Test
    public void testSilentFailuresDoNotSuppressSeedNodes() {
        PeerManager manager = spy(node.getPeerManager());
        NodeAddress address = new NodeAddress("seed.onion:9999");
        when(manager.isSeedNode(address)).thenReturn(true);
        reportFailingPeer(manager, address);
        manager.onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 0));

        assertFalse(manager.isPeerUnavailable(address));
        assertTrue(manager.getPersistedPeers().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = CloseConnectionReason.class, names = {"NO_PROTO_BUFFER_ENV", "SOCKET_TIMEOUT", "RESET"})
    public void testPartialResponseDoesNotCountAsSilentFailure(CloseConnectionReason reason) {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("peer.onion:9999");
        reportFailingPeer(manager, address);
        OutboundConnection connection = outboundConnection(address, 0);
        when(connection.getLastReadTimestamp()).thenReturn(1L);

        manager.onDisconnect(reason, connection);

        assertFalse(manager.isPeerUnavailable(address));
        assertEquals(7, manager.getPersistedPeers().iterator().next().getFailedConnectionAttempts());
    }

    @Test
    public void testOfflineDialFailuresPreservePeerCandidates() {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("peer.onion:9999");
        reportFailingPeer(manager, address);
        for (int i = 0; i < 20; i++) manager.handleConnectionFault(address);

        assertFalse(manager.isPeerUnavailable(address));
        assertEquals(7, manager.getPersistedPeers().iterator().next().getFailedConnectionAttempts());
        assertEquals(1, manager.getReportedPeers().size());
    }

    @Test
    public void testWrongNetworkPeerStaysBlockedAfterOutboundTraffic() {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("wrong.onion:9999");
        OutboundConnection connection = outboundConnection(address, 1);
        when(connection.getRuleViolation()).thenReturn(RuleViolation.WRONG_NETWORK_ID);
        manager.onDisconnect(CloseConnectionReason.RULE_VIOLATION, connection);
        manager.onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 1));

        assertTrue(manager.isPeerUnavailable(address));
        assertTrue(manager.isWrongNetworkPeer(address));
    }

    @Test
    public void testLivePeersRequireValidatedMessagesAndExcludeSuppressedPeers() {
        PeerManager manager = node.getPeerManager();
        NodeAddress address = new NodeAddress("peer.onion:9999");
        OutboundConnection connection = outboundConnection(address, 0);
        when(connection.getCapabilities()).thenReturn(new Capabilities());
        when(node.getNetworkNode().getConfirmedConnections()).thenReturn(Set.of(connection));
        assertTrue(manager.getLivePeers(null).isEmpty());

        when(connection.getStatistic().getReceivedBytes()).thenReturn(1L);
        assertTrue(manager.getLivePeers(null).isEmpty());
        when(connection.getStatistic().getLastReceivedMessageTimestamp()).thenReturn(1L);
        assertEquals(1, manager.getLivePeers(null).size());
        reportFailingPeer(manager, address);
        manager.onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 0));
        assertTrue(manager.getLivePeers(null).isEmpty());
    }

    @Test
    public void testDelayedPeerExchangeRechecksPeerEligibility() {
        try (MockedStatic<ThreadUtils> threadUtils = mockStatic(ThreadUtils.class);
             MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            });
            NodeAddress address = new NodeAddress("peer.onion:9999");
            when(node.getNetworkNode().getNodeAddress()).thenReturn(new NodeAddress("self.onion:9999"));
            PeerExchangeManager exchange = new PeerExchangeManager(node.getNetworkNode(), mock(SeedNodeRepository.class), node.getPeerManager());
            try {
                exchange.requestReportedPeersFromSeedNodes(address);
                ArgumentCaptor<Runnable> delayedSend = ArgumentCaptor.forClass(Runnable.class);
                threadUtils.verify(() -> ThreadUtils.runAfterRandomDelay(delayedSend.capture(), anyLong(), anyLong(), any(TimeUnit.class)));
                reportFailingPeer(node.getPeerManager(), address);
                node.getPeerManager().onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 0));
                delayedSend.getValue().run();

                userThread.verify(() -> UserThread.execute(any(Runnable.class)));
                verify(node.getNetworkNode(), never()).sendMessage(eq(address), any());
            } finally {
                exchange.shutDown();
            }
        }
    }

    @Test
    public void testDelayedDataRequestRechecksPeerEligibility() {
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            NodeAddress address = new NodeAddress("peer.onion:9999");
            when(node.getNetworkNode().nodeAddressProperty()).thenReturn(new SimpleObjectProperty<>());
            SeedNodeRepository seeds = mock(SeedNodeRepository.class);
            when(seeds.getSeedNodeAddresses()).thenReturn(List.of(address));
            P2PDataStorage storage = mock(P2PDataStorage.class);
            RequestDataManager requests = new RequestDataManager(node.getNetworkNode(), seeds, storage, node.getPeerManager());
            requests.setListener(mock(RequestDataManager.Listener.class));
            try {
                requests.requestPreliminaryData();
                ArgumentCaptor<Runnable> delayedSend = ArgumentCaptor.forClass(Runnable.class);
                userThread.verify(() -> UserThread.runAfter(delayedSend.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)));
                reportFailingPeer(node.getPeerManager(), address);
                node.getPeerManager().onDisconnect(CloseConnectionReason.RESET, outboundConnection(address, 0));
                delayedSend.getValue().run();

                verify(storage, never()).buildPreliminaryGetDataRequest(anyInt());
                verify(node.getNetworkNode(), never()).sendMessage(eq(address), any());
            } finally {
                requests.shutDown();
            }
        }
    }

    private static void reportFailingPeer(PeerManager manager, NodeAddress address) {
        Peer peer = new Peer(address, null);
        peer.setFailedConnectionAttempts(7);
        manager.addToReportedPeers(Set.of(peer), mock(Connection.class), new Capabilities());
    }

    private static OutboundConnection outboundConnection(NodeAddress address, long receivedBytes) {
        OutboundConnection connection = mock(OutboundConnection.class);
        when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        Statistic statistic = mock(Statistic.class);
        when(statistic.getReceivedBytes()).thenReturn(receivedBytes);
        when(connection.getStatistic()).thenReturn(statistic);
        when(connection.tryAccountPeerFault()).thenReturn(true, false);
        return connection;
    }

    @Test
    public void testCheckMaxConnectionsNotExceeded() {
        for (int i = 0; i < 2; i++) {
            node.addInboundConnection(PeerType.PEER);
        }
        assertEquals(2, node.getNetworkNode().getAllConnections().size());

        assertFalse(node.getPeerManager().checkMaxConnections());

        node.getNetworkNode().getAllConnections().forEach(connection ->
                verify(connection, never()).shutDown(eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN), isA(Runnable.class)));
    }

    @Test
    public void testCheckMaxConnectionsExceededWithInboundPeers() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            node.addInboundConnection(PeerType.PEER);
        }
        assertEquals(3, node.getNetworkNode().getAllConnections().size());
        List<Connection> inboundSortedPeerConnections = node.getNetworkNode().getAllConnections().stream()
                .filter(e -> e instanceof InboundConnection)
                .filter(e -> e.getConnectionState().getPeerType() == PeerType.PEER)
                .filter(e -> !e.isStopped())
                .sorted(Comparator.comparingLong(o -> o.getStatistic().getLastActivityTimestamp()))
                .collect(Collectors.toList());
        Connection oldestConnection = inboundSortedPeerConnections.remove(0);

        assertTrue(node.getPeerManager().checkMaxConnections());
        // Need to wait because the shutDownCompleteHandler calls
        // checkMaxConnections on the user thread after a delay
        Thread.sleep(500);

        verify(oldestConnection, times(1)).shutDown(
                eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                isA(Runnable.class));
        inboundSortedPeerConnections.forEach(connection ->
                verify(connection, never()).shutDown(
                        eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                        isA(Runnable.class)));
    }

    @Test
    public void testCheckMaxConnectionsPeerLimitNotExceeded() {
        for (int i = 0; i < maxConnectionsPeer; i++) {
            node.addOutboundConnection(PeerType.PEER);
        }
        assertEquals(maxConnectionsPeer, node.getNetworkNode().getAllConnections().size());

        assertFalse(node.getPeerManager().checkMaxConnections());

        node.getNetworkNode().getAllConnections().forEach(connection ->
                verify(connection, never()).shutDown(eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN), isA(Runnable.class)));
    }

    @Test
    public void testCheckMaxConnectionsPeerLimitExceeded() throws InterruptedException {
        for (int i = 0; i < maxConnectionsPeer + 1; i++) {
            node.addOutboundConnection(PeerType.PEER);
        }
        assertEquals(maxConnectionsPeer + 1, node.getNetworkNode().getAllConnections().size());
        List<Connection> sortedPeerConnections = node.getNetworkNode().getAllConnections().stream()
                .filter(e -> e.getConnectionState().getPeerType() == PeerType.PEER)
                .filter(e -> !e.isStopped())
                .sorted(Comparator.comparingLong(o -> o.getStatistic().getLastActivityTimestamp()))
                .collect(Collectors.toList());
        Connection oldestConnection = sortedPeerConnections.remove(0);

        assertTrue(node.getPeerManager().checkMaxConnections());
        // Need to wait because the shutDownCompleteHandler calls
        // checkMaxConnections on the user thread after a delay
        Thread.sleep(500);

        verify(oldestConnection, times(1)).shutDown(
                eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                isA(Runnable.class));
        sortedPeerConnections.forEach(connection ->
                verify(connection, never()).shutDown(
                        eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                        isA(Runnable.class)));
    }

    @Test
    public void testCheckMaxConnectionsNonDirectLimitNotExceeded() {
        for (int i = 0; i < maxConnectionsNonDirect; i++) {
            node.addOutboundConnection(PeerType.INITIAL_DATA_EXCHANGE);
        }
        assertEquals(maxConnectionsNonDirect, node.getNetworkNode().getAllConnections().size());

        assertFalse(node.getPeerManager().checkMaxConnections());

        node.getNetworkNode().getAllConnections().forEach(connection ->
                verify(connection, never()).shutDown(eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN), isA(Runnable.class)));
    }

    @Test
    @Disabled
    public void testCheckMaxConnectionsNonDirectLimitExceeded() throws InterruptedException {
        for (int i = 0; i < maxConnectionsNonDirect + 1; i++) {
            node.addOutboundConnection(PeerType.INITIAL_DATA_EXCHANGE);
        }
        assertEquals(maxConnectionsNonDirect + 1, node.getNetworkNode().getAllConnections().size());
        List<Connection> sortedPeerConnections = node.getNetworkNode().getAllConnections().stream()
                .filter(e -> e.getConnectionState().getPeerType() != PeerType.PEER)
                .filter(e -> e.getConnectionState().getPeerType() == PeerType.INITIAL_DATA_EXCHANGE)
                .sorted(Comparator.comparingLong(o -> o.getStatistic().getLastActivityTimestamp()))
                .collect(Collectors.toList());
        Connection oldestConnection = sortedPeerConnections.remove(0);

        assertTrue(node.getPeerManager().checkMaxConnections());
        // Need to wait because the shutDownCompleteHandler calls
        // checkMaxConnections on the user thread after a delay
        Thread.sleep(500);

        //TODO it reports "Wanted but not invoked:" but when debugging into it it is called. So seems to be some
        // mock setup issue
        verify(oldestConnection, times(1)).shutDown(
                eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                isA(Runnable.class));
        sortedPeerConnections.forEach(connection ->
                verify(connection, never()).shutDown(
                        eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN),
                        isA(Runnable.class)));
    }

    @Test
    public void testCheckMaxConnectionsExceededWithOutboundSeeds() {
        for (int i = 0; i < 3; i++) {
            node.addOutboundConnection(PeerType.INITIAL_DATA_EXCHANGE);
        }
        assertEquals(3, node.getNetworkNode().getAllConnections().size());

        assertFalse(node.getPeerManager().checkMaxConnections());

        node.getNetworkNode().getAllConnections().forEach(connection ->
                verify(connection, never()).shutDown(eq(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN), isA(Runnable.class)));
    }

    @Test
    public void testKeepAliveClosesConnectionWhenPongIsMissing() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(230_000);
            fixture.verifyNoPeerFault();
            fixture.advanceTime(10_000);
            fixture.timeout.run();

            verify(fixture.connection, times(1)).shutDown(CloseConnectionReason.SOCKET_TIMEOUT);
            verify(fixture.peerManager, times(1)).handleConnectionFault(fixture.connection);
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            verify(fixture.timeoutTimer, atLeastOnce()).stop();
        }
    }

    @Test
    public void testKeepAliveAcceptsPongBeforeSendCompletes() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.messageListener.onMessage(new Pong(fixture.ping.getNonce()), fixture.connection);
            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(240_000);

            verify(fixture.statistic).setRoundTripTime(anyInt());
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            fixture.verifyNoPeerFault();
            verify(fixture.timeoutTimer).stop();

            fixture.scan.run();
            fixture.sendPing.run();
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveIgnoresWrongPong() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.set(fixture.connection);
            fixture.messageListener.onMessage(new Pong(fixture.ping.getNonce() + 1), fixture.connection);
            fixture.messageListener.onMessage(new Pong(fixture.ping.getNonce()), mock(Connection.class));
            fixture.advanceTime(240_000);

            verify(fixture.statistic, never()).setRoundTripTime(anyInt());
            verify(fixture.connection).shutDown(CloseConnectionReason.SOCKET_TIMEOUT);
        }
    }

    @Test
    public void testKeepAliveCancelsPendingSendOnDisconnect() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.manager.onDisconnect(CloseConnectionReason.SOCKET_CLOSED, fixture.connection);
            fixture.sendFuture.setException(new IOException("closed"));
            fixture.advanceTime(240_000);
            fixture.messageListener.onMessage(new Pong(fixture.ping.getNonce()), fixture.connection);

            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            fixture.verifyNoPeerFault();
            verify(fixture.statistic, never()).setRoundTripTime(anyInt());
            verify(fixture.timeoutTimer).stop();
        }
    }

    @Test
    public void testKeepAliveBoundsPendingSendAndIgnoresLateFailure() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.advanceTime(230_000);
            fixture.verifyNoPeerFault();
            fixture.advanceTime(10_000);
            fixture.sendFuture.setException(new IOException("closed after timeout"));

            verify(fixture.connection, times(1)).shutDown(CloseConnectionReason.SEND_MSG_TIMEOUT);
            verify(fixture.connection, never()).shutDown(CloseConnectionReason.SEND_MSG_FAILURE);
        }
    }

    @Test
    public void testKeepAliveGivesQueuedPingFullReplyWindow() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.advanceTime(230_000);
            Runnable oldTimeout = fixture.timeout;
            fixture.sendFuture.set(fixture.connection);
            fixture.now += 10_000;
            oldTimeout.run();
            fixture.timeout.run();
            fixture.advanceTime(220_000);
            fixture.verifyNoPeerFault();
            fixture.advanceTime(10_000);

            verify(fixture.connection).shutDown(CloseConnectionReason.SOCKET_TIMEOUT);
            verify(fixture.networkNode, times(1)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveLeavesSendFailureToConnection() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.setException(new IOException("send failed"));
            fixture.advanceTime(240_000);

            fixture.verifyNoPeerFault();
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            verify(fixture.timeoutTimer).stop();
        }
    }

    @Test
    public void testKeepAliveRetriesAfterLocalExecutorRejection() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.setException(new RejectedExecutionException("send pool full"));
            fixture.advanceTime(240_000);
            when(fixture.networkNode.sendMessage(eq(fixture.connection), any(Ping.class))).thenReturn(SettableFuture.create());
            fixture.scan.run();
            fixture.sendPing.run();

            fixture.verifyNoPeerFault();
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveHandlesSynchronousExecutorRejection() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            when(fixture.networkNode.sendMessage(eq(fixture.connection), any(Ping.class)))
                    .thenThrow(new RejectedExecutionException("send pool full"));
            fixture.startPing();
            fixture.advanceTime(240_000);
            fixture.scan.run();
            fixture.sendPing.run();

            fixture.verifyNoPeerFault();
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveUsesReceivedTrafficEvenWithRecentWrites() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            when(fixture.statistic.getLastActivityAge()).thenReturn(0L);
            fixture.startPing();
            verify(fixture.networkNode).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveSkipsRecentlyReceivingAndStoppedConnections() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.manager.start();
            when(fixture.statistic.getLastReceivedMessageAge()).thenReturn(0L);
            fixture.scan.run();
            when(fixture.statistic.getLastReceivedMessageAge()).thenReturn(120_000L);
            when(fixture.connection.isStopped()).thenReturn(true);
            fixture.scan.run();

            verify(fixture.networkNode, never()).sendMessage(eq(fixture.connection), any(Ping.class));
            fixture.userThread.verify(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)), never());
        }
    }

    @Test
    public void testKeepAlivePreservesIncomingTransferUntilProgressStops() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(230_000);
            when(fixture.connection.getLastReadTimestamp()).thenReturn(fixture.now);
            fixture.advanceTime(240_000 - 10_000);
            fixture.verifyNoPeerFault();
            verify(fixture.networkNode, times(1)).sendMessage(eq(fixture.connection), any(Ping.class));

            fixture.advanceTime(10_000);
            verify(fixture.connection).shutDown(CloseConnectionReason.SOCKET_TIMEOUT);
        }
    }

    @Test
    public void testKeepAlivePreservesIncomingProgressWhileSendIsPending() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.advanceTime(230_000);
            when(fixture.connection.getLastReadTimestamp()).thenReturn(fixture.now);
            fixture.advanceTime(230_000);
            fixture.verifyNoPeerFault();

            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(230_000);
            fixture.verifyNoPeerFault();
            fixture.messageListener.onMessage(new Pong(fixture.ping.getNonce()), fixture.connection);
            fixture.advanceTime(10_000);
            fixture.verifyNoPeerFault();
        }
    }

    @Test
    public void testKeepAliveRetiresPendingProbeAfterPause() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.now += 90_000;
            fixture.timeout.run();
            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(240_000);

            fixture.verifyNoPeerFault();
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            fixture.scan.run();
            fixture.sendPing.run();
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveRetiresReplyProbeWhenWakeJustMissesDeadline() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.set(fixture.connection);
            fixture.advanceTime(210_000);
            fixture.now += 31_000;
            fixture.timeout.run();
            fixture.manager.onAwakeFromStandby();

            fixture.verifyNoPeerFault();
            fixture.scan.run();
            fixture.sendPing.run();
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
        }
    }

    @Test
    public void testKeepAliveCancelsProbeWhenWakeListenerRunsFirst() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.sendFuture.set(fixture.connection);
            fixture.now += 300_000;
            fixture.manager.onAwakeFromStandby();
            fixture.timeout.run();

            fixture.verifyNoPeerFault();
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
        }
    }

    @Test
    public void testKeepAliveRetiresProbeAfterClockMovesBackwards() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.now -= 60_000;
            fixture.timeout.run();
            fixture.sendFuture.set(fixture.connection);

            fixture.verifyNoPeerFault();
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
        }
    }

    @Test
    public void testKeepAliveDoesNotFaultAlreadyStoppedConnection() {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            when(fixture.connection.isStopped()).thenReturn(true);
            fixture.advanceTime(240_000);
            fixture.sendFuture.setException(new IOException("socket already closed"));

            fixture.verifyNoPeerFault();
            verify(fixture.connection).removeMessageListener(fixture.messageListener);
            verify(fixture.timeoutTimer).stop();
        }
    }

    @ValueSource(booleans = {false, true})
    @ParameterizedTest(name = "{index}: Test with sendFailure={0}")
    public void testKeepAlivePreservesReplacementAfterQueuedCallback(boolean sendFailure) {
        try (KeepAliveFixture fixture = new KeepAliveFixture()) {
            fixture.startPing();
            fixture.queueConnectionTasks = true;
            fixture.now += 300_000;

            // Queue the old callback before cancellation, then install a replacement before either runs.
            if (sendFailure) fixture.sendFuture.setException(new IOException("late send failure"));
            else fixture.timeout.run();
            fixture.manager.onAwakeFromStandby();
            fixture.scan.run();
            fixture.drainConnectionTasks();

            when(fixture.networkNode.sendMessage(eq(fixture.connection), any(Ping.class))).thenReturn(SettableFuture.create());
            fixture.sendPing.run();
            fixture.drainConnectionTasks();
            MessageListener replacementListener = fixture.messageListener;
            fixture.scan.run();
            fixture.drainConnectionTasks();

            fixture.userThread.verify(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)), times(2));
            verify(fixture.networkNode, times(2)).sendMessage(eq(fixture.connection), any(Ping.class));
            fixture.verifyNoPeerFault();
            fixture.manager.shutDown();
            fixture.drainConnectionTasks();
            verify(fixture.connection).removeMessageListener(replacementListener);
        }
    }

    private static class KeepAliveFixture implements AutoCloseable {
        private final NetworkNode networkNode = mock(NetworkNode.class);
        private final PeerManager peerManager = mock(PeerManager.class);
        private final Connection connection = mock(OutboundConnection.class);
        private final Statistic statistic = mock(Statistic.class);
        private final Clock clock = mock(Clock.class);
        private final Timer timeoutTimer = mock(Timer.class);
        private final SettableFuture<Connection> sendFuture = SettableFuture.create();
        private final MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
        private final MockedStatic<ThreadUtils> threadUtils = mockStatic(ThreadUtils.class);
        private final KeepAliveManager manager;
        private final ArrayDeque<Runnable> connectionTasks = new ArrayDeque<>();
        private boolean queueConnectionTasks;
        private Runnable scan;
        private Runnable sendPing;
        private Runnable timeout;
        private MessageListener messageListener;
        private Ping ping;
        private long now = 1_000_000;

        private KeepAliveFixture() {
            when(clock.millis()).thenAnswer(invocation -> now);
            threadUtils.when(() -> ThreadUtils.execute(any(Runnable.class), anyString())).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(0);
                if (queueConnectionTasks && Connection.THREAD_ID.equals(invocation.getArgument(1))) connectionTasks.add(task);
                else task.run();
                return CompletableFuture.completedFuture(null);
            });
            userThread.when(() -> UserThread.runPeriodically(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                scan = invocation.getArgument(0);
                return mock(Timer.class);
            });
            userThread.when(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                sendPing = invocation.getArgument(0);
                return mock(Timer.class);
            });
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
                timeout = invocation.getArgument(0);
                long delayMs = invocation.getArgument(1);
                assertTrue(delayMs > 0 && delayMs <= 10_000L);
                return timeoutTimer;
            });
            when(connection.getUid()).thenReturn("keepalive-test");
            when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.empty());
            when(connection.getStatistic()).thenReturn(statistic);
            when(statistic.roundTripTimeProperty()).thenReturn(new SimpleIntegerProperty());
            when(statistic.getLastReceivedMessageAge()).thenReturn(120_000L);
            when(networkNode.getConfirmedConnections()).thenReturn(Set.of(connection));
            when(peerManager.getMaxConnections()).thenReturn(12);
            doAnswer(invocation -> {
                messageListener = invocation.getArgument(0);
                return null;
            }).when(connection).addMessageListener(any(MessageListener.class));
            when(networkNode.sendMessage(eq(connection), any(Ping.class))).thenAnswer(invocation -> {
                ping = invocation.getArgument(1);
                return sendFuture;
            });
            manager = new KeepAliveManager(networkNode, peerManager, clock);
        }

        private void startPing() {
            manager.start();
            scan.run();
            sendPing.run();
        }

        private void advanceTime(long elapsedMs) {
            while (elapsedMs > 0) {
                long stepMs = Math.min(10_000, elapsedMs);
                now += stepMs;
                elapsedMs -= stepMs;
                timeout.run();
            }
        }

        private void verifyNoPeerFault() {
            verify(connection, never()).shutDown(any(CloseConnectionReason.class));
            verify(peerManager, never()).handleConnectionFault(connection);
        }

        private void drainConnectionTasks() {
            while (!connectionTasks.isEmpty()) connectionTasks.removeFirst().run();
        }

        @Override
        public void close() {
            manager.shutDown();
            drainConnectionTasks();
            threadUtils.close();
            userThread.close();
        }
    }
}
