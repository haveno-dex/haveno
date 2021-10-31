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

package haveno.core.support.dispute.arbitration.messages;

import haveno.core.support.SupportType;

import haveno.network.p2p.NodeAddress;

import haveno.common.app.Version;

import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode(callSuper = true)
public final class PeerPublishedDisputePayoutTxMessage extends ArbitrationMessage {
    private final String updatedMultisigHex;
    private final String payoutTxHex;
    private final String tradeId;
    private final NodeAddress senderNodeAddress;

    public PeerPublishedDisputePayoutTxMessage(String updatedMultisigHex,
    		                                   String payoutTxHex,
                                               String tradeId,
                                               NodeAddress senderNodeAddress,
                                               String uid,
                                               SupportType supportType) {
        this(updatedMultisigHex,
                payoutTxHex,
                tradeId,
                senderNodeAddress,
                uid,
                Version.getP2PMessageVersion(),
                supportType);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PeerPublishedDisputePayoutTxMessage(String updatedMultisigHex,
                                                String payoutTxHex,
                                                String tradeId,
                                                NodeAddress senderNodeAddress,
                                                String uid,
                                                int messageVersion,
                                                SupportType supportType) {
        super(messageVersion, uid, supportType);
        this.updatedMultisigHex = updatedMultisigHex;
        this.payoutTxHex = payoutTxHex;
        this.tradeId = tradeId;
        this.senderNodeAddress = senderNodeAddress;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setPeerPublishedDisputePayoutTxMessage(protobuf.PeerPublishedDisputePayoutTxMessage.newBuilder()
                        .setUpdatedMultisigHex(updatedMultisigHex)
                        .setPayoutTxHex(payoutTxHex)
                        .setTradeId(tradeId)
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setUid(uid)
                        .setType(SupportType.toProtoMessage(supportType)))
                .build();
    }

    public static PeerPublishedDisputePayoutTxMessage fromProto(protobuf.PeerPublishedDisputePayoutTxMessage proto,
                                                                int messageVersion) {
        return new PeerPublishedDisputePayoutTxMessage(proto.getUpdatedMultisigHex(),
        		proto.getPayoutTxHex(),
                proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion,
                SupportType.fromProto(proto.getType()));
    }

    @Override
    public String getTradeId() {
        return tradeId;
    }

    @Override
    public String toString() {
        return "PeerPublishedDisputePayoutTxMessage{" +
                "\n     updatedMultisigHex=" + updatedMultisigHex +
                "\n     payoutTxHex=" + payoutTxHex +
                ",\n     tradeId='" + tradeId + '\'' +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     PeerPublishedDisputePayoutTxMessage.uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                "\n} " + super.toString();
    }
}
