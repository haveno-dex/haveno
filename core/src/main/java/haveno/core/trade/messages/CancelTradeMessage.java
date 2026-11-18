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

package haveno.core.trade.messages;

import haveno.common.app.Version;
import haveno.common.proto.ProtoUtil;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Asks the peer to cancel the trade or agrees to the peer's request, unless declining it.
 * The seller includes the payout tx returning the deposits, which the buyer signs and publishes.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public final class CancelTradeMessage extends TradeMailboxMessage {
    private final NodeAddress senderNodeAddress;
    private final boolean declined;
    @Nullable
    @ToString.Exclude
    private final String payoutTxHex;
    @Nullable
    @ToString.Exclude
    private final String updatedMultisigHex;

    public CancelTradeMessage(String tradeId,
                              NodeAddress senderNodeAddress,
                              String uid,
                              boolean declined,
                              @Nullable String payoutTxHex,
                              @Nullable String updatedMultisigHex) {
        super(Version.getP2PMessageVersion(), tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.declined = declined;
        this.payoutTxHex = payoutTxHex;
        this.updatedMultisigHex = updatedMultisigHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.CancelTradeMessage.Builder builder = protobuf.CancelTradeMessage.newBuilder()
                .setTradeId(offerId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid)
                .setDeclined(declined);
        Optional.ofNullable(payoutTxHex).ifPresent(e -> builder.setPayoutTxHex(payoutTxHex));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        return getNetworkEnvelopeBuilder().setCancelTradeMessage(builder).build();
    }

    public static CancelTradeMessage fromProto(protobuf.CancelTradeMessage proto) {
        return new CancelTradeMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                proto.getDeclined(),
                ProtoUtil.stringOrNullFromProto(proto.getPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
    }
}
