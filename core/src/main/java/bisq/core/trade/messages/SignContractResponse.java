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
public final class SignContractResponse extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final long currentDate;
    private final String contractSignature;

    public SignContractResponse(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     int messageVersion,
                                     long currentDate,
                                     String contractSignature) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.currentDate = currentDate;
        this.contractSignature = contractSignature;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignContractResponse.Builder builder = protobuf.SignContractResponse.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid);

        Optional.ofNullable(contractSignature).ifPresent(e -> builder.setContractSignature(contractSignature));

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setSignContractResponse(builder).build();
    }

    public static SignContractResponse fromProto(protobuf.SignContractResponse proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new SignContractResponse(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getContractSignature()));
    }

    @Override
    public String toString() {
        return "SignContractResponse {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     contractSignature='" + contractSignature +
                "\n} " + super.toString();
    }
}
