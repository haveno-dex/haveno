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

package haveno.network.p2p.peers;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.storage.messages.BroadcastMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BroadcastHandlerTest {

    @Test
    public void testSufficientlyBroadcastAfterThreeSuccessfulSends() {
        try (BroadcastFixture fixture = new BroadcastFixture(4)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            fixture.connections.forEach(connection -> when(connection.testCapability(statistics)).thenReturn(true));
            fixture.broadcast(statistics, listener);
            fixture.flush();
            fixture.succeed(0);
            fixture.succeed(1);
            verify(listener, never()).onSufficientlyBroadcast(any());
            fixture.succeed(2);
            fixture.succeed(3);
            verify(listener, times(1)).onSufficientlyBroadcast(any());
            verify(listener, never()).onNotSufficientlyBroadcast(any(Integer.class), any(Integer.class));
        }
    }

    @Test
    public void testRequestConfirmsOnlyAfterThreeSuccessfulSendsCarryingIt() {
        try (BroadcastFixture fixture = new BroadcastFixture(5)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastMessage refresh = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            // the first two peers accept only the refresh, so the third success carries the statistics to a single peer
            for (int i = 0; i < 5; i++) {
                when(fixture.connections.get(i).testCapability(refresh)).thenReturn(true);
                when(fixture.connections.get(i).testCapability(statistics)).thenReturn(i >= 2);
            }
            fixture.broadcast(refresh, null);
            fixture.broadcast(statistics, listener);
            fixture.flush();
            fixture.succeed(0);
            fixture.succeed(1);
            fixture.succeed(2);
            fixture.succeed(3);
            verify(listener, never()).onSufficientlyBroadcast(any());
            fixture.succeed(4);
            verify(listener, times(1)).onSufficientlyBroadcast(any());
        }
    }

    @Test
    public void testPeerWithTwoConnectionsCountsOnce() {
        try (BroadcastFixture fixture = new BroadcastFixture(4)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            fixture.connections.forEach(connection -> when(connection.testCapability(statistics)).thenReturn(true));
            when(fixture.connections.get(1).getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("peer0:1000")));
            fixture.broadcast(statistics, listener);
            fixture.flush();
            assertEquals(3, fixture.sends.size());
            int sentToSharedPeer = fixture.sent(0) ? 0 : 1;
            assertTrue(fixture.sent(sentToSharedPeer) && !fixture.sent(1 - sentToSharedPeer));
            fixture.succeed(sentToSharedPeer);
            fixture.succeed(2);
            verify(listener, never()).onSufficientlyBroadcast(any());
            fixture.succeed(3);
            verify(listener, times(1)).onSufficientlyBroadcast(any());
        }
    }

    @Test
    public void testPeersWithoutOurAddressDoNotCountTowardConfirmation() {
        try (BroadcastFixture fixture = new BroadcastFixture(4)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            fixture.connections.forEach(connection -> when(connection.testCapability(statistics)).thenReturn(true));
            when(fixture.connections.get(0).isPeerReadyForBroadcast()).thenReturn(false);
            fixture.broadcast(statistics, listener);
            fixture.flush();
            assertEquals(3, fixture.sends.size());
            assertFalse(fixture.sent(0));
            fixture.succeed(1);
            fixture.succeed(2);
            verify(listener, never()).onSufficientlyBroadcast(any());
            fixture.succeed(3);
            verify(listener).onSufficientlyBroadcast(any());
        }
    }

    @Test
    public void testReadyConnectionIsSelectedBeforeDeduplicatingPeer() {
        try (BroadcastFixture fixture = new BroadcastFixture(2)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            fixture.connections.forEach(connection -> when(connection.testCapability(statistics)).thenReturn(true));
            when(fixture.connections.get(0).isPeerReadyForBroadcast()).thenReturn(false);
            when(fixture.connections.get(1).getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("peer0:1000")));
            // keep the unready connection first so deduplicating before checking readiness would lose the usable one
            when(fixture.networkNode.getConfirmedConnections()).thenReturn(new LinkedHashSet<>(fixture.connections));
            fixture.broadcast(statistics, listener);
            fixture.flush();
            assertEquals(1, fixture.sends.size());
            assertFalse(fixture.sent(0));
            fixture.succeed(1);
            verify(listener).onSufficientlyBroadcast(any());
        }
    }

    @Test
    public void testSendToStoppedConnectionDoesNotConfirm() {
        try (BroadcastFixture fixture = new BroadcastFixture(3)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            fixture.connections.forEach(connection -> when(connection.testCapability(statistics)).thenReturn(true));
            fixture.broadcast(statistics, listener);
            fixture.flush();
            fixture.succeed(0);
            fixture.succeed(1);
            // the connection stopped after the send was queued, so its write was skipped although the future completed
            when(fixture.connections.get(2).isStopped()).thenReturn(true);
            fixture.succeed(2);
            verify(listener, never()).onSufficientlyBroadcast(any());
        }
    }

    @Test
    public void testFailedSendDoesNotConfirmRequestExcludedFromSuccessfulSends() {
        try (BroadcastFixture fixture = new BroadcastFixture(4)) {
            BroadcastMessage statistics = mock(BroadcastMessage.class);
            BroadcastMessage refresh = mock(BroadcastMessage.class);
            BroadcastHandler.Listener listener = mock(BroadcastHandler.Listener.class);
            // the first three peers accept only the refresh, so their successes count without carrying the statistics
            for (int i = 0; i < 4; i++) {
                when(fixture.connections.get(i).testCapability(refresh)).thenReturn(true);
                when(fixture.connections.get(i).testCapability(statistics)).thenReturn(i == 3);
            }
            fixture.broadcast(refresh, null);
            fixture.broadcast(statistics, listener);
            fixture.flush();
            fixture.succeed(0);
            fixture.succeed(1);
            fixture.succeed(2);
            fixture.fail(3);
            verify(listener, never()).onSufficientlyBroadcast(any());
        }
    }

    private static class BroadcastFixture implements AutoCloseable {
        private final NetworkNode networkNode = mock(NetworkNode.class);
        private final List<Connection> connections = new ArrayList<>();
        private final Map<Connection, SettableFuture<Connection>> sends = new HashMap<>();
        private final MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
        private final Broadcaster broadcaster;

        private BroadcastFixture(int numConnections) {
            when(networkNode.getNodeAddress()).thenReturn(new NodeAddress("local:9999"));
            for (int i = 0; i < numConnections; i++) {
                Connection connection = mock(Connection.class);
                when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("peer" + i + ":1000")));
                when(connection.isPeerReadyForBroadcast()).thenReturn(true);
                connections.add(connection);
            }
            when(networkNode.getConfirmedConnections()).thenReturn(new HashSet<>(connections));
            when(networkNode.sendMessage(any(Connection.class), any(NetworkEnvelope.class), any(ListeningExecutorService.class)))
                    .thenAnswer(invocation -> {
                        SettableFuture<Connection> send = SettableFuture.create();
                        sends.put(invocation.getArgument(0), send);
                        return send;
                    });
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenReturn(mock(Timer.class));
            userThread.when(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(0, Runnable.class).run();
                        return mock(Timer.class);
                    });
            broadcaster = new Broadcaster(networkNode, mock(PeerManager.class), 12);
        }

        private void broadcast(BroadcastMessage message, BroadcastHandler.Listener listener) {
            broadcaster.broadcast(message, networkNode.getNodeAddress(), listener);
        }

        private void flush() {
            broadcaster.flush();
        }

        private boolean sent(int index) {
            return sends.containsKey(connections.get(index));
        }

        private void succeed(int index) {
            sends.get(connections.get(index)).set(connections.get(index));
        }

        private void fail(int index) {
            sends.get(connections.get(index)).setException(new IOException("peer disconnected"));
        }

        @Override
        public void close() {
            broadcaster.shutDown(() -> {
            });
            userThread.close();
        }
    }
}
