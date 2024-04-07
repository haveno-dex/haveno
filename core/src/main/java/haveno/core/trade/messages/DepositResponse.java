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
    private final long buyerSecurityDeposit;
    private final long sellerSecurityDeposit;

    public DepositResponse(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String errorMessage,
                                     long buyerSecurityDeposit,
                                     long sellerSecurityDeposit) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.errorMessage = errorMessage;
        this.buyerSecurityDeposit = buyerSecurityDeposit;
        this.sellerSecurityDeposit = sellerSecurityDeposit;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.DepositResponse.Builder builder = protobuf.DepositResponse.newBuilder()
                .setTradeId(offerId)
                .setUid(uid);
        builder.setCurrentDate(currentDate);
        builder.setBuyerSecurityDeposit(buyerSecurityDeposit);
        builder.setSellerSecurityDeposit(sellerSecurityDeposit);
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
                ProtoUtil.stringOrNullFromProto(proto.getErrorMessage()),
                proto.getBuyerSecurityDeposit(),
                proto.getSellerSecurityDeposit());
    }

    @Override
    public String toString() {
        return "DepositResponse {" +
                ",\n     currentDate=" + currentDate +
                ",\n     errorMessage=" + errorMessage +
                ",\n     buyerSecurityDeposit=" + buyerSecurityDeposit +
                ",\n     sellerSecurityDeposit=" + sellerSecurityDeposit +
                "\n} " + super.toString();
    }
}
