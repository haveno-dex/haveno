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

package haveno.network.p2p;

import haveno.common.consensus.UsedForTradeContractJson;
import haveno.common.crypto.Hash;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.util.JsonExclude;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

@Getter
@EqualsAndHashCode
@Slf4j
public final class NodeAddress implements PersistablePayload, NetworkPayload, UsedForTradeContractJson {
    private final String hostName;
    private final int port;

    @JsonExclude
    private byte[] addressPrefixHash;

    public NodeAddress(String hostName, int port) {
        this.hostName = hostName;
        this.port = port;
    }

    public NodeAddress(String fullAddress) {
        final String[] split = fullAddress.split(Pattern.quote(":"));
        checkArgument(split.length == 2, "fullAddress must contain ':'");
        this.hostName = split[0];
        this.port = Integer.parseInt(split[1]);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public protobuf.NodeAddress toProtoMessage() {
        return protobuf.NodeAddress.newBuilder().setHostName(hostName).setPort(port).build();
    }

    public static NodeAddress fromProto(protobuf.NodeAddress proto) {
        return new NodeAddress(proto.getHostName(), proto.getPort());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getFullAddress() {
        return hostName + ":" + port;
    }

    public String getAddressForDisplay() {
        if (hostName.endsWith(".onion")) return getHostNameForDisplay();
        else return shortenAddressForDisplay(getFullAddress());
    }

    private String getHostNameWithoutPostFix() {
        return hostName.replace(".onion", "");
    }

    // tor v3 onions are too long to display for example in a table grid, so this convenience method
    // produces a display-friendly format which includes [first 7]..[last 7] characters.
    // tor v2 and localhost will be displayed in full, as they are 16 chars or fewer.
    private String getHostNameForDisplay() {
        return shortenAddressForDisplay(getHostNameWithoutPostFix());
    }

    private String shortenAddressForDisplay(String address) {
        if (address.length() > 16) {
            return address.substring(0, 7) + ".." + address.substring(address.length() - 7);
        }
        return address;
    }

    // We use just a few chars from the full address to blur the potential receiver for sent network_messages
    public byte[] getAddressPrefixHash() {
        if (addressPrefixHash == null)
            addressPrefixHash = Hash.getSha256Hash(getFullAddress().substring(0, Math.min(2, getFullAddress().length())));
        return addressPrefixHash;
    }

    @Override
    public String toString() {
        return getFullAddress();
    }
}
