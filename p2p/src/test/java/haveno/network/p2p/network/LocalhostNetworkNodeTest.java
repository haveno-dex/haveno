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

import com.google.common.util.concurrent.SettableFuture;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TorNode created. Took 6 sec.
// Hidden service created. Took 40-50 sec.
// Connection establishment takes about 4 sec.

public class LocalhostNetworkNodeTest {
    private static final Logger log = LoggerFactory.getLogger(LocalhostNetworkNodeTest.class);

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
}
