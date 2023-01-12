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
import bisq.core.account.witness.AccountAgeWitness;
import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;
import bisq.common.proto.ProtoUtil;
import bisq.common.proto.network.NetworkEnvelope;

import java.util.Optional;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import com.google.protobuf.ByteString;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Getter
public final class PaymentReceivedMessage extends TradeMailboxMessage {
    private final NodeAddress senderNodeAddress;
    @Nullable
    private final String unsignedPayoutTxHex;
    @Nullable
    private final String signedPayoutTxHex;
    private final String updatedMultisigHex;
    private final boolean deferPublishPayout;
    @Nullable
    private final AccountAgeWitness buyerAccountAgeWitness;
    @Nullable
    private final SignedWitness buyerSignedWitness;
    private final PaymentSentMessage paymentSentMessage;
    @Setter
    @Nullable
    private byte[] sellerSignature;

    public PaymentReceivedMessage(String tradeId,
                                    NodeAddress senderNodeAddress,
                                    String uid,
                                    String unsignedPayoutTxHex,
                                    String signedPayoutTxHex,
                                    String updatedMultisigHex,
                                    boolean deferPublishPayout,
                                    AccountAgeWitness buyerAccountAgeWitness,
                                    @Nullable SignedWitness buyerSignedWitness,
                                    PaymentSentMessage paymentSentMessage) {
        this(tradeId,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                unsignedPayoutTxHex,
                signedPayoutTxHex,
                updatedMultisigHex,
                deferPublishPayout,
                buyerAccountAgeWitness,
                buyerSignedWitness,
                paymentSentMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentReceivedMessage(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     String uid,
                                     String messageVersion,
                                     String unsignedPayoutTxHex,
                                     String signedPayoutTxHex,
                                     String updatedMultisigHex,
                                     boolean deferPublishPayout,
                                     AccountAgeWitness buyerAccountAgeWitness,
                                     @Nullable SignedWitness buyerSignedWitness,
                                     PaymentSentMessage paymentSentMessage) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.unsignedPayoutTxHex = unsignedPayoutTxHex;
        this.signedPayoutTxHex = signedPayoutTxHex;
        this.updatedMultisigHex = updatedMultisigHex;
        this.deferPublishPayout = deferPublishPayout;
        this.paymentSentMessage = paymentSentMessage;
        this.buyerAccountAgeWitness = buyerAccountAgeWitness;
        this.buyerSignedWitness = buyerSignedWitness;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.PaymentReceivedMessage.Builder builder = protobuf.PaymentReceivedMessage.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid)
                .setDeferPublishPayout(deferPublishPayout);
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        Optional.ofNullable(unsignedPayoutTxHex).ifPresent(e -> builder.setUnsignedPayoutTxHex(unsignedPayoutTxHex));
        Optional.ofNullable(signedPayoutTxHex).ifPresent(e -> builder.setSignedPayoutTxHex(signedPayoutTxHex));
        Optional.ofNullable(buyerAccountAgeWitness).ifPresent(buyerAccountAgeWitness -> builder.setBuyerAccountAgeWitness(buyerAccountAgeWitness.toProtoAccountAgeWitness()));
        Optional.ofNullable(buyerSignedWitness).ifPresent(buyerSignedWitness -> builder.setBuyerSignedWitness(buyerSignedWitness.toProtoSignedWitness()));
        Optional.ofNullable(paymentSentMessage).ifPresent(e -> builder.setPaymentSentMessage(paymentSentMessage.toProtoNetworkEnvelope().getPaymentSentMessage()));
        Optional.ofNullable(sellerSignature).ifPresent(e -> builder.setSellerSignature(ByteString.copyFrom(e)));
        return getNetworkEnvelopeBuilder().setPaymentReceivedMessage(builder).build();
    }

    public static NetworkEnvelope fromProto(protobuf.PaymentReceivedMessage proto, String messageVersion) {
        // There is no method to check for a nullable non-primitive data type object but we know that all fields
        // are empty/null, so we check for the signature to see if we got a valid buyerSignedWitness.
        protobuf.AccountAgeWitness protoAccountAgeWitness = proto.getBuyerAccountAgeWitness();
        AccountAgeWitness buyerAccountAgeWitness = protoAccountAgeWitness.getHash().isEmpty() ? null : AccountAgeWitness.fromProto(protoAccountAgeWitness);
        protobuf.SignedWitness protoSignedWitness = proto.getBuyerSignedWitness();
        SignedWitness buyerSignedWitness = !protoSignedWitness.getSignature().isEmpty() ?
                SignedWitness.fromProto(protoSignedWitness) :
                null;
        PaymentReceivedMessage message = new PaymentReceivedMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.stringOrNullFromProto(proto.getUnsignedPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getSignedPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()),
                proto.getDeferPublishPayout(),
                buyerAccountAgeWitness,
                buyerSignedWitness,
                proto.hasPaymentSentMessage() ? PaymentSentMessage.fromProto(proto.getPaymentSentMessage(), messageVersion) : null);
        message.setSellerSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getSellerSignature()));
        return message;
    }

    @Override
    public String toString() {
        return "PaymentReceivedMessage{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     buyerSignedWitness=" + buyerSignedWitness +
                ",\n     unsignedPayoutTxHex=" + unsignedPayoutTxHex +
                ",\n     signedPayoutTxHex=" + signedPayoutTxHex +
                ",\n     updatedMultisigHex=" + (updatedMultisigHex == null ? null : updatedMultisigHex.substring(0, Math.max(updatedMultisigHex.length(), 1000))) +
                ",\n     deferPublishPayout=" + deferPublishPayout +
                ",\n     paymentSentMessage=" + paymentSentMessage +
                ",\n     sellerSignature=" + sellerSignature +
                "\n} " + super.toString();
    }
}
