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

package bisq.core.trade.messages;

import bisq.core.account.sign.SignedWitness;

import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;
import bisq.common.proto.network.NetworkEnvelope;

import java.util.Optional;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Value
public final class PayoutTxPublishedMessage extends TradeMailboxMessage {
    private final NodeAddress senderNodeAddress;
    private final String signedPayoutTxHex;

    // Added in v1.4.0
    @Nullable
    private final SignedWitness signedWitness;

    public PayoutTxPublishedMessage(String tradeId,
                                    NodeAddress senderNodeAddress,
                                    @Nullable SignedWitness signedWitness,
                                    String signedPayoutTxHex) {
        this(tradeId,
                senderNodeAddress,
                signedWitness,
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                signedPayoutTxHex);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PayoutTxPublishedMessage(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     @Nullable SignedWitness signedWitness,
                                     String uid,
                                     String messageVersion,
                                     String signedPayoutTxHex) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.signedWitness = signedWitness;
        this.signedPayoutTxHex = signedPayoutTxHex;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.PayoutTxPublishedMessage.Builder builder = protobuf.PayoutTxPublishedMessage.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid)
                .setSignedPayoutTxHex(signedPayoutTxHex);
        Optional.ofNullable(signedWitness).ifPresent(signedWitness -> builder.setSignedWitness(signedWitness.toProtoSignedWitness()));
        return getNetworkEnvelopeBuilder().setPayoutTxPublishedMessage(builder).build();
    }

    public static NetworkEnvelope fromProto(protobuf.PayoutTxPublishedMessage proto, String messageVersion) {
        // There is no method to check for a nullable non-primitive data type object but we know that all fields
        // are empty/null, so we check for the signature to see if we got a valid signedWitness.
        protobuf.SignedWitness protoSignedWitness = proto.getSignedWitness();
        SignedWitness signedWitness = !protoSignedWitness.getSignature().isEmpty() ?
                SignedWitness.fromProto(protoSignedWitness) :
                null;
        return new PayoutTxPublishedMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                signedWitness,
                proto.getUid(),
                messageVersion,
                proto.getSignedPayoutTxHex());
    }

    @Override
    public String toString() {
        return "PayoutTxPublishedMessage{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     signedWitness=" + signedWitness +
                ",\n     signedPayoutTxHex=" + signedPayoutTxHex +
                "\n} " + super.toString();
    }
}
