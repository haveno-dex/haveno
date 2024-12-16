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

import javax.annotation.Nullable;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Value
public final class InitMultisigRequest extends TradeMessage implements DirectMessage {
    private final long currentDate;
    @Nullable
    private final String preparedMultisigHex;
    @Nullable
    private final String madeMultisigHex;
    @Nullable
    private final String exchangedMultisigHex;
    @Nullable
    private final String tradeFeeAddress;

    public InitMultisigRequest(String tradeId,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String preparedMultisigHex,
                                     String madeMultisigHex,
                                     String exchangedMultisigHex,
                                     String tradeFeeAddress) {
        super(messageVersion, tradeId, uid);
        this.currentDate = currentDate;
        this.preparedMultisigHex = preparedMultisigHex;
        this.madeMultisigHex = madeMultisigHex;
        this.exchangedMultisigHex = exchangedMultisigHex;
        this.tradeFeeAddress = tradeFeeAddress;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.InitMultisigRequest.Builder builder = protobuf.InitMultisigRequest.newBuilder()
                .setTradeId(offerId)
                .setUid(uid);

        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(exchangedMultisigHex).ifPresent(e -> builder.setExchangedMultisigHex(exchangedMultisigHex));
        Optional.ofNullable(tradeFeeAddress).ifPresent(e -> builder.setTradeFeeAddress(tradeFeeAddress));

        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitMultisigRequest(builder).build();
    }

    public static InitMultisigRequest fromProto(protobuf.InitMultisigRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      String messageVersion) {
        return new InitMultisigRequest(proto.getTradeId(),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()),
                ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()),
                ProtoUtil.stringOrNullFromProto(proto.getExchangedMultisigHex()),
                ProtoUtil.stringOrNullFromProto(proto.getTradeFeeAddress()));
    }

    @Override
    public String toString() {
        return "InitMultisigRequest {" +
                ",\n     currentDate=" + currentDate +
                ",\n     preparedMultisigHex=" + preparedMultisigHex +
                ",\n     madeMultisigHex=" + madeMultisigHex +
                ",\n     exchangedMultisigHex=" + exchangedMultisigHex +
                ",\n     tradeFeeAddress=" + tradeFeeAddress +
                "\n} " + super.toString();
    }
}
