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

package haveno.network.p2p.peers.peerexchange;

import com.google.protobuf.Message;
import haveno.common.proto.persistable.PersistableEnvelope;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@EqualsAndHashCode
public class PeerList implements PersistableEnvelope {
    @Getter
    private final Set<Peer> set = new HashSet<>();

    public PeerList() {
    }

    public PeerList(Set<Peer> set) {
        setAll(set);
    }

    public int size() {
        return set.size();
    }

    @Override
    public Message toProtoMessage() {
        return protobuf.PersistableEnvelope.newBuilder()
                .setPeerList(protobuf.PeerList.newBuilder()
                        .addAllPeer(set.stream().map(Peer::toProtoMessage).collect(Collectors.toList())))
                .build();
    }

    public static PeerList fromProto(protobuf.PeerList proto) {
        return new PeerList(proto.getPeerList().stream()
                .map(Peer::fromProto)
                .collect(Collectors.toSet()));
    }

    public void setAll(Collection<Peer> collection) {
        this.set.clear();
        this.set.addAll(collection);
    }

    @Override
    public String toString() {
        return "PeerList{" +
                "\n     set=" + set +
                "\n}";
    }
}
