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

import bisq.network.p2p.NodeAddress;

import bisq.common.app.Version;
import bisq.common.proto.ProtoUtil;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;

@EqualsAndHashCode(callSuper = true)
@Value
public final class CounterCurrencyTransferStartedMessage extends TradeMailboxMessage {
    private final String buyerPayoutAddress;
    private final NodeAddress senderNodeAddress;
    private final String buyerPayoutTxSigned;
    @Nullable
    private final String counterCurrencyTxId;

    // Added after v1.3.7
    // We use that for the XMR txKey but want to keep it generic to be flexible for data of other payment methods or assets.
    @Nullable
    private String counterCurrencyExtraData;

    public CounterCurrencyTransferStartedMessage(String tradeId,
                                                 String buyerPayoutAddress,
                                                 NodeAddress senderNodeAddress,
                                                 String buyerPayoutTxSigned,
                                                 @Nullable String counterCurrencyTxId,
                                                 @Nullable String counterCurrencyExtraData,
                                                 String uid) {
        this(tradeId,
                buyerPayoutAddress,
                senderNodeAddress,
                buyerPayoutTxSigned,
                counterCurrencyTxId,
                counterCurrencyExtraData,
                uid,
                Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private CounterCurrencyTransferStartedMessage(String tradeId,
                                                  String buyerPayoutAddress,
                                                  NodeAddress senderNodeAddress,
                                                  String buyerPayoutTxSigned,
                                                  @Nullable String counterCurrencyTxId,
                                                  @Nullable String counterCurrencyExtraData,
                                                  String uid,
                                                  String messageVersion) {
        super(messageVersion, tradeId, uid);
        this.buyerPayoutAddress = buyerPayoutAddress;
        this.senderNodeAddress = senderNodeAddress;
        this.buyerPayoutTxSigned = buyerPayoutTxSigned;
        this.counterCurrencyTxId = counterCurrencyTxId;
        this.counterCurrencyExtraData = counterCurrencyExtraData;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        final protobuf.CounterCurrencyTransferStartedMessage.Builder builder = protobuf.CounterCurrencyTransferStartedMessage.newBuilder();
        builder.setTradeId(tradeId)
                .setBuyerPayoutAddress(buyerPayoutAddress)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setBuyerPayoutTxSigned(buyerPayoutTxSigned)
                .setUid(uid);

        Optional.ofNullable(counterCurrencyTxId).ifPresent(e -> builder.setCounterCurrencyTxId(counterCurrencyTxId));
        Optional.ofNullable(counterCurrencyExtraData).ifPresent(e -> builder.setCounterCurrencyExtraData(counterCurrencyExtraData));

        return getNetworkEnvelopeBuilder().setCounterCurrencyTransferStartedMessage(builder).build();
    }

    public static CounterCurrencyTransferStartedMessage fromProto(protobuf.CounterCurrencyTransferStartedMessage proto,
                                                                  String messageVersion) {
        return new CounterCurrencyTransferStartedMessage(proto.getTradeId(),
                proto.getBuyerPayoutAddress(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getBuyerPayoutTxSigned(),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyTxId()),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()),
                proto.getUid(),
                messageVersion);
    }


    @Override
    public String toString() {
        return "CounterCurrencyTransferStartedMessage{" +
                "\n     buyerPayoutAddress='" + buyerPayoutAddress + '\'' +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     counterCurrencyTxId=" + counterCurrencyTxId +
                ",\n     counterCurrencyExtraData=" + counterCurrencyExtraData +
                ",\n     uid='" + uid + '\'' +
                ",\n     buyerPayoutTxSigned=" + buyerPayoutTxSigned +
                "\n} " + super.toString();
    }
}
