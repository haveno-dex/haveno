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

import haveno.core.support.SupportType;
import haveno.core.support.dispute.DisputeResult;

import haveno.network.p2p.NodeAddress;

import haveno.common.app.Version;

import lombok.EqualsAndHashCode;
import lombok.Value;

import static com.google.common.base.Preconditions.checkArgument;

@Value
@EqualsAndHashCode(callSuper = true)
public final class DisputeResultMessage extends DisputeMessage {
    private final DisputeResult disputeResult;
    private final NodeAddress senderNodeAddress;

    public DisputeResultMessage(DisputeResult disputeResult,
                                NodeAddress senderNodeAddress,
                                String uid,
                                SupportType supportType) {
        this(disputeResult,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private DisputeResultMessage(DisputeResult disputeResult,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 int messageVersion,
                                 SupportType supportType) {
        super(messageVersion, uid, supportType);
        this.disputeResult = disputeResult;
        this.senderNodeAddress = senderNodeAddress;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setDisputeResultMessage(protobuf.DisputeResultMessage.newBuilder()
                        .setDisputeResult(disputeResult.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setUid(uid)
                        .setType(SupportType.toProtoMessage(supportType)))
                .build();
    }

    public static DisputeResultMessage fromProto(protobuf.DisputeResultMessage proto, int messageVersion) {
        checkArgument(proto.hasDisputeResult(), "DisputeResult must be set");
        return new DisputeResultMessage(DisputeResult.fromProto(proto.getDisputeResult()),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()));
    }

    @Override
    public String getTradeId() {
        return disputeResult.getTradeId();
    }

    @Override
    public String toString() {
        return "DisputeResultMessage{" +
                "\n     disputeResult=" + disputeResult +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     DisputeResultMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                "\n} " + super.toString();
    }
}
