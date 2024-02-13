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

package haveno.network.p2p.storage.messages;

import haveno.common.app.Version;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class AddPersistableNetworkPayloadMessage extends BroadcastMessage {
    private final PersistableNetworkPayload persistableNetworkPayload;

    public AddPersistableNetworkPayloadMessage(PersistableNetworkPayload persistableNetworkPayload) {
        this(persistableNetworkPayload, Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private AddPersistableNetworkPayloadMessage(PersistableNetworkPayload persistableNetworkPayload, String messageVersion) {
        super(messageVersion);
        this.persistableNetworkPayload = persistableNetworkPayload;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setAddPersistableNetworkPayloadMessage(protobuf.AddPersistableNetworkPayloadMessage.newBuilder()
                        .setPayload(persistableNetworkPayload.toProtoMessage()))
                .build();
    }

    public static AddPersistableNetworkPayloadMessage fromProto(protobuf.AddPersistableNetworkPayloadMessage proto,
                                                                NetworkProtoResolver resolver,
                                                                String messageVersion) {
        return new AddPersistableNetworkPayloadMessage((PersistableNetworkPayload) resolver.fromProto(proto.getPayload()),
                messageVersion);
    }
}
