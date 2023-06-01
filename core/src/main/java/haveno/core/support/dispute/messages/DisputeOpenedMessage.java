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
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DisputeOpenedMessage extends DisputeMessage {
    private final Dispute dispute;
    private final NodeAddress senderNodeAddress;
    private final String updatedMultisigHex;
    private final PaymentSentMessage paymentSentMessage;

    public DisputeOpenedMessage(Dispute dispute,
                                 NodeAddress senderNodeAddress,
                                 String uid,
                                 SupportType supportType,
                                 String updatedMultisigHex,
                                 PaymentSentMessage paymentSentMessage) {
        this(dispute,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType,
                updatedMultisigHex,
                paymentSentMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private DisputeOpenedMessage(Dispute dispute,
                                  NodeAddress senderNodeAddress,
                                  String uid,
                                  String messageVersion,
                                  SupportType supportType,
                                  String updatedMultisigHex,
                                  PaymentSentMessage paymentSentMessage) {
        super(messageVersion, uid, supportType);
        this.dispute = dispute;
        this.senderNodeAddress = senderNodeAddress;
        this.updatedMultisigHex = updatedMultisigHex;
        this.paymentSentMessage = paymentSentMessage;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DisputeOpenedMessage.Builder builder = protobuf.DisputeOpenedMessage.newBuilder()
                .setUid(uid)
                .setDispute(dispute.toProtoMessage())
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setType(SupportType.toProtoMessage(supportType))
                .setUpdatedMultisigHex(updatedMultisigHex);
        Optional.ofNullable(paymentSentMessage).ifPresent(e -> builder.setPaymentSentMessage(paymentSentMessage.toProtoNetworkEnvelope().getPaymentSentMessage()));
        return getNetworkEnvelopeBuilder().setDisputeOpenedMessage(builder).build();
    }

    public static DisputeOpenedMessage fromProto(protobuf.DisputeOpenedMessage proto,
                                                  CoreProtoResolver coreProtoResolver,
                                                  String messageVersion) {
        return new DisputeOpenedMessage(Dispute.fromProto(proto.getDispute(), coreProtoResolver),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()),
                proto.hasPaymentSentMessage() ? PaymentSentMessage.fromProto(proto.getPaymentSentMessage(), messageVersion) : null);
    }

    @Override
    public String getTradeId() {
        return dispute.getTradeId();
    }

    @Override
    public String toString() {
        return "DisputeOpenedMessage{" +
                "\n     dispute=" + dispute +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     DisputeOpenedMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                ",\n     updatedMultisigHex=" + updatedMultisigHex +
                ",\n     paymentSentMessage=" + paymentSentMessage +
                "\n} " + super.toString();
    }
}
