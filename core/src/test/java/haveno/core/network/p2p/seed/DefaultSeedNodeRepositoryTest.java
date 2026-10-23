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

package haveno.core.network.p2p.seed;

import haveno.common.config.Config;
import haveno.network.p2p.NodeAddress;
import haveno.core.filter.FilterManager;
import haveno.core.network.p2p.inventory.GetInventoryRequestHandler;
import haveno.core.network.p2p.inventory.messages.GetInventoryRequest;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.storage.P2PDataStorage;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import org.junit.jupiter.api.Test;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultSeedNodeRepositoryTest {

    @Test
    public void inventoryRequestsShareBoundedStorageSnapshot() throws Exception {
        P2PDataStorage storage = mock(P2PDataStorage.class);
        GetInventoryRequestHandler handler = new GetInventoryRequestHandler(mock(NetworkNode.class),
                mock(PeerManager.class), storage, mock(FilterManager.class), 12);
        Connection connection = mock(Connection.class);
        for (int i = 0; i < 100; i++) handler.onMessage(new GetInventoryRequest("1.0." + i), connection);
        verify(storage).getMapForDataResponse(null);
        verify(storage).getMap();
        Field updatedAt = GetInventoryRequestHandler.class.getDeclaredField("dataInventoryUpdatedAt");
        updatedAt.setAccessible(true);
        updatedAt.setLong(handler, System.nanoTime() - TimeUnit.SECONDS.toNanos(31));
        handler.onMessage(new GetInventoryRequest("1.0.0"), connection);
        verify(storage, times(2)).getMapForDataResponse(null);
        verify(storage, times(2)).getMap();
    }

    @Test
    public void getSeedNodes() {
        DefaultSeedNodeRepository DUT = new DefaultSeedNodeRepository(new Config());
        assertFalse(DUT.getSeedNodeAddresses().isEmpty());
    }

    @Test
    public void manualSeedNodes() {
        String seed1 = "asdf:8001";
        String seed2 = "fdsa:6001";
        String seedNodesOption = format("--%s=%s,%s", Config.SEED_NODES, seed1, seed2);
        DefaultSeedNodeRepository DUT = new DefaultSeedNodeRepository(new Config(seedNodesOption));
        assertFalse(DUT.getSeedNodeAddresses().isEmpty());
        assertEquals(2, DUT.getSeedNodeAddresses().size());
        assertTrue(DUT.getSeedNodeAddresses().contains(new NodeAddress(seed1)));
        assertTrue(DUT.getSeedNodeAddresses().contains(new NodeAddress(seed2)));
    }
}
