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

package haveno.network.p2p.storage.mocks;

import haveno.common.persistence.PersistenceManager;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.persistence.HistoricalDataStoreService;
import haveno.network.p2p.storage.persistence.PersistableNetworkPayloadStore;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * Implementation of an in-memory HistoricalDataStoreService that can be used in tests. Removes overhead
 * involving files, resources, and services for tests that don't need it.
 */
public class HistoricalDataStoreServiceFake extends HistoricalDataStoreService<PersistableNetworkPayloadStore<PersistableNetworkPayload>> {
    private final Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> liveData = new HashMap<>();

    @SuppressWarnings("unchecked")
    public HistoricalDataStoreServiceFake() {
        super(mock(File.class), mock(PersistenceManager.class));
    }

    @Override
    public Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> getMapOfLiveData() {
        return liveData;
    }

    @Override
    public String getFileName() {
        return null;
    }

    @Override
    protected PersistableNetworkPayloadStore<PersistableNetworkPayload> createStore() {
        return null;
    }

    @Override
    public boolean canHandle(PersistableNetworkPayload payload) {
        return true;
    }

    @Override
    protected void initializePersistenceManager() {
    }
}
