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
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.util.Utilities;
import haveno.core.proto.CoreProtoResolver;
import haveno.network.p2p.DirectMessage;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class InitTradeRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final long tradeAmount;
    private final long tradePrice;
    private final long tradeFee;
    private final String accountId;
    private final String paymentAccountId;
    private final String paymentMethodId;
    private final PubKeyRing pubKeyRing;

    // added in v 0.6. can be null if we trade with an older peer
    @Nullable
    private final byte[] accountAgeWitnessSignatureOfOfferId;
    private final long currentDate;

    // XMR integration
    private final NodeAddress makerNodeAddress;
    private final NodeAddress takerNodeAddress;
    @Nullable
    private final NodeAddress arbitratorNodeAddress;
    @Nullable
    private final String reserveTxHash;
    @Nullable
    private final String reserveTxHex;
    @Nullable
    private final String reserveTxKey;
    @Nullable
    private final String payoutAddress;
    @Nullable
    private final byte[] makerSignature;

    public InitTradeRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     long tradeAmount,
                                     long tradePrice,
                                     long tradeFee,
                                     String accountId,
                                     String paymentAccountId,
                                     String paymentMethodId,
                                     String uid,
                                     String messageVersion,
                                     @Nullable byte[] accountAgeWitnessSignatureOfOfferId,
                                     long currentDate,
                                     NodeAddress makerNodeAddress,
                                     NodeAddress takerNodeAddress,
                                     NodeAddress arbitratorNodeAddress,
                                     @Nullable String reserveTxHash,
                                     @Nullable String reserveTxHex,
                                     @Nullable String reserveTxKey,
                                     @Nullable String payoutAddress,
                                     @Nullable byte[] makerSignature) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.tradeFee = tradeFee;
        this.accountId = accountId;
        this.paymentAccountId = paymentAccountId;
        this.paymentMethodId = paymentMethodId;
        this.accountAgeWitnessSignatureOfOfferId = accountAgeWitnessSignatureOfOfferId;
        this.currentDate = currentDate;
        this.makerNodeAddress = makerNodeAddress;
        this.takerNodeAddress = takerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKey = reserveTxKey;
        this.payoutAddress = payoutAddress;
        this.makerSignature = makerSignature;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.InitTradeRequest.Builder builder = protobuf.InitTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setTakerNodeAddress(takerNodeAddress.toProtoMessage())
                .setMakerNodeAddress(makerNodeAddress.toProtoMessage())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setTradeFee(tradeFee)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setPaymentAccountId(paymentAccountId)
                .setPaymentMethodId(paymentMethodId)
                .setAccountId(accountId)
                .setUid(uid);

        Optional.ofNullable(arbitratorNodeAddress).ifPresent(e -> builder.setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage()));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(payoutAddress).ifPresent(e -> builder.setPayoutAddress(payoutAddress));
        Optional.ofNullable(accountAgeWitnessSignatureOfOfferId).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfOfferId(ByteString.copyFrom(e)));
        Optional.ofNullable(makerSignature).ifPresent(e -> builder.setMakerSignature(ByteString.copyFrom(e)));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitTradeRequest(builder).build();
    }

    public static InitTradeRequest fromProto(protobuf.InitTradeRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new InitTradeRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                proto.getTradeFee(),
                proto.getAccountId(),
                proto.getPaymentAccountId(),
                proto.getPaymentMethodId(),
                proto.getUid(),
                messageVersion,
                ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignatureOfOfferId()),
                proto.getCurrentDate(),
                NodeAddress.fromProto(proto.getMakerNodeAddress()),
                NodeAddress.fromProto(proto.getTakerNodeAddress()),
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxHash()),
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxKey()),
                ProtoUtil.stringOrNullFromProto(proto.getPayoutAddress()),
                ProtoUtil.byteArrayOrNullFromProto(proto.getMakerSignature()));
    }

    @Override
    public String toString() {
        return "InitTradeRequest{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     tradeFee=" + tradeFee +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     accountId='" + accountId + '\'' +
                ",\n     paymentAccountId=" + paymentAccountId +
                ",\n     paymentMethodId=" + paymentMethodId +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     accountAgeWitnessSignatureOfOfferId=" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfOfferId) +
                ",\n     currentDate=" + currentDate +
                ",\n     reserveTxHash=" + reserveTxHash +
                ",\n     reserveTxHex=" + reserveTxHex +
                ",\n     reserveTxKey=" + reserveTxKey +
                ",\n     payoutAddress=" + payoutAddress +
                ",\n     makerSignature=" + (makerSignature == null ? null : Utilities.byteArrayToInteger(makerSignature)) +
                "\n} " + super.toString();
    }
}
