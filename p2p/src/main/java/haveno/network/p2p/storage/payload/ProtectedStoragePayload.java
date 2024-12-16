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

package haveno.network.p2p.storage.payload;

import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.network.NetworkProtoResolver;

import javax.annotation.Nullable;
import java.security.PublicKey;
import java.util.Map;

/**
 * Messages which support ownership protection (using signatures) and a time to live
 * <p/>
 * Implementations:
 * io.haveno.alert.Alert
 * io.haveno.arbitration.Arbitrator
 * io.haveno.trade.offer.OfferPayload
 */
public interface ProtectedStoragePayload extends NetworkPayload {
    /**
     * Used for check if the add or remove operation is permitted.
     * Only data owner can add or remove the data.
     * OwnerPubKey has to be equal to the ownerPubKey of the ProtectedStorageEntry
     *
     * @return The public key of the data owner.
     */
    PublicKey getOwnerPubKey();

    // Should be only used in emergency case if we need to add data but do not want to break backward compatibility
    // at the P2P network storage checks. The hash of the object will be used to verify if the data is valid. Any new
    // field in a class would break that hash and therefore break the storage mechanism.
    @Nullable
    Map<String, String> getExtraDataMap();

    static ProtectedStoragePayload fromProto(protobuf.StoragePayload storagePayload, NetworkProtoResolver networkProtoResolver) {
        return (ProtectedStoragePayload) networkProtoResolver.fromProto(storagePayload);
    }
}
