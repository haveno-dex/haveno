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

import com.google.protobuf.ByteString;

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.util.Utilities;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;
import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class InitTradeRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final long tradeAmount;
    private final long tradePrice;
    private final long txFee;
    private final long tradeFee;
    private final String payoutAddressString;
    private final PaymentAccountPayload paymentAccountPayload;
    private final PubKeyRing pubKeyRing;
    private final String accountId;
    @Nullable
    private final String tradeFeeTxId;
    private final NodeAddress arbitratorNodeAddress;
    
    // added in v 0.6. can be null if we trade with an older peer
    @Nullable
    private final byte[] accountAgeWitnessSignatureOfOfferId;
    private final long currentDate;
    
    // added for XMR integration
    private final NodeAddress takerNodeAddress;
    private final NodeAddress makerNodeAddress;

    public InitTradeRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     long tradeAmount,
                                     long tradePrice,
                                     long txFee,
                                     long tradeFee,
                                     String payoutAddressString,
                                     PaymentAccountPayload paymentAccountPayload,
                                     String accountId,
                                     String tradeFeeTxId,
                                     String uid,
                                     int messageVersion,
                                     @Nullable byte[] accountAgeWitnessSignatureOfOfferId,
                                     long currentDate,
                                     NodeAddress takerNodeAddress,
                                     NodeAddress makerNodeAddress,
                                     NodeAddress arbitratorNodeAddress) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.paymentAccountPayload = paymentAccountPayload;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.txFee = txFee;
        this.tradeFee = tradeFee;
        this.payoutAddressString = payoutAddressString;
        this.accountId = accountId;
        this.tradeFeeTxId = tradeFeeTxId;
        this.accountAgeWitnessSignatureOfOfferId = accountAgeWitnessSignatureOfOfferId;
        this.currentDate = currentDate;
        this.takerNodeAddress = takerNodeAddress;
        this.makerNodeAddress = makerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
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
                .setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setTxFee(txFee)
                .setTradeFee(tradeFee)
                .setPayoutAddressString(payoutAddressString)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setPaymentAccountPayload((protobuf.PaymentAccountPayload) paymentAccountPayload.toProtoMessage())
                .setAccountId(accountId)
                .setUid(uid);

        Optional.ofNullable(tradeFeeTxId).ifPresent(e -> builder.setTradeFeeTxId(tradeFeeTxId));
        Optional.ofNullable(accountAgeWitnessSignatureOfOfferId).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfOfferId(ByteString.copyFrom(e)));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitTradeRequest(builder).build();
    }
    
    public static InitTradeRequest fromProto(protobuf.InitTradeRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new InitTradeRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                proto.getTxFee(),
                proto.getTradeFee(),
                proto.getPayoutAddressString(),
                coreProtoResolver.fromProto(proto.getPaymentAccountPayload()),
                proto.getAccountId(),
                ProtoUtil.stringOrNullFromProto(proto.getTradeFeeTxId()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignatureOfOfferId()),
                proto.getCurrentDate(),
                NodeAddress.fromProto(proto.getTakerNodeAddress()),
                NodeAddress.fromProto(proto.getMakerNodeAddress()),
                NodeAddress.fromProto(proto.getArbitratorNodeAddress()));
    }

    @Override
    public String toString() {
        return "InitTradeRequest{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     txFee=" + txFee +
                ",\n     takerFee=" + tradeFee +
                ",\n     payoutAddressString='" + payoutAddressString + '\'' +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     paymentAccountPayload=" + paymentAccountPayload +
                ",\n     paymentAccountPayload='" + accountId + '\'' +
                ",\n     takerFeeTxId='" + tradeFeeTxId + '\'' +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     accountAgeWitnessSignatureOfOfferId=" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfOfferId) +
                ",\n     currentDate=" + currentDate +
                "\n} " + super.toString();
    }
}
