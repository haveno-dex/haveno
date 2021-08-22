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
public final class UpdateMultisigRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final long currentDate;
    @Nullable
    private final String updatedMultisigHex;

    public UpdateMultisigRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     int messageVersion,
                                     long currentDate,
                                     String updatedMultisigHex) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.currentDate = currentDate;
        this.updatedMultisigHex = updatedMultisigHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.UpdateMultisigRequest.Builder builder = protobuf.UpdateMultisigRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid);

        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setUpdateMultisigRequest(builder).build();
    }

    public static UpdateMultisigRequest fromProto(protobuf.UpdateMultisigRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new UpdateMultisigRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
    }

    @Override
    public String toString() {
        return "UpdateMultisigRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     updatedMultisigHex='" + updatedMultisigHex +
                "\n} " + super.toString();
    }
}
