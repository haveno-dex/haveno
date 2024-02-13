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

package haveno.network.p2p;

import haveno.common.ClockWatcher;
import haveno.common.crypto.KeyRing;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionState;
import haveno.network.p2p.network.ConnectionStatistics;
import haveno.network.p2p.network.InboundConnection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.network.OutboundConnection;
import haveno.network.p2p.network.PeerType;
import haveno.network.p2p.network.Statistic;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.peerexchange.PeerList;
import haveno.network.p2p.seed.SeedNodeRepository;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MockNode {
    @Getter
    private NetworkNode networkNode;
    @Getter
    private PeerManager peerManager;
    @Getter
    private Set<Connection> connections;
    @Getter
    private int maxConnections;
    @Getter
    private PersistenceManager<PeerList> persistenceManager;

    public MockNode(int maxConnections) throws IOException {
        this.maxConnections = maxConnections;
        networkNode = mock(NetworkNode.class);
        File storageDir = Files.createTempDirectory("storage").toFile();
        persistenceManager = new PersistenceManager<>(storageDir, mock(PersistenceProtoResolver.class), mock(CorruptedStorageFileHandler.class), mock(KeyRing.class));
        peerManager = new PeerManager(networkNode, mock(SeedNodeRepository.class), new ClockWatcher(), persistenceManager, maxConnections);
        connections = new HashSet<>();
        when(networkNode.getAllConnections()).thenReturn(connections);
    }

    public void addInboundConnection(PeerType peerType) {
        InboundConnection inboundConnection = mock(InboundConnection.class);

        ConnectionStatistics connectionStatistics = mock(ConnectionStatistics.class);
        when(connectionStatistics.getConnectionCreationTimeStamp()).thenReturn(0L);
        when(inboundConnection.getConnectionStatistics()).thenReturn(connectionStatistics);

        ConnectionState connectionState = mock(ConnectionState.class);
        when(connectionState.getPeerType()).thenReturn(peerType);
        when(inboundConnection.getConnectionState()).thenReturn(connectionState);

        Statistic statistic = mock(Statistic.class);
        long lastActivityTimestamp = System.currentTimeMillis();
        when(statistic.getLastActivityTimestamp()).thenReturn(lastActivityTimestamp);
        when(inboundConnection.getStatistic()).thenReturn(statistic);
        doNothing().when(inboundConnection).run();
        connections.add(inboundConnection);
    }

    public void addOutboundConnection(PeerType peerType) {
        OutboundConnection outboundConnection = mock(OutboundConnection.class);

        ConnectionStatistics connectionStatistics = mock(ConnectionStatistics.class);
        when(connectionStatistics.getConnectionCreationTimeStamp()).thenReturn(0L);
        when(outboundConnection.getConnectionStatistics()).thenReturn(connectionStatistics);

        ConnectionState connectionState = mock(ConnectionState.class);
        when(connectionState.getPeerType()).thenReturn(peerType);
        when(outboundConnection.getConnectionState()).thenReturn(connectionState);

        Statistic statistic = mock(Statistic.class);
        long lastActivityTimestamp = System.currentTimeMillis();
        when(statistic.getLastActivityTimestamp()).thenReturn(lastActivityTimestamp);
        when(outboundConnection.getStatistic()).thenReturn(statistic);
        doNothing().when(outboundConnection).run();
        connections.add(outboundConnection);
    }
}
