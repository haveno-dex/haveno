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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
public final class PrepareMultisigRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final long tradeAmount;
    private final long tradePrice;
    private final long txFee;
    private final long tradeFee;
    private final String preparedMultisigHex;
    private final String payoutAddressString;
    private final PubKeyRing pubKeyRing;
    private final PaymentAccountPayload paymentAccountPayload;
    private final String accountId; // TODO (woodser): accountId needed?
    private final String tradeFeeTxId;
    private final List<NodeAddress> acceptedArbitratorNodeAddresses;
    @Nullable
    private final NodeAddress arbitratorNodeAddress;

    // added in v 0.6. can be null if we trade with an older peer
    @Nullable
    private final byte[] accountAgeWitnessSignatureOfOfferId;
    private final long currentDate;

    public PrepareMultisigRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     long tradeAmount,
                                     long tradePrice,
                                     long txFee,
                                     long tradeFee,
                                     String preparedMultisigHex,
                                     String payoutAddressString,
                                     PubKeyRing pubKeyRing,
                                     PaymentAccountPayload paymentAccountPayload,
                                     String accountId,
                                     String tradeFeeTxId,
                                     List<NodeAddress> acceptedArbitratorNodeAddresses,
                                     NodeAddress arbitratorNodeAddress,
                                     String uid,
                                     int messageVersion,
                                     @Nullable byte[] accountAgeWitnessSignatureOfOfferId,
                                     long currentDate) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.txFee = txFee;
        this.tradeFee = tradeFee;
        this.preparedMultisigHex = preparedMultisigHex;
        this.payoutAddressString = payoutAddressString;
        this.pubKeyRing = pubKeyRing;
        this.paymentAccountPayload = paymentAccountPayload;
        this.accountId = accountId;
        this.tradeFeeTxId = tradeFeeTxId;
        this.acceptedArbitratorNodeAddresses = acceptedArbitratorNodeAddresses;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.accountAgeWitnessSignatureOfOfferId = accountAgeWitnessSignatureOfOfferId;
        this.currentDate = currentDate;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.PrepareMultisigRequest.Builder builder = protobuf.PrepareMultisigRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setTxFee(txFee)
                .setTradeFee(tradeFee)
                .setPreparedMultisigHex(preparedMultisigHex)
                .setPayoutAddressString(payoutAddressString)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setPaymentAccountPayload((protobuf.PaymentAccountPayload) paymentAccountPayload.toProtoMessage())
                .setAccountId(accountId)
                .setTradeFeeTxId(tradeFeeTxId)
                .addAllAcceptedArbitratorNodeAddresses(acceptedArbitratorNodeAddresses.stream()
                        .map(NodeAddress::toProtoMessage).collect(Collectors.toList()))
                .setUid(uid);

        Optional.ofNullable(accountAgeWitnessSignatureOfOfferId).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfOfferId(ByteString.copyFrom(e)));
        Optional.ofNullable(arbitratorNodeAddress).ifPresent(e -> builder.setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage()));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setPrepareMultisigRequest(builder).build();
    }

    public static PrepareMultisigRequest fromProto(protobuf.PrepareMultisigRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        List<NodeAddress> acceptedArbitratorNodeAddresses = proto.getAcceptedArbitratorNodeAddressesList().stream()
                .map(NodeAddress::fromProto).collect(Collectors.toList());
        return new PrepareMultisigRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                proto.getTxFee(),
                proto.getTradeFee(),
                proto.getPreparedMultisigHex(),
                proto.getPayoutAddressString(),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                coreProtoResolver.fromProto(proto.getPaymentAccountPayload()),
                proto.getAccountId(),
                proto.getTradeFeeTxId(),
                acceptedArbitratorNodeAddresses,
                NodeAddress.fromProto(proto.getArbitratorNodeAddress()),
                proto.getUid(),
                messageVersion,
                ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignatureOfOfferId()),
                proto.getCurrentDate());
    }

    @Override
    public String toString() {
        return "PrepareMultisigRequest{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     txFee=" + txFee +
                ",\n     takerFee=" + tradeFee +
                ",\n     takerPreparedMultisigHex='" + preparedMultisigHex + '\'' +
                ",\n     takerPayoutAddressString='" + payoutAddressString + '\'' +
                ",\n     takerPubKeyRing=" + pubKeyRing +
                ",\n     takerPaymentAccountPayload=" + paymentAccountPayload +
                ",\n     takerAccountId='" + accountId + '\'' +
                ",\n     takerFeeTxId='" + tradeFeeTxId + '\'' +
                ",\n     acceptedArbitratorNodeAddresses=" + acceptedArbitratorNodeAddresses +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     accountAgeWitnessSignatureOfOfferId=" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfOfferId) +
                ",\n     currentDate=" + currentDate +
                "\n} " + super.toString();
    }
}
