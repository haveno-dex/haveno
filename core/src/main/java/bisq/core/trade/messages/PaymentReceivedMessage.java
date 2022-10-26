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
import bisq.common.proto.ProtoUtil;
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
public final class PaymentReceivedMessage extends TradeMailboxMessage {
    private final NodeAddress senderNodeAddress;
    @Nullable
    private final String unsignedPayoutTxHex;
    @Nullable
    private final String signedPayoutTxHex;
    private final String updatedMultisigHex;
    private final boolean sawArrivedPaymentReceivedMsg;

    // Added in v1.4.0
    @Nullable
    private final SignedWitness signedWitness;

    public PaymentReceivedMessage(String tradeId,
                                    NodeAddress senderNodeAddress,
                                    @Nullable SignedWitness signedWitness,
                                    String unsignedPayoutTxHex,
                                    String signedPayoutTxHex,
                                    String updatedMultisigHex,
                                    boolean sawArrivedPaymentReceivedMsg) {
        this(tradeId,
                senderNodeAddress,
                signedWitness,
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                unsignedPayoutTxHex,
                signedPayoutTxHex,
                updatedMultisigHex,
                sawArrivedPaymentReceivedMsg);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentReceivedMessage(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     @Nullable SignedWitness signedWitness,
                                     String uid,
                                     String messageVersion,
                                     String unsignedPayoutTxHex,
                                     String signedPayoutTxHex,
                                     String updatedMultisigHex,
                                     boolean sawArrivedPaymentReceivedMsg) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.signedWitness = signedWitness;
        this.unsignedPayoutTxHex = unsignedPayoutTxHex;
        this.signedPayoutTxHex = signedPayoutTxHex;
        this.updatedMultisigHex = updatedMultisigHex;
        this.sawArrivedPaymentReceivedMsg = sawArrivedPaymentReceivedMsg;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.PaymentReceivedMessage.Builder builder = protobuf.PaymentReceivedMessage.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid)
                .setSawArrivedPaymentReceivedMsg(sawArrivedPaymentReceivedMsg);
        Optional.ofNullable(signedWitness).ifPresent(signedWitness -> builder.setSignedWitness(signedWitness.toProtoSignedWitness()));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        Optional.ofNullable(unsignedPayoutTxHex).ifPresent(e -> builder.setUnsignedPayoutTxHex(unsignedPayoutTxHex));
        Optional.ofNullable(signedPayoutTxHex).ifPresent(e -> builder.setSignedPayoutTxHex(signedPayoutTxHex));
        return getNetworkEnvelopeBuilder().setPaymentReceivedMessage(builder).build();
    }

    public static NetworkEnvelope fromProto(protobuf.PaymentReceivedMessage proto, String messageVersion) {
        // There is no method to check for a nullable non-primitive data type object but we know that all fields
        // are empty/null, so we check for the signature to see if we got a valid signedWitness.
        protobuf.SignedWitness protoSignedWitness = proto.getSignedWitness();
        SignedWitness signedWitness = !protoSignedWitness.getSignature().isEmpty() ?
                SignedWitness.fromProto(protoSignedWitness) :
                null;
        return new PaymentReceivedMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                signedWitness,
                proto.getUid(),
                messageVersion,
                ProtoUtil.stringOrNullFromProto(proto.getUnsignedPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getSignedPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()),
                proto.getSawArrivedPaymentReceivedMsg());
    }

    @Override
    public String toString() {
        return "SellerReceivedPaymentMessage{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     signedWitness=" + signedWitness +
                ",\n     unsignedPayoutTxHex=" + unsignedPayoutTxHex +
                ",\n     signedPayoutTxHex=" + signedPayoutTxHex +
                ",\n     updatedMultisigHex=" + (updatedMultisigHex == null ? null : updatedMultisigHex.substring(0, Math.max(updatedMultisigHex.length(), 1000))) +
                ",\n     sawArrivedPaymentReceivedMsg=" + sawArrivedPaymentReceivedMsg +
                "\n} " + super.toString();
    }
}
