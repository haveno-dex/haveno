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

package haveno.core.support.dispute.messages;

import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;

import haveno.network.p2p.NodeAddress;

import haveno.common.app.Version;

import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class OpenNewDisputeMessage extends DisputeMessage {
    private final Dispute dispute;
    private final NodeAddress senderNodeAddress;
    private final String updatedMultisigHex;

    public OpenNewDisputeMessage(Dispute dispute,
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

    private OpenNewDisputeMessage(Dispute dispute,
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
                .setOpenNewDisputeMessage(protobuf.OpenNewDisputeMessage.newBuilder()
                        .setUid(uid)
                        .setDispute(dispute.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setType(SupportType.toProtoMessage(supportType))
                        .setUpdatedMultisigHex(updatedMultisigHex))
                .build();
    }

    public static OpenNewDisputeMessage fromProto(protobuf.OpenNewDisputeMessage proto,
                                                  CoreProtoResolver coreProtoResolver,
                                                  int messageVersion) {
        return new OpenNewDisputeMessage(Dispute.fromProto(proto.getDispute(), coreProtoResolver),
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
        return "OpenNewDisputeMessage{" +
                "\n     dispute=" + dispute +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     OpenNewDisputeMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                ",\n     updatedMultisigHex=" + updatedMultisigHex +
                "\n} " + super.toString();
    }
}
