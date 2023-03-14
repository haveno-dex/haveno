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

package haveno.network.crypto;

import haveno.common.proto.network.NetworkEnvelope;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.security.PublicKey;

@EqualsAndHashCode
@Value
public final class DecryptedDataTuple {
    private final NetworkEnvelope networkEnvelope;
    private final PublicKey sigPublicKey;

    public DecryptedDataTuple(NetworkEnvelope networkEnvelope, PublicKey sigPublicKey) {
        this.networkEnvelope = networkEnvelope;
        this.sigPublicKey = sigPublicKey;
    }
}
