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

package haveno.network.p2p.mailbox;


import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.util.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@EqualsAndHashCode
public class IgnoredMailboxMap implements PersistableEnvelope {
    @Getter
    private final Map<String, Long> dataMap;

    public IgnoredMailboxMap() {
        this.dataMap = new HashMap<>();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public IgnoredMailboxMap(Map<String, Long> ignored) {
        this.dataMap = ignored;
    }

    @Override
    public protobuf.PersistableEnvelope toProtoMessage() {
        return protobuf.PersistableEnvelope.newBuilder()
                .setIgnoredMailboxMap(protobuf.IgnoredMailboxMap.newBuilder().putAllData(dataMap))
                .build();
    }

    public static IgnoredMailboxMap fromProto(protobuf.IgnoredMailboxMap proto) {
        return new IgnoredMailboxMap(CollectionUtils.isEmpty(proto.getDataMap()) ? new HashMap<>() : proto.getDataMap());
    }

    public void putAll(Map<String, Long> map) {
        dataMap.putAll(map);
    }

    public boolean containsKey(String uid) {
        return dataMap.containsKey(uid);
    }

    public void put(String uid, long creationTimeStamp) {
        dataMap.put(uid, creationTimeStamp);
    }
}
