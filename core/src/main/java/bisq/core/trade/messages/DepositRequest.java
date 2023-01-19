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
import com.google.protobuf.ByteString;
import java.util.Optional;
import javax.annotation.Nullable;
import bisq.common.proto.ProtoUtil;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositRequest extends TradeMessage implements DirectMessage {
    private final long currentDate;
    private final String contractSignature;
    private final String depositTxHex;
    private final String depositTxKey;
    @Nullable
    private final byte[] paymentAccountKey;

    public DepositRequest(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String contractSignature,
                                     String depositTxHex,
                                     String depositTxKey,
                                     @Nullable byte[] paymentAccountKey) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.contractSignature = contractSignature;
        this.depositTxHex = depositTxHex;
        this.depositTxKey = depositTxKey;
        this.paymentAccountKey = paymentAccountKey;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DepositRequest.Builder builder = protobuf.DepositRequest.newBuilder()
                .setTradeId(tradeId)
                .setUid(uid)
                .setContractSignature(contractSignature)
                .setDepositTxHex(depositTxHex)
                .setDepositTxKey(depositTxKey);
        builder.setCurrentDate(currentDate);
        Optional.ofNullable(paymentAccountKey).ifPresent(e -> builder.setPaymentAccountKey(ByteString.copyFrom(e)));

        return getNetworkEnvelopeBuilder().setDepositRequest(builder).build();
    }

    public static DepositRequest fromProto(protobuf.DepositRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new DepositRequest(proto.getTradeId(),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getContractSignature(),
                proto.getDepositTxHex(),
                proto.getDepositTxKey(),
                ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()));
    }

    @Override
    public String toString() {
        return "DepositRequest {" +
                ",\n     currentDate=" + currentDate +
                ",\n     contractSignature=" + contractSignature +
                ",\n     depositTxHex='" + depositTxHex +
                ",\n     depositTxKey='" + depositTxKey +
                ",\n     paymentAccountKey='" + paymentAccountKey +
                "\n} " + super.toString();
    }
}
