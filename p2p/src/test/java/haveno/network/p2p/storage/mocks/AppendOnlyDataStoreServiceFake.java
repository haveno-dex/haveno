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

package haveno.network.p2p.storage.mocks;

import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.util.Map;

/**
 * Implementation of an in-memory AppendOnlyDataStoreService that can be used in tests. Removes overhead
 * involving files, resources, and services for tests that don't need it.
 *
 * @see <a href="https://martinfowler.com/articles/mocksArentStubs.html#TheDifferenceBetweenMocksAndStubs">Reference</a>
 */
public class AppendOnlyDataStoreServiceFake extends AppendOnlyDataStoreService {

    public AppendOnlyDataStoreServiceFake() {
        addService(new MapStoreServiceFake());
    }

    public Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> getMap() {
        return super.getMap();
    }

    public void put(P2PDataStorage.ByteArray hashAsByteArray, PersistableNetworkPayload payload) {
        super.put(hashAsByteArray, payload);
    }
}
