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

package haveno.network.p2p.peers.getdata.messages;

import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.ExtendedDataSizePermission;
import haveno.network.p2p.InitialDataRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nullable;
import java.util.Set;

@EqualsAndHashCode(callSuper = true)
@Getter
@ToString
public abstract class GetDataRequest extends NetworkEnvelope implements ExtendedDataSizePermission,
        InitialDataRequest {
    protected final int nonce;
    // Keys for ProtectedStorageEntry items to be excluded from the request because the peer has them already
    protected final Set<byte[]> excludedKeys;

    // Added at v1.4.0
    // The version of the requester. Used for response to send potentially missing historical data
    @Nullable
    protected final String version;

    public GetDataRequest(String messageVersion,
                          int nonce,
                          Set<byte[]> excludedKeys,
                          @Nullable String version) {
        super(messageVersion);
        this.nonce = nonce;
        this.excludedKeys = excludedKeys;
        this.version = version;
    }
}
