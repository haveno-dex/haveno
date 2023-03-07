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

import haveno.common.app.Version;
import haveno.common.proto.ProtoUtil;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.DisputeResult;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Optional;

import javax.annotation.Nullable;

@Value
@EqualsAndHashCode(callSuper = true)
public final class DisputeClosedMessage extends DisputeMessage {
    private final DisputeResult disputeResult;
    private final NodeAddress senderNodeAddress;
    private final String updatedMultisigHex;
    @Nullable
    private final String unsignedPayoutTxHex;
    private final boolean deferPublishPayout;

    public DisputeClosedMessage(DisputeResult disputeResult,
                                NodeAddress senderNodeAddress,
                                String uid,
                                SupportType supportType,
                                String updatedMultisigHex,
                                @Nullable String unsignedPayoutTxHex,
                                boolean deferPublishPayout) {
        this(disputeResult,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType,
                updatedMultisigHex,
                unsignedPayoutTxHex,
                deferPublishPayout);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private DisputeClosedMessage(DisputeResult disputeResult,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 String messageVersion,
                                 SupportType supportType,
                                 String updatedMultisigHex,
                                 String unsignedPayoutTxHex,
                                 boolean deferPublishPayout) {
        super(messageVersion, uid, supportType);
        this.disputeResult = disputeResult;
        this.senderNodeAddress = senderNodeAddress;
        this.updatedMultisigHex = updatedMultisigHex;
        this.unsignedPayoutTxHex = unsignedPayoutTxHex;
        this.deferPublishPayout = deferPublishPayout;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DisputeClosedMessage.Builder builder = protobuf.DisputeClosedMessage.newBuilder()
                .setDisputeResult(disputeResult.toProtoMessage())
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid)
                .setType(SupportType.toProtoMessage(supportType))
                .setUpdatedMultisigHex(updatedMultisigHex)
                .setDeferPublishPayout(deferPublishPayout);
        Optional.ofNullable(unsignedPayoutTxHex).ifPresent(e -> builder.setUnsignedPayoutTxHex(unsignedPayoutTxHex));
        return getNetworkEnvelopeBuilder().setDisputeClosedMessage(builder).build();
    }

    public static DisputeClosedMessage fromProto(protobuf.DisputeClosedMessage proto, String messageVersion) {
        checkArgument(proto.hasDisputeResult(), "DisputeResult must be set");
        return new DisputeClosedMessage(DisputeResult.fromProto(proto.getDisputeResult()),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()),
                proto.getUpdatedMultisigHex(),
                ProtoUtil.stringOrNullFromProto(proto.getUnsignedPayoutTxHex()),
                proto.getDeferPublishPayout());
    }

    @Override
    public String getTradeId() {
        return disputeResult.getTradeId();
    }

    @Override
    public String toString() {
        return "DisputeClosedMessage{" +
                "\n     disputeResult=" + disputeResult +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     DisputeClosedMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                ",\n     deferPublishPayout=" + deferPublishPayout +
                "\n} " + super.toString();
    }
}
