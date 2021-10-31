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

import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;
import bisq.common.proto.ProtoUtil;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;

// It is the last message in the take offer phase. We use MailboxMessage instead of DirectMessage to add more tolerance
// in case of network issues and as the message does not trigger further protocol execution.
@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositTxMessage extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    @Nullable
    private final String tradeFeeTxId;
    @Nullable
    private final String depositTxId;

    public DepositTxMessage(String uid,
                            String tradeId,
                            NodeAddress senderNodeAddress,
                            String tradeFeeTxId,
                            String depositTxId) {
      super(Version.getP2PMessageVersion(), tradeId, uid);
      this.senderNodeAddress = senderNodeAddress;
      this.tradeFeeTxId = tradeFeeTxId;
      this.depositTxId = depositTxId;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
      protobuf.DepositTxMessage.Builder builder = protobuf.DepositTxMessage.newBuilder()
              .setTradeId(tradeId)
              .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
              .setUid(uid);
      Optional.ofNullable(tradeFeeTxId).ifPresent(e -> builder.setTradeFeeTxId(tradeFeeTxId));
      Optional.ofNullable(depositTxId).ifPresent(e -> builder.setDepositTxId(depositTxId));
      return getNetworkEnvelopeBuilder().setDepositTxMessage(builder).build();
    }

    public static DepositTxMessage fromProto(protobuf.DepositTxMessage proto, int messageVersion) {
        return new DepositTxMessage(proto.getUid(),
                proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                ProtoUtil.stringOrNullFromProto(proto.getTradeFeeTxId()),
                ProtoUtil.stringOrNullFromProto(proto.getDepositTxId()));
    }

    @Override
    public String toString() {
        return "DepositTxMessage{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     tradeFeeTxId=" + tradeFeeTxId +
                ",\n     depositTxId=" + depositTxId +
                "\n} " + super.toString();
    }
}
