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
    TradeProtocolVersion tradeProtocolVersion;
    private final long tradeAmount;
    private final long tradePrice;
    private final String paymentMethodId;
    @Nullable
    private final String makerAccountId;
    private final String takerAccountId;
    private final String makerPaymentAccountId;
    private final String takerPaymentAccountId;
    private final PubKeyRing takerPubKeyRing;
    @Nullable
    private final byte[] accountAgeWitnessSignatureOfOfferId;
    private final long currentDate;
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

    public InitTradeRequest(TradeProtocolVersion tradeProtocolVersion,
                                    String offerId,
                                    long tradeAmount,
                                    long tradePrice,
                                    String paymentMethodId,
                                    @Nullable String makerAccountId,
                                    String takerAccountId,
                                    String makerPaymentAccountId,
                                    String takerPaymentAccountId,
                                    PubKeyRing takerPubKeyRing,
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
                                    @Nullable String payoutAddress) {
        super(messageVersion, offerId, uid);
        this.tradeProtocolVersion = tradeProtocolVersion;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.makerAccountId = makerAccountId;
        this.takerAccountId = takerAccountId;
        this.makerPaymentAccountId = makerPaymentAccountId;
        this.takerPaymentAccountId = takerPaymentAccountId;
        this.takerPubKeyRing = takerPubKeyRing;
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
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////


	@Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.InitTradeRequest.Builder builder = protobuf.InitTradeRequest.newBuilder()
                .setTradeProtocolVersion(TradeProtocolVersion.toProtoMessage(tradeProtocolVersion))
                .setOfferId(offerId)
                .setTakerNodeAddress(takerNodeAddress.toProtoMessage())
                .setMakerNodeAddress(makerNodeAddress.toProtoMessage())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setTakerPubKeyRing(takerPubKeyRing.toProtoMessage())
                .setMakerPaymentAccountId(makerPaymentAccountId)
                .setTakerPaymentAccountId(takerPaymentAccountId)
                .setPaymentMethodId(paymentMethodId)
                .setTakerAccountId(takerAccountId)
                .setUid(uid);

        Optional.ofNullable(makerAccountId).ifPresent(e -> builder.setMakerAccountId(makerAccountId));
        Optional.ofNullable(arbitratorNodeAddress).ifPresent(e -> builder.setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage()));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(payoutAddress).ifPresent(e -> builder.setPayoutAddress(payoutAddress));
        Optional.ofNullable(accountAgeWitnessSignatureOfOfferId).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfOfferId(ByteString.copyFrom(e)));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitTradeRequest(builder).build();
    }

    public static InitTradeRequest fromProto(protobuf.InitTradeRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new InitTradeRequest(TradeProtocolVersion.fromProto(proto.getTradeProtocolVersion()),
                proto.getOfferId(),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                proto.getPaymentMethodId(),
                ProtoUtil.stringOrNullFromProto(proto.getMakerAccountId()),
                proto.getTakerAccountId(),
                proto.getMakerPaymentAccountId(),
                proto.getTakerPaymentAccountId(),
                PubKeyRing.fromProto(proto.getTakerPubKeyRing()),
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
                ProtoUtil.stringOrNullFromProto(proto.getPayoutAddress()));
    }

    @Override
    public String toString() {
        return "InitTradeRequest{" +
                "\n     tradeProtocolVersion=" + tradeProtocolVersion +
                ",\n     offerId=" + offerId +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     paymentMethodId=" + paymentMethodId +
                ",\n     makerAccountId=" + makerAccountId +
                ",\n     takerAccountId=" + takerAccountId +
                ",\n     makerPaymentAccountId=" + makerPaymentAccountId +
                ",\n     takerPaymentAccountId=" + takerPaymentAccountId +
                ",\n     takerPubKeyRing=" + takerPubKeyRing +
                ",\n     accountAgeWitnessSignatureOfOfferId=" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfOfferId) +
                ",\n     currentDate=" + currentDate +
                ",\n     makerNodeAddress=" + makerNodeAddress +
                ",\n     takerNodeAddress=" + takerNodeAddress +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     reserveTxHash=" + reserveTxHash +
                ",\n     reserveTxHex=" + reserveTxHex +
                ",\n     reserveTxKey=" + reserveTxKey +
                ",\n     payoutAddress=" + payoutAddress +
                "\n} " + super.toString();
    }
}
