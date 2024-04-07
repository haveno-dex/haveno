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

package haveno.core.trade.messages;

import com.google.protobuf.ByteString;
import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.util.Utilities;
import haveno.core.proto.CoreProtoResolver;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositsConfirmedMessage extends TradeMailboxMessage {
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
                .setTradeId(offerId)
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
                ",\n     sellerPaymentAccountKey=" + Utilities.bytesAsHexString(sellerPaymentAccountKey) +
                ",\n     updatedMultisigHex=" + (updatedMultisigHex == null ? null : updatedMultisigHex.substring(0, Math.max(updatedMultisigHex.length(), 1000))) +
                "\n} " + super.toString();
    }
}
