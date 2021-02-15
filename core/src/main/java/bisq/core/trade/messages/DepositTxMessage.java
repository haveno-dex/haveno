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

import java.util.Optional;

import javax.annotation.Nullable;

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

// It is the last message in the take offer phase. We use MailboxMessage instead of DirectMessage to add more tolerance
// in case of network issues and as the message does not trigger further protocol execution.
@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositTxMessage extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    @Nullable
    private final String tradeFeeTxId;
    @Nullable
    private final String depositTxId;

    public DepositTxMessage(int messageVersion,
                            String uid,
                            String tradeId,
                            NodeAddress senderNodeAddress,
                            PubKeyRing pubKeyRing,
                            String tradeFeeTxId,
                            String depositTxId) {
      super(messageVersion, tradeId, uid);
      this.senderNodeAddress = senderNodeAddress;
      this.pubKeyRing = pubKeyRing;
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
              .setPubKeyRing(pubKeyRing.toProtoMessage())
              .setUid(uid);
      Optional.ofNullable(tradeFeeTxId).ifPresent(e -> builder.setTradeFeeTxId(tradeFeeTxId));
      Optional.ofNullable(depositTxId).ifPresent(e -> builder.setDepositTxId(depositTxId));
      return getNetworkEnvelopeBuilder().setDepositTxMessage(builder).build();
    }

    public static DepositTxMessage fromProto(protobuf.DepositTxMessage proto, int messageVersion) {
        return new DepositTxMessage(messageVersion,
                proto.getUid(),
                proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
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
