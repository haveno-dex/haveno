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
import haveno.common.proto.ProtoUtil;
import haveno.common.util.Utilities;
import haveno.core.proto.CoreProtoResolver;
import haveno.network.p2p.DirectMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignContractResponse extends TradeMessage implements DirectMessage {
    private final long currentDate;
    private final String contractAsJson;
    private final byte[] contractSignature;
    private final byte[] encryptedPaymentAccountPayload;

    public SignContractResponse(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String contractAsJson,
                                     byte[] contractSignature,
                                     @Nullable byte[] encryptedPaymentAccountPayload) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.contractAsJson = contractAsJson;
        this.contractSignature = contractSignature;
        this.encryptedPaymentAccountPayload = encryptedPaymentAccountPayload;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignContractResponse.Builder builder = protobuf.SignContractResponse.newBuilder()
                .setTradeId(tradeId)
                .setUid(uid);

        Optional.ofNullable(contractAsJson).ifPresent(e -> builder.setContractAsJson(contractAsJson));
        Optional.ofNullable(contractSignature).ifPresent(e -> builder.setContractSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(encryptedPaymentAccountPayload).ifPresent(e -> builder.setEncryptedPaymentAccountPayload(ByteString.copyFrom(e)));

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setSignContractResponse(builder).build();
    }

    public static SignContractResponse fromProto(protobuf.SignContractResponse proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new SignContractResponse(proto.getTradeId(),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()),
                ProtoUtil.byteArrayOrNullFromProto(proto.getContractSignature()),
                proto.getEncryptedPaymentAccountPayload().toByteArray());
    }

    @Override
    public String toString() {
        return "SignContractResponse {" +
                ",\n     currentDate=" + currentDate +
                ",\n     contractAsJson='" + contractAsJson +
                ",\n     contractSignature='" + Utilities.bytesAsHexString(contractSignature) +
                "\n} " + super.toString();
    }
}
