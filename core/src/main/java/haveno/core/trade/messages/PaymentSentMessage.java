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

import com.google.protobuf.ByteString;
import haveno.common.app.Version;
import haveno.common.proto.ProtoUtil;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Getter
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
    @Nullable
    private AccountAgeWitness sellerAccountAgeWitness;
    @Setter
    @Nullable
    private byte[] buyerSignature;

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
                                                 @Nullable byte[] paymentAccountKey,
                                                 AccountAgeWitness sellerAccountAgeWitness) {
        this(tradeId,
                senderNodeAddress,
                counterCurrencyTxId,
                counterCurrencyExtraData,
                uid,
                Version.getP2PMessageVersion(),
                signedPayoutTxHex,
                updatedMultisigHex,
                paymentAccountKey,
                sellerAccountAgeWitness);
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
                                                  @Nullable byte[] paymentAccountKey,
                                                  AccountAgeWitness sellerAccountAgeWitness) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.counterCurrencyTxId = counterCurrencyTxId;
        this.counterCurrencyExtraData = counterCurrencyExtraData;
        this.payoutTxHex = signedPayoutTxHex;
        this.updatedMultisigHex = updatedMultisigHex;
        this.paymentAccountKey = paymentAccountKey;
        this.sellerAccountAgeWitness = sellerAccountAgeWitness;
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
        Optional.ofNullable(buyerSignature).ifPresent(e -> builder.setBuyerSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(sellerAccountAgeWitness).ifPresent(e -> builder.setSellerAccountAgeWitness(sellerAccountAgeWitness.toProtoAccountAgeWitness()));

        return getNetworkEnvelopeBuilder().setPaymentSentMessage(builder).build();
    }

    public static PaymentSentMessage fromProto(protobuf.PaymentSentMessage proto,
                                                                  String messageVersion) {

        protobuf.AccountAgeWitness protoAccountAgeWitness = proto.getSellerAccountAgeWitness();
        AccountAgeWitness accountAgeWitness = protoAccountAgeWitness.getHash().isEmpty() ? null : AccountAgeWitness.fromProto(protoAccountAgeWitness);

        PaymentSentMessage message = new PaymentSentMessage(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyTxId()),
                ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.stringOrNullFromProto(proto.getPayoutTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()),
                ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()),
                accountAgeWitness
        );
        message.setBuyerSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getBuyerSignature()));
        return message;
    }


    @Override
    public String toString() {
        return "PaymentSentMessage{" +
                ",\n     tradeId=" + tradeId +
                ",\n     uid='" + uid + '\'' +
                ",\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     counterCurrencyTxId=" + counterCurrencyTxId +
                ",\n     counterCurrencyExtraData=" + counterCurrencyExtraData +
                ",\n     payoutTxHex=" + payoutTxHex +
                ",\n     updatedMultisigHex=" + updatedMultisigHex +
                ",\n     paymentAccountKey=" + paymentAccountKey +
                ",\n     buyerSignature=" + buyerSignature +
                "\n} " + super.toString();
    }
}
