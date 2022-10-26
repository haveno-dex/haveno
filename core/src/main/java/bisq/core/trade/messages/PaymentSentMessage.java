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
import com.google.protobuf.ByteString;
import bisq.common.app.Version;
import bisq.common.proto.ProtoUtil;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;

@EqualsAndHashCode(callSuper = true)
@Value
public final class PaymentSentMessage extends TradeMailboxMessage {
    private final NodeAddress senderNodeAddress;
    @Nullable
    private final String counterCurrencyTxId;
    @Nullable
    private final String payoutTxHex;
    @Nullable
    private final String updatedMultisigHex;
    @Nullable
    private final byte[] paymentAccountKey;

    // Added after v1.3.7
    // We use that for the XMR txKey but want to keep it generic to be flexible for data of other payment methods or assets.
    @Nullable
    private String counterCurrencyExtraData;

    public PaymentSentMessage(String tradeId,
                                                 NodeAddress senderNodeAddress,
                                                 @Nullable String counterCurrencyTxId,
                                                 @Nullable String counterCurrencyExtraData,
                                                 String uid,
                                                 @Nullable String signedPayoutTxHex,
                                                 @Nullable String updatedMultisigHex,
                                                 @Nullable byte[] paymentAccountKey) {
        this(tradeId,
                senderNodeAddress,
                counterCurrencyTxId,
                counterCurrencyExtraData,
                uid,
                Version.getP2PMessageVersion(),
                signedPayoutTxHex,
                updatedMultisigHex,
                paymentAccountKey);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentSentMessage(String tradeId,
                                                  NodeAddress senderNodeAddress,
                                                  @Nullable String counterCurrencyTxId,
                                                  @Nullable String counterCurrencyExtraData,
                                                  String uid,
                                                  String messageVersion,
                                                  @Nullable String signedPayoutTxHex,
                                                  @Nullable String updatedMultisigHex,
                                                  @Nullable byte[] paymentAccountKey) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.counterCurrencyTxId = counterCurrencyTxId;
        this.counterCurrencyExtraData = counterCurrencyExtraData;
        this.payoutTxHex = signedPayoutTxHex;
        this.updatedMultisigHex = updatedMultisigHex;
        this.paymentAccountKey = paymentAccountKey;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        final protobuf.PaymentSentMessage.Builder builder = protobuf.PaymentSentMessage.newBuilder();
        builder.setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setUid(uid);

        Optional.ofNullable(counterCurrencyTxId).ifPresent(e -> builder.setCounterCurrencyTxId(counterCurrencyTxId));
        Optional.ofNullable(counterCurrencyExtraData).ifPresent(e -> builder.setCounterCurrencyExtraData(counterCurrencyExtraData));
        Optional.ofNullable(payoutTxHex).ifPresent(e -> builder.setPayoutTxHex(payoutTxHex));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        Optional.ofNullable(paymentAccountKey).ifPresent(e -> builder.setPaymentAccountKey(ByteString.copyFrom(e)));

        return getNetworkEnvelopeBuilder().setPaymentSentMessage(builder).build();
    }

    public static PaymentSentMessage fromProto(protobuf.PaymentSentMessage proto,
                                                                  String messageVersion) {
        return new PaymentSentMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyTxId()),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.stringOrNullFromProto(proto.getPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()),
                ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey())
        );
    }


    @Override
    public String toString() {
        return "PaymentSentMessage{" +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     counterCurrencyTxId=" + counterCurrencyTxId +
                ",\n     counterCurrencyExtraData=" + counterCurrencyExtraData +
                ",\n     uid='" + uid + '\'' +
                ",\n     payoutTxHex=" + payoutTxHex +
                ",\n     updatedMultisigHex=" + updatedMultisigHex +
                ",\n     paymentAccountKey=" + paymentAccountKey +
                "\n} " + super.toString();
    }
}
