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

package bisq.network.p2p;

import bisq.common.ClockWatcher;
import bisq.common.file.CorruptedStorageFileHandler;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.persistable.PersistenceProtoResolver;
import bisq.network.p2p.network.*;
import bisq.network.p2p.peers.PeerManager;
import bisq.network.p2p.peers.peerexchange.PeerList;
import bisq.network.p2p.seed.SeedNodeRepository;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.*;

public class MockNode {
    @Getter
    public NetworkNode networkNode;
    @Getter
    public PeerManager peerManager;
    @Getter
    public Set<Connection> connections;
    @Getter
    public int maxConnections;

    public MockNode(int maxConnections) throws IOException {
        this.maxConnections = maxConnections;
        networkNode = mock(NetworkNode.class);
        File storageDir = Files.createTempDirectory("storage").toFile();
        PersistenceManager<PeerList> persistenceManager = new PersistenceManager<>(storageDir, mock(PersistenceProtoResolver.class), mock(CorruptedStorageFileHandler.class));
        peerManager = new PeerManager(networkNode, mock(SeedNodeRepository.class), new ClockWatcher(), maxConnections, persistenceManager);
        connections = new HashSet<>();
        when(networkNode.getAllConnections()).thenReturn(connections);
    }

    public void addInboundConnection(Connection.PeerType peerType) {
        InboundConnection inboundConnection = mock(InboundConnection.class);
        when(inboundConnection.getPeerType()).thenReturn(peerType);
        Statistic statistic = mock(Statistic.class);
        long lastActivityTimestamp = System.currentTimeMillis();
        when(statistic.getLastActivityTimestamp()).thenReturn(lastActivityTimestamp);
        when(inboundConnection.getStatistic()).thenReturn(statistic);
        doNothing().when(inboundConnection).run();
        connections.add(inboundConnection);
    }

    public void addOutboundConnection(Connection.PeerType peerType) {
        OutboundConnection outboundConnection = mock(OutboundConnection.class);
        when(outboundConnection.getPeerType()).thenReturn(peerType);
        Statistic statistic = mock(Statistic.class);
        long lastActivityTimestamp = System.currentTimeMillis();
        when(statistic.getLastActivityTimestamp()).thenReturn(lastActivityTimestamp);
        when(outboundConnection.getStatistic()).thenReturn(statistic);
        doNothing().when(outboundConnection).run();
        connections.add(outboundConnection);
    }
}
