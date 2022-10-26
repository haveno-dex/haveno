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

import bisq.common.app.Version;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositsConfirmedMessage extends TradeMailboxMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    @Nullable
    private final byte[] sellerPaymentAccountKey;
    @Nullable
    private final String updatedMultisigHex;

    public DepositsConfirmedMessage(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     @Nullable byte[] sellerPaymentAccountKey,
                                     @Nullable String updatedMultisigHex) {
        super(Version.getP2PMessageVersion(), tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.sellerPaymentAccountKey = sellerPaymentAccountKey;
        this.updatedMultisigHex = updatedMultisigHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DepositsConfirmedMessage.Builder builder = protobuf.DepositsConfirmedMessage.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid);
        Optional.ofNullable(sellerPaymentAccountKey).ifPresent(e -> builder.setSellerPaymentAccountKey(ByteString.copyFrom(e)));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        return getNetworkEnvelopeBuilder().setDepositsConfirmedMessage(builder).build();
    }

    public static DepositsConfirmedMessage fromProto(protobuf.DepositsConfirmedMessage proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new DepositsConfirmedMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                ProtoUtil.byteArrayOrNullFromProto(proto.getSellerPaymentAccountKey()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
    }

    @Override
    public String toString() {
        return "DepositsConfirmedMessage {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     sellerPaymentAccountKey=" + sellerPaymentAccountKey +
                ",\n     updatedMultisigHex=" + (updatedMultisigHex == null ? null : updatedMultisigHex.substring(0, Math.max(updatedMultisigHex.length(), 1000))) +
                "\n} " + super.toString();
    }
}
