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

package haveno.core.network.p2p.inventory;

import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.util.Profiler;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.network.p2p.inventory.messages.GetInventoryRequest;
import haveno.core.network.p2p.inventory.messages.GetInventoryResponse;
import haveno.core.network.p2p.inventory.model.InventoryItem;
import haveno.core.network.p2p.inventory.model.RequestInfo;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.network.Statistic;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GetInventoryRequestHandler implements MessageListener {
    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final P2PDataStorage p2PDataStorage;
    private final FilterManager filterManager;
    private final int maxConnections;

    @Inject
    public GetInventoryRequestHandler(NetworkNode networkNode,
                                      PeerManager peerManager,
                                      P2PDataStorage p2PDataStorage,
                                      FilterManager filterManager,
                                      @Named(Config.MAX_CONNECTIONS) int maxConnections) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.p2PDataStorage = p2PDataStorage;
        this.filterManager = filterManager;
        this.maxConnections = maxConnections;

        this.networkNode.addMessageListener(this);
    }

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof GetInventoryRequest) {
            // Data
            GetInventoryRequest getInventoryRequest = (GetInventoryRequest) networkEnvelope;
            Map<InventoryItem, Integer> dataObjects = new HashMap<>();
            p2PDataStorage.getMapForDataResponse(getInventoryRequest.getVersion()).values().stream()
                    .map(e -> e.getClass().getSimpleName())
                    .forEach(className -> addClassNameToMap(dataObjects, className));
            p2PDataStorage.getMap().values().stream()
                    .map(ProtectedStorageEntry::getProtectedStoragePayload)
                    .map(e -> e.getClass().getSimpleName())
                    .forEach(className -> addClassNameToMap(dataObjects, className));
            Map<InventoryItem, String> inventory = new HashMap<>();
            dataObjects.forEach((key, value) -> inventory.put(key, String.valueOf(value)));



            // network
            inventory.put(InventoryItem.maxConnections, String.valueOf(maxConnections));
            inventory.put(InventoryItem.numConnections, String.valueOf(networkNode.getAllConnections().size()));
            inventory.put(InventoryItem.peakNumConnections, String.valueOf(peerManager.getPeakNumConnections()));
            inventory.put(InventoryItem.numAllConnectionsLostEvents, String.valueOf(peerManager.getNumAllConnectionsLostEvents()));
            peerManager.maybeResetNumAllConnectionsLostEvents();
            inventory.put(InventoryItem.sentBytes, String.valueOf(Statistic.totalSentBytesProperty().get()));
            inventory.put(InventoryItem.sentBytesPerSec, String.valueOf(Statistic.totalSentBytesPerSecProperty().get()));
            inventory.put(InventoryItem.receivedBytes, String.valueOf(Statistic.totalReceivedBytesProperty().get()));
            inventory.put(InventoryItem.receivedBytesPerSec, String.valueOf(Statistic.totalReceivedBytesPerSecProperty().get()));
            inventory.put(InventoryItem.receivedMessagesPerSec, String.valueOf(Statistic.numTotalReceivedMessagesPerSecProperty().get()));
            inventory.put(InventoryItem.sentMessagesPerSec, String.valueOf(Statistic.numTotalSentMessagesPerSecProperty().get()));

            // node
            inventory.put(InventoryItem.version, Version.VERSION);
            inventory.put(InventoryItem.commitHash, RequestInfo.COMMIT_HASH);
            inventory.put(InventoryItem.usedMemory, String.valueOf(Profiler.getUsedMemoryInBytes()));
            inventory.put(InventoryItem.jvmStartTime, String.valueOf(ManagementFactory.getRuntimeMXBean().getStartTime()));

            Filter filter = filterManager.getFilter();
            if (filter != null) {
                inventory.put(InventoryItem.filteredSeeds, Joiner.on("," + System.getProperty("line.separator")).join(filter.getSeedNodes()));
            }

            log.info("Send inventory {} to {}", inventory, connection.getPeersNodeAddressOptional());
            GetInventoryResponse getInventoryResponse = new GetInventoryResponse(inventory);
            networkNode.sendMessage(connection, getInventoryResponse);
        }
    }

    public void shutDown() {
        networkNode.removeMessageListener(this);
    }

    private void addClassNameToMap(Map<InventoryItem, Integer> dataObjects, String className) {
        Optional<InventoryItem> optionalEnum = Enums.getIfPresent(InventoryItem.class, className);
        if (optionalEnum.isPresent()) {
            InventoryItem key = optionalEnum.get();
            dataObjects.putIfAbsent(key, 0);
            int prev = dataObjects.get(key);
            dataObjects.put(key, prev + 1);
        }
    }
}
