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

import bisq.core.proto.CoreProtoResolver;

import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;
import com.google.protobuf.ByteString;
import java.util.Optional;
import javax.annotation.Nullable;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class PaymentAccountKeyResponse extends TradeMailboxMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    @Nullable
    private final byte[] paymentAccountKey;
    @Nullable
    private final String updatedMultisigHex;

    public PaymentAccountKeyResponse(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     String messageVersion,
                                     @Nullable byte[] paymentAccountKey,
                                     @Nullable String updatedMultisigHex) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.paymentAccountKey = paymentAccountKey;
        this.updatedMultisigHex = updatedMultisigHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.PaymentAccountKeyResponse.Builder builder = protobuf.PaymentAccountKeyResponse.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid);
        Optional.ofNullable(paymentAccountKey).ifPresent(e -> builder.setPaymentAccountKey(ByteString.copyFrom(e)));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        return getNetworkEnvelopeBuilder().setPaymentAccountKeyResponse(builder).build();
    }

    public static PaymentAccountKeyResponse fromProto(protobuf.PaymentAccountKeyResponse proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new PaymentAccountKeyResponse(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
    }

    @Override
    public String toString() {
        return "PaymentAccountKeyResponse {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     paymentAccountKey=" + paymentAccountKey +
                "\n} " + super.toString();
    }
}
