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

import bisq.common.app.Version;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Dispute;
import bisq.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class ArbitratorPayoutTxRequest extends DisputeMessage {
    private final Dispute dispute;
    private final NodeAddress senderNodeAddress;
    private final String updatedMultisigHex;

    public ArbitratorPayoutTxRequest(Dispute dispute,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 SupportType supportType,
                                 String updatedMultisigHex) {
        this(dispute,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType,
                updatedMultisigHex);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ArbitratorPayoutTxRequest(Dispute dispute,
                                  NodeAddress senderNodeAddress,
                                  String uid,
                                  int messageVersion,
                                  SupportType supportType,
                                  String updatedMultisigHex) {
        super(messageVersion, uid, supportType);
        this.dispute = dispute;
        this.senderNodeAddress = senderNodeAddress;
        this.updatedMultisigHex = updatedMultisigHex;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setArbitratorPayoutTxRequest(protobuf.ArbitratorPayoutTxRequest.newBuilder()
                        .setUid(uid)
                        .setDispute(dispute.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setType(SupportType.toProtoMessage(supportType))
                        .setUpdatedMultisigHex(updatedMultisigHex))
                .build();
    }

    public static ArbitratorPayoutTxRequest fromProto(protobuf.ArbitratorPayoutTxRequest proto,
                                                  CoreProtoResolver coreProtoResolver,
                                                  int messageVersion) {
        return new ArbitratorPayoutTxRequest(Dispute.fromProto(proto.getDispute(), coreProtoResolver),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()),
                proto.getUpdatedMultisigHex());
    }

    @Override
    public String getTradeId() {
        return dispute.getTradeId();
    }
    
    @Override
    public String toString() {
        return "ArbitratorPayoutTxRequest{" +
                "\n     dispute=" + dispute +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     ArbitratorPayoutTxRequest.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                ",\n     updatedMultisigHex=" + updatedMultisigHex +
                "\n} " + super.toString();
    }
}
