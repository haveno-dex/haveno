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

package haveno.core.network.p2p.inventory.messages;


import haveno.common.app.Version;
import haveno.common.proto.network.NetworkEnvelope;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class GetInventoryRequest extends NetworkEnvelope {
    private final String version;

    public GetInventoryRequest(String version) {
        this(version, Version.getP2PMessageVersion());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private GetInventoryRequest(String version, String messageVersion) {
        super(messageVersion);

        this.version = version;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setGetInventoryRequest(protobuf.GetInventoryRequest.newBuilder()
                        .setVersion(version))
                .build();
    }

    public static GetInventoryRequest fromProto(protobuf.GetInventoryRequest proto, String messageVersion) {
        return new GetInventoryRequest(proto.getVersion(), messageVersion);
    }
}
