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

package haveno.network.p2p.storage.persistence;

import haveno.network.p2p.storage.P2PDataStorage;

import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistablePayload;

import java.io.File;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles persisted data which is stored in a map.
 *
 * @param <T>
 * @param <R>
 */
@Slf4j
public abstract class MapStoreService<T extends PersistableEnvelope, R extends PersistablePayload> extends StoreService<T> {


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MapStoreService(File storageDir, PersistenceManager<T> persistenceManager) {
        super(storageDir, persistenceManager);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public abstract Map<P2PDataStorage.ByteArray, R> getMap();

    public abstract boolean canHandle(R payload);

    void put(P2PDataStorage.ByteArray hash, R payload) {
        getMap().put(hash, payload);
        requestPersistence();
    }

    protected R putIfAbsent(P2PDataStorage.ByteArray hash, R payload) {
        R previous = getMap().putIfAbsent(hash, payload);
        requestPersistence();
        return previous;
    }

    R remove(P2PDataStorage.ByteArray hash) {
        R result = getMap().remove(hash);
        requestPersistence();
        return result;
    }

    boolean containsKey(P2PDataStorage.ByteArray hash) {
        return getMap().containsKey(hash);
    }
}
