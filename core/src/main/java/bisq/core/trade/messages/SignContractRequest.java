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

import bisq.core.proto.CoreProtoResolver;

import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;
import com.google.protobuf.ByteString;
import bisq.common.crypto.PubKeyRing;

import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignContractRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final long currentDate;
    private final String accountId;
    private final byte[] paymentAccountPayloadHash;
    private final String payoutAddress;
    private final String depositTxHash;

    public SignContractRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String uid,
                                     int messageVersion,
                                     long currentDate,
                                     String accountId,
                                     byte[] paymentAccountPayloadHash,
                                     String payoutAddress,
                                     String depositTxHash) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.currentDate = currentDate;
        this.accountId = accountId;
        this.paymentAccountPayloadHash = paymentAccountPayloadHash;
        this.payoutAddress = payoutAddress;
        this.depositTxHash = depositTxHash;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignContractRequest.Builder builder = protobuf.SignContractRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setUid(uid)
                .setAccountId(accountId)
                .setPaymentAccountPayloadHash(ByteString.copyFrom(paymentAccountPayloadHash))
                .setPayoutAddress(payoutAddress)
                .setDepositTxHash(depositTxHash);

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setSignContractRequest(builder).build();
    }

    public static SignContractRequest fromProto(protobuf.SignContractRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new SignContractRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getAccountId(),
                proto.getPaymentAccountPayloadHash().toByteArray(),
                proto.getPayoutAddress(),
                proto.getDepositTxHash());
    }

    @Override
    public String toString() {
        return "SignContractRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     accountId=" + accountId +
                ",\n     paymentAccountPayloadHash='" + paymentAccountPayloadHash +
                ",\n     payoutAddress='" + payoutAddress +
                ",\n     depositTxHash='" + depositTxHash +
                "\n} " + super.toString();
    }
}
