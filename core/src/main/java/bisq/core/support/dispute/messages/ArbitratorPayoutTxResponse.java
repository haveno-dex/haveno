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

package bisq.core.support.dispute.messages;

import bisq.core.proto.CoreProtoResolver;
import bisq.core.support.SupportType;

import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;

import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class ArbitratorPayoutTxResponse extends DisputeMessage {
    private final String tradeId;
    private final NodeAddress senderNodeAddress;
    private final String arbitratorSignedPayoutTxHex;

    public ArbitratorPayoutTxResponse(String tradeId,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 SupportType supportType,
                                 String arbitratorSignedPayoutTxHex) {
        this(tradeId,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType,
                arbitratorSignedPayoutTxHex);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ArbitratorPayoutTxResponse(String tradeId,
                                  NodeAddress senderNodeAddress,
                                  String uid,
                                  int messageVersion,
                                  SupportType supportType,
                                  String arbitratorSignedPayoutTxHex) {
        super(messageVersion, uid, supportType);
        this.tradeId = tradeId;
        this.senderNodeAddress = senderNodeAddress;
        this.arbitratorSignedPayoutTxHex = arbitratorSignedPayoutTxHex;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setArbitratorPayoutTxResponse(protobuf.ArbitratorPayoutTxResponse.newBuilder()
                        .setUid(uid)
                        .setTradeId(tradeId)
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setType(SupportType.toProtoMessage(supportType))
                        .setArbitratorSignedPayoutTxHex(arbitratorSignedPayoutTxHex))
                .build();
    }

    public static ArbitratorPayoutTxResponse fromProto(protobuf.ArbitratorPayoutTxResponse proto,
                                                  CoreProtoResolver coreProtoResolver,
                                                  int messageVersion) {
        return new ArbitratorPayoutTxResponse(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()),
                proto.getArbitratorSignedPayoutTxHex());
    }

    @Override
    public String toString() {
        return "ArbitratorPayoutTxResponse{" +
                "\n      tradeId=" + tradeId +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     ArbitratorPayoutTxResponse.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                ",\n     updatedMultisigHex=" + arbitratorSignedPayoutTxHex +
                "\n} " + super.toString();
    }
}
