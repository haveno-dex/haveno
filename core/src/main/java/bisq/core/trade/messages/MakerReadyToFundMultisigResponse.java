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

import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;
import bisq.network.p2p.DirectMessage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public class MakerReadyToFundMultisigResponse extends TradeMessage implements DirectMessage {
    @Getter
    private final boolean isMakerReadyToFundMultisig;
    @Getter
    @Nullable
    private final String makerContractAsJson;
    @Getter
    @Nullable
    private final String makerContractSignature;
    @Getter
    @Nullable
    private final String makerPayoutAddressString;
    @Getter
    @Nullable
    private final PaymentAccountPayload makerPaymentAccountPayload;
    @Getter
    @Nullable
    private final String makerAccountId;
    @Getter
    private final long currentDate;
    
    public MakerReadyToFundMultisigResponse(String tradeId,
                                     boolean isMakerReadyToFundMultisig,
                                     String uid,
                                     int messageVersion,
                                     String makerContractAsJson,
                                     String makerContractSignature,
                                     String makerPayoutAddressString,
                                     PaymentAccountPayload makerPaymentAccountPayload,
                                     String makerAccountId,
                                     long currentDate) {
        super(messageVersion, tradeId, uid);
        this.isMakerReadyToFundMultisig = isMakerReadyToFundMultisig;
        this.makerContractAsJson = makerContractAsJson;
        this.makerContractSignature = makerContractSignature;
        this.makerPayoutAddressString = makerPayoutAddressString;
        this.makerPaymentAccountPayload = makerPaymentAccountPayload;
        this.makerAccountId = makerAccountId;
        this.currentDate = currentDate;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.MakerReadyToFundMultisigResponse.Builder builder = protobuf.MakerReadyToFundMultisigResponse.newBuilder()
                .setTradeId(tradeId)
                .setIsMakerReadyToFundMultisig(isMakerReadyToFundMultisig)
                .setCurrentDate(currentDate);
        
        Optional.ofNullable(makerContractAsJson).ifPresent(e -> builder.setMakerContractAsJson(makerContractAsJson));
        Optional.ofNullable(makerContractSignature).ifPresent(e -> builder.setMakerContractSignature(makerContractSignature));
        Optional.ofNullable(makerPayoutAddressString).ifPresent(e -> builder.setMakerPayoutAddressString(makerPayoutAddressString));
        Optional.ofNullable(makerPaymentAccountPayload).ifPresent(e -> builder.setMakerPaymentAccountPayload((protobuf.PaymentAccountPayload) makerPaymentAccountPayload.toProtoMessage()));
        Optional.ofNullable(makerAccountId).ifPresent(e -> builder.setMakerAccountId(makerAccountId));
        
        return getNetworkEnvelopeBuilder().setMakerReadyToFundMultisigResponse(builder).build();
    }
    
    public static MakerReadyToFundMultisigResponse fromProto(protobuf.MakerReadyToFundMultisigResponse proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new MakerReadyToFundMultisigResponse(proto.getTradeId(),
                proto.getIsMakerReadyToFundMultisig(),
                proto.getUid(),
                messageVersion,
                proto.getMakerContractAsJson(),
                proto.getMakerContractSignature(),
                proto.getMakerPayoutAddressString(),
                coreProtoResolver.fromProto(proto.getMakerPaymentAccountPayload()),
                proto.getMakerAccountId(),
                proto.getCurrentDate());
    }

    @Override
    public String toString() {
        return "MakerReadyToFundMultisigResponse{" +
                "\n     isMakerReadyToFundMultisig=" + isMakerReadyToFundMultisig +
                "\n} " + super.toString();
    }
}
