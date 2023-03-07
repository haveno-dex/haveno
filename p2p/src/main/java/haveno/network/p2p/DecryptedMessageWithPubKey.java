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

package haveno.network.p2p;

import com.google.protobuf.ByteString;
import haveno.common.crypto.Sig;
import haveno.common.proto.ProtobufferException;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistablePayload;
import java.security.PublicKey;

import lombok.Value;

@Value
public final class DecryptedMessageWithPubKey implements PersistablePayload {
    private final NetworkEnvelope networkEnvelope;
    private final byte[] signaturePubKeyBytes;
    transient private final PublicKey signaturePubKey;

    public DecryptedMessageWithPubKey(NetworkEnvelope networkEnvelope, PublicKey signaturePubKey) {
        this.networkEnvelope = networkEnvelope;
        this.signaturePubKey = signaturePubKey;
        this.signaturePubKeyBytes = Sig.getPublicKeyBytes(signaturePubKey);
    }

    private DecryptedMessageWithPubKey(NetworkEnvelope networkEnvelope, byte[] signaturePubKeyBytes) {
        this.networkEnvelope = networkEnvelope;
        this.signaturePubKeyBytes = signaturePubKeyBytes;
        this.signaturePubKey = Sig.getPublicKeyFromBytes(signaturePubKeyBytes);
    }

    @Override
    public protobuf.DecryptedMessageWithPubKey toProtoMessage() {
        return protobuf.DecryptedMessageWithPubKey.newBuilder()
                .setNetworkEnvelope(networkEnvelope.toProtoNetworkEnvelope())
                .setSignaturePubKeyBytes(ByteString.copyFrom(signaturePubKeyBytes))
                .build();
    }

    public static DecryptedMessageWithPubKey fromProto(protobuf.DecryptedMessageWithPubKey proto,
                                                       NetworkProtoResolver networkProtoResolver)
            throws ProtobufferException {
        return new DecryptedMessageWithPubKey(networkProtoResolver.fromProto(proto.getNetworkEnvelope()),
                proto.getSignaturePubKeyBytes().toByteArray());
    }
}
