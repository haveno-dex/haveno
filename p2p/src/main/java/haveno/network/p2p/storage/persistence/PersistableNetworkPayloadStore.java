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

import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Store for PersistableNetworkPayload map entries with it's data hash as key.
 */
@Slf4j
public abstract class PersistableNetworkPayloadStore<T extends PersistableNetworkPayload> implements PersistableEnvelope {
    @Getter
    protected final Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> map = new ConcurrentHashMap<>();

    protected PersistableNetworkPayloadStore() {
    }

    protected PersistableNetworkPayloadStore(Collection<T> collection) {
        collection.forEach(item -> map.put(new P2PDataStorage.ByteArray(item.getHash()), item));
    }

    public boolean containsKey(P2PDataStorage.ByteArray hash) {
        return map.containsKey(hash);
    }
}
