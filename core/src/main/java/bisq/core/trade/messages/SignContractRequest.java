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

import bisq.core.proto.CoreProtoResolver;

import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;

import java.util.Optional;

import javax.annotation.Nullable;

import com.google.protobuf.ByteString;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.util.Utilities;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignContractRequest extends TradeMessage implements DirectMessage {
    private final long currentDate;
    private final String accountId;
    private final byte[] paymentAccountPayloadHash;
    private final String payoutAddress;
    private final String depositTxHash;
    private final byte[] accountAgeWitnessSignatureOfDepositHash;

    public SignContractRequest(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String accountId,
                                     byte[] paymentAccountPayloadHash,
                                     String payoutAddress,
                                     String depositTxHash,
                                     @Nullable byte[] accountAgeWitnessSignatureOfDepositHash) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.accountId = accountId;
        this.paymentAccountPayloadHash = paymentAccountPayloadHash;
        this.payoutAddress = payoutAddress;
        this.depositTxHash = depositTxHash;
        this.accountAgeWitnessSignatureOfDepositHash = accountAgeWitnessSignatureOfDepositHash;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignContractRequest.Builder builder = protobuf.SignContractRequest.newBuilder()
                .setTradeId(tradeId)
                .setUid(uid)
                .setAccountId(accountId)
                .setPaymentAccountPayloadHash(ByteString.copyFrom(paymentAccountPayloadHash))
                .setPayoutAddress(payoutAddress)
                .setDepositTxHash(depositTxHash);

        Optional.ofNullable(accountAgeWitnessSignatureOfDepositHash).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfDepositHash(ByteString.copyFrom(e)));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setSignContractRequest(builder).build();
    }

    public static SignContractRequest fromProto(protobuf.SignContractRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new SignContractRequest(proto.getTradeId(),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getAccountId(),
                proto.getPaymentAccountPayloadHash().toByteArray(),
                proto.getPayoutAddress(),
                proto.getDepositTxHash(),
                ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignatureOfDepositHash()));
    }

    @Override
    public String toString() {
        return "SignContractRequest {" +
                ",\n     currentDate=" + currentDate +
                ",\n     accountId=" + accountId +
                ",\n     paymentAccountPayloadHash='" + Utilities.bytesAsHexString(paymentAccountPayloadHash) +
                ",\n     payoutAddress='" + payoutAddress +
                ",\n     depositTxHash='" + depositTxHash +
                ",\n     accountAgeWitnessSignatureOfDepositHash='" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfDepositHash) +
                "\n} " + super.toString();
    }
}
