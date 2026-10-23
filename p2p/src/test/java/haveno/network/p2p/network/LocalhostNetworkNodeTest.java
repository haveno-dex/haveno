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

package haveno.network.p2p.network;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import haveno.common.Timer;
import haveno.common.config.Config;
import haveno.network.utils.LeakyBucket;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TorNode created. Took 6 sec.
// Hidden service created. Took 40-50 sec.
// Connection establishment takes about 4 sec.

public class LocalhostNetworkNodeTest {
    private static final Logger log = LoggerFactory.getLogger(LocalhostNetworkNodeTest.class);

    @Test
    @SuppressWarnings("unchecked")
    public void inventoryRequestsHaveGlobalAndConnectionBurstLimits() throws Exception {
        Map<Field, Object> previous = new HashMap<>();
        for (String name : new String[]{"config", "envelopeLimitsGlobalOverrides", "envelopeLimitsGlobalDefaultManager", "envelopeLimitsGlobalUnknownPeers"}) {
            Field field = Connection.class.getDeclaredField(name);
            field.setAccessible(true);
            previous.put(field, field.get(null));
            field.set(null, name.equals("config") ? mock(Config.class) : null);
        }
        try {
            Connection connection = mock(Connection.class);
            Method initialize = Connection.class.getDeclaredMethod("initThrottlers");
            initialize.setAccessible(true);
            initialize.invoke(connection);
            for (String name : new String[]{"envelopeLimitsGlobalOverrides", "envelopeLimitsConnectionOverrides"}) {
                Field field = Connection.class.getDeclaredField(name);
                field.setAccessible(true);
                Map<String, LeakyBucket> limits = (Map<String, LeakyBucket>) field.get(connection);
                LeakyBucket bucket = limits.get("GetInventoryRequest");
                assertNotNull(bucket);
                int accepted = 0;
                for (int i = 0; i < 10000; i++) if (!bucket.isSpamming(1)) accepted++;
                assertEquals(name.contains("Global") ? 10 : 2, accepted);
            }
        } finally {
            for (Map.Entry<Field, Object> entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

    @Test
    public void testShutdownCompletesOnceAfterTimeoutAndLateConnectionClose() {
        NetworkNode node = spy(new LocalhostNetworkNode(9001, TestUtils.getNetworkProtoResolver(), null, 2));
        Connection connection = mock(Connection.class);
        doReturn(Set.of(connection)).when(node).getAllConnections();
        AtomicInteger completions = new AtomicInteger();
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> closed = ArgumentCaptor.forClass(Runnable.class);
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runAfter(timeout.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));
            node.shutDown(completions::incrementAndGet);
            verify(connection).shutDown(eq(CloseConnectionReason.APP_SHUT_DOWN), closed.capture());
            timeout.getValue().run();
            assertEquals(1, completions.get());
            closed.getValue().run();
            assertEquals(1, completions.get());
        }
    }

    @Disabled("P2P network test is outdated")
    @Test
    public void testMessage() throws InterruptedException, IOException {
        CountDownLatch msgLatch = new CountDownLatch(2);
        LocalhostNetworkNode node1 = new LocalhostNetworkNode(9001, TestUtils.getNetworkProtoResolver(), null, 12);
        node1.addMessageListener((message, connection) -> {
            log.debug("onMessage node1 " + message);
            msgLatch.countDown();
        });
        CountDownLatch startupLatch = new CountDownLatch(2);
        node1.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onTorNodeReady");
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onHiddenServiceReady");
                startupLatch.countDown();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                log.debug("onSetupFailed");
            }

            @Override
            public void onRequestCustomBridges() {
            }
        });

        LocalhostNetworkNode node2 = new LocalhostNetworkNode(9002, TestUtils.getNetworkProtoResolver(), null, 12);
        node2.addMessageListener((message, connection) -> {
            log.debug("onMessage node2 " + message);
            msgLatch.countDown();
        });
        node2.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onTorNodeReady 2");
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onHiddenServiceReady 2");
                startupLatch.countDown();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                log.debug("onSetupFailed 2");
            }

            @Override
            public void onRequestCustomBridges() {
            }
        });
        startupLatch.await();

        msgLatch.await();

        CountDownLatch shutDownLatch = new CountDownLatch(2);
        node1.shutDown(shutDownLatch::countDown);
        node2.shutDown(shutDownLatch::countDown);
        shutDownLatch.await();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSendCompletesOnUserThread(boolean connectionAppearsDuringDial) throws Exception {
        NodeAddress address = new NodeAddress("localhost", 9999);
        NetworkNode node = spy(new LocalhostNetworkNode(9001, TestUtils.getNetworkProtoResolver(), null, 2));
        Connection connection = mock(Connection.class);
        when(connection.hasPeersNodeAddress()).thenReturn(true);
        when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        when(connection.getStatistic()).thenReturn(mock(Statistic.class));
        if (connectionAppearsDuringDial) {
            doReturn(Set.of(), Set.of(connection)).when(node).getAllConnections();
            doReturn(mock(Socket.class)).when(node).createSocket(address);
        } else {
            doReturn(Set.of(connection)).when(node).getAllConnections();
        }
        Executor originalExecutor = UserThread.getExecutor();
        BlockingQueue<Runnable> userTasks = new LinkedBlockingQueue<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CountDownLatch sendAllowed = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(sendAllowed.await(5, TimeUnit.SECONDS));
            return null;
        }).when(connection).sendMessage(any(NetworkEnvelope.class));
        UserThread.setExecutor(userTasks::add);
        try {
            SettableFuture<Connection> future = node.sendMessage(address, new Ping(1, 0));
            future.addListener(() -> callbackThread.set(Thread.currentThread()), MoreExecutors.directExecutor());
            assertFalse(future.isDone());
            sendAllowed.countDown();
            while (!future.isDone()) {
                Runnable task = userTasks.poll(5, TimeUnit.SECONDS);
                assertNotNull(task);
                task.run();
            }
            assertSame(connection, future.get(5, TimeUnit.SECONDS));
            assertSame(Thread.currentThread(), callbackThread.get());
        } finally {
            sendAllowed.countDown();
            UserThread.setExecutor(originalExecutor);
            doReturn(Set.of()).when(node).getAllConnections();
            node.shutDown(null);
        }
    }

    @Test
    public void testSendPrefersReceivingConnectionOverRecentWrites() {
        NodeAddress address = new NodeAddress("localhost", 9999);
        NetworkNode node = mock(NetworkNode.class, CALLS_REAL_METHODS);
        Connection stalled = mock(OutboundConnection.class);
        Connection receiving = mock(InboundConnection.class);
        Statistic stalledStatistic = mock(Statistic.class);
        Statistic receivingStatistic = mock(Statistic.class);
        for (Connection connection : Set.of(stalled, receiving)) {
            when(connection.hasPeersNodeAddress()).thenReturn(true);
            when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        }
        when(stalled.getStatistic()).thenReturn(stalledStatistic);
        when(receiving.getStatistic()).thenReturn(receivingStatistic);
        when(stalledStatistic.getLastActivityTimestamp()).thenReturn(300L);
        when(stalledStatistic.getLastReceivedMessageTimestamp()).thenReturn(100L);
        when(receivingStatistic.getLastActivityTimestamp()).thenReturn(200L);
        when(receivingStatistic.getLastReceivedMessageTimestamp()).thenReturn(200L);
        doReturn(Set.of(stalled, receiving)).when(node).getAllConnections();
        SettableFuture<Connection> sendFuture = SettableFuture.create();
        doReturn(sendFuture).when(node).sendMessage(any(Connection.class), any(NetworkEnvelope.class));

        Ping message = new Ping(1, 0);
        assertSame(sendFuture, node.sendMessage(address, message));
        verify(node).sendMessage(receiving, message);
    }

    @Test
    public void testLocalActivityDoesNotRefreshReceivedTraffic() {
        Statistic statistic = new Statistic();
        assertEquals(0L, statistic.getLastReceivedMessageTimestamp());
        assertTrue(statistic.getLastReceivedMessageAge() >= 0);
        assertTrue(statistic.getLastReceivedMessageAge() <= System.currentTimeMillis() - statistic.getCreationDate().getTime());

        statistic.updateLastActivityTimestamp();
        assertEquals(0L, statistic.getLastReceivedMessageTimestamp());
        statistic.updateLastReceivedMessageTimestamp();
        long receivedTimestamp = statistic.getLastReceivedMessageTimestamp();
        assertTrue(receivedTimestamp > 0);
        statistic.updateLastActivityTimestamp();
        assertEquals(receivedTimestamp, statistic.getLastReceivedMessageTimestamp());
    }

    @Test
    public void testReadProgressIsRecordedBeforeAnEnvelopeCompletes() throws Exception {
        Class<?> streamClass = Class.forName(Connection.class.getName() + "$EofTrackingInputStream");
        Constructor<?> constructor = streamClass.getDeclaredConstructor(InputStream.class);
        constructor.setAccessible(true);
        Field inputField = Connection.class.getDeclaredField("protoInputStream");
        inputField.setAccessible(true);
        for (boolean bulkRead : new boolean[]{false, true}) {
            InputStream stream = (InputStream) constructor.newInstance(new ByteArrayInputStream(new byte[]{1, 2}));
            Connection connection = mock(Connection.class, CALLS_REAL_METHODS);
            inputField.set(connection, stream);
            assertEquals(0L, connection.getLastReadTimestamp());

            if (bulkRead) assertEquals(2, stream.read(new byte[2], 0, 2));
            else assertEquals(1, stream.read());
            assertTrue(connection.getLastReadTimestamp() > 0);
            if (!bulkRead) assertEquals(2, stream.read());
            long lastReadTimestamp = connection.getLastReadTimestamp();
            assertEquals(-1, stream.read());
            assertEquals(lastReadTimestamp, connection.getLastReadTimestamp());
        }
    }

}
