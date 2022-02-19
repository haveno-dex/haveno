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
import bisq.common.crypto.PubKeyRing;

import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final long currentDate;
    private final String contractSignature;
    private final String depositTxHex;
    private final String depositTxKey;

    public DepositRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String contractSignature,
                                     String depositTxHex,
                                     String depositTxKey) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.currentDate = currentDate;
        this.contractSignature = contractSignature;
        this.depositTxHex = depositTxHex;
        this.depositTxKey = depositTxKey;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DepositRequest.Builder builder = protobuf.DepositRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid)
                .setContractSignature(contractSignature)
                .setDepositTxHex(depositTxHex)
                .setDepositTxKey(depositTxKey);
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setDepositRequest(builder).build();
    }

    public static DepositRequest fromProto(protobuf.DepositRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new DepositRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getContractSignature(),
                proto.getDepositTxHex(),
                proto.getDepositTxKey());
    }

    @Override
    public String toString() {
        return "DepositRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     contractSignature=" + contractSignature +
                ",\n     depositTxHex='" + depositTxHex +
                ",\n     depositTxKey='" + depositTxKey +
                "\n} " + super.toString();
    }
}
