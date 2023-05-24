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

import haveno.common.proto.ProtoUtil;
import haveno.core.proto.CoreProtoResolver;
import haveno.network.p2p.DirectMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class DepositResponse extends TradeMessage implements DirectMessage {
    private final long currentDate;
    private final String errorMessage;

    public DepositResponse(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String errorMessage) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.errorMessage = errorMessage;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DepositResponse.Builder builder = protobuf.DepositResponse.newBuilder()
                .setTradeId(tradeId)
                .setUid(uid);
        builder.setCurrentDate(currentDate);
        Optional.ofNullable(errorMessage).ifPresent(e -> builder.setErrorMessage(errorMessage));

        return getNetworkEnvelopeBuilder().setDepositResponse(builder).build();
    }

    public static DepositResponse fromProto(protobuf.DepositResponse proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new DepositResponse(proto.getTradeId(),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getErrorMessage()));
    }

    @Override
    public String toString() {
        return "DepositResponse {" +
                ",\n     currentDate=" + currentDate +
                ",\n     errorMessage=" + errorMessage +
                "\n} " + super.toString();
    }
}
