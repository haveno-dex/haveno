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

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;

@EqualsAndHashCode(callSuper = true)
@Value
public final class InitMultisigRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final long currentDate;
    @Nullable
    private final String preparedMultisigHex;
    @Nullable
    private final String madeMultisigHex;
    @Nullable
    private final String exchangedMultisigHex;

    public InitMultisigRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String preparedMultisigHex,
                                     String madeMultisigHex,
                                     String exchangedMultisigHex) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.currentDate = currentDate;
        this.preparedMultisigHex = preparedMultisigHex;
        this.madeMultisigHex = madeMultisigHex;
        this.exchangedMultisigHex = exchangedMultisigHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.InitMultisigRequest.Builder builder = protobuf.InitMultisigRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid);

        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(exchangedMultisigHex).ifPresent(e -> builder.setExchangedMultisigHex(exchangedMultisigHex));

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitMultisigRequest(builder).build();
    }

    public static InitMultisigRequest fromProto(protobuf.InitMultisigRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new InitMultisigRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()),
                ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()),
                ProtoUtil.stringOrNullFromProto(proto.getExchangedMultisigHex()));
    }

    @Override
    public String toString() {
        return "InitMultisigRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     preparedMultisigHex='" + preparedMultisigHex +
                ",\n     madeMultisigHex='" + madeMultisigHex +
                ",\n     exchangedMultisigHex='" + exchangedMultisigHex +
                "\n} " + super.toString();
    }
}
