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

package haveno.core.support.dispute;

import com.google.protobuf.ByteString;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.util.Utilities;
import haveno.core.support.messages.ChatMessage;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Date;
import java.util.Optional;

@EqualsAndHashCode
@Getter
@Slf4j
public final class DisputeResult implements NetworkPayload {

    public enum Winner {
        BUYER,
        SELLER
    }

    public enum Reason {
        OTHER,
        BUG,
        USABILITY,
        SCAM,               // Not used anymore
        PROTOCOL_VIOLATION, // Not used anymore
        NO_REPLY,           // Not used anymore
        BANK_PROBLEMS,
        OPTION_TRADE,
        SELLER_NOT_RESPONDING,
        WRONG_SENDER_ACCOUNT,
        TRADE_ALREADY_SETTLED,
        PEER_WAS_LATE
    }

    public enum SubtractFeeFrom {
        BUYER_ONLY,
        SELLER_ONLY,
        BUYER_AND_SELLER
    }

    private final String tradeId;
    private final int traderId;
    @Setter
    @Nullable
    private Winner winner;
    private int reasonOrdinal = Reason.OTHER.ordinal();
    @Setter
    @Nullable
    private SubtractFeeFrom subtractFeeFrom;
    private final BooleanProperty tamperProofEvidenceProperty = new SimpleBooleanProperty();
    private final BooleanProperty idVerificationProperty = new SimpleBooleanProperty();
    private final BooleanProperty screenCastProperty = new SimpleBooleanProperty();
    private final StringProperty summaryNotesProperty = new SimpleStringProperty("");
    @Setter
    @Nullable
    private ChatMessage chatMessage;
    @Setter
    @Nullable
    private byte[] arbitratorSignature;
    private long buyerPayoutAmountBeforeCost;
    private long sellerPayoutAmountBeforeCost;
    @Setter
    @Nullable
    private byte[] arbitratorPubKey;
    private long closeDate;

    public DisputeResult(String tradeId, int traderId) {
        this.tradeId = tradeId;
        this.traderId = traderId;
    }

    public DisputeResult(String tradeId,
                         int traderId,
                         @Nullable Winner winner,
                         int reasonOrdinal,
                         @Nullable SubtractFeeFrom subtractFeeFrom,
                         boolean tamperProofEvidence,
                         boolean idVerification,
                         boolean screenCast,
                         String summaryNotes,
                         @Nullable ChatMessage chatMessage,
                         @Nullable byte[] arbitratorSignature,
                         long buyerPayoutAmountBeforeCost,
                         long sellerPayoutAmountBeforeCost,
                         @Nullable byte[] arbitratorPubKey,
                         long closeDate) {
        this.tradeId = tradeId;
        this.traderId = traderId;
        this.winner = winner;
        this.reasonOrdinal = reasonOrdinal;
        this.subtractFeeFrom = subtractFeeFrom;
        this.tamperProofEvidenceProperty.set(tamperProofEvidence);
        this.idVerificationProperty.set(idVerification);
        this.screenCastProperty.set(screenCast);
        this.summaryNotesProperty.set(summaryNotes);
        this.chatMessage = chatMessage;
        this.arbitratorSignature = arbitratorSignature;
        this.buyerPayoutAmountBeforeCost = buyerPayoutAmountBeforeCost;
        this.sellerPayoutAmountBeforeCost = sellerPayoutAmountBeforeCost;
        this.arbitratorPubKey = arbitratorPubKey;
        this.closeDate = closeDate;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static DisputeResult fromProto(protobuf.DisputeResult proto) {
        return new DisputeResult(proto.getTradeId(),
                proto.getTraderId(),
                ProtoUtil.enumFromProto(DisputeResult.Winner.class, proto.getWinner().name()),
                proto.getReasonOrdinal(),
                ProtoUtil.enumFromProto(DisputeResult.SubtractFeeFrom.class, proto.getSubtractFeeFrom().name()),
                proto.getTamperProofEvidence(),
                proto.getIdVerification(),
                proto.getScreenCast(),
                proto.getSummaryNotes(),
                proto.getChatMessage() == null ? null : ChatMessage.fromPayloadProto(proto.getChatMessage()),
                proto.getArbitratorSignature().toByteArray(),
                proto.getBuyerPayoutAmountBeforeCost(),
                proto.getSellerPayoutAmountBeforeCost(),
                proto.getArbitratorPubKey().toByteArray(),
                proto.getCloseDate());
    }

    @Override
    public protobuf.DisputeResult toProtoMessage() {
        final protobuf.DisputeResult.Builder builder = protobuf.DisputeResult.newBuilder()
                .setTradeId(tradeId)
                .setTraderId(traderId)
                .setReasonOrdinal(reasonOrdinal)
                .setTamperProofEvidence(tamperProofEvidenceProperty.get())
                .setIdVerification(idVerificationProperty.get())
                .setScreenCast(screenCastProperty.get())
                .setSummaryNotes(summaryNotesProperty.get())
                .setBuyerPayoutAmountBeforeCost(buyerPayoutAmountBeforeCost)
                .setSellerPayoutAmountBeforeCost(sellerPayoutAmountBeforeCost)
                .setCloseDate(closeDate);

        Optional.ofNullable(arbitratorSignature).ifPresent(arbitratorSignature -> builder.setArbitratorSignature(ByteString.copyFrom(arbitratorSignature)));
        Optional.ofNullable(arbitratorPubKey).ifPresent(arbitratorPubKey -> builder.setArbitratorPubKey(ByteString.copyFrom(arbitratorPubKey)));
        Optional.ofNullable(winner).ifPresent(result -> builder.setWinner(protobuf.DisputeResult.Winner.valueOf(winner.name())));
        Optional.ofNullable(subtractFeeFrom).ifPresent(result -> builder.setSubtractFeeFrom(protobuf.DisputeResult.SubtractFeeFrom.valueOf(subtractFeeFrom.name())));
        Optional.ofNullable(chatMessage).ifPresent(chatMessage ->
                builder.setChatMessage(chatMessage.toProtoNetworkEnvelope().getChatMessage()));

        return builder.build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BooleanProperty tamperProofEvidenceProperty() {
        return tamperProofEvidenceProperty;
    }

    public BooleanProperty idVerificationProperty() {
        return idVerificationProperty;
    }

    public BooleanProperty screenCastProperty() {
        return screenCastProperty;
    }

    public void setReason(Reason reason) {
        this.reasonOrdinal = reason.ordinal();
    }

    public Reason getReason() {
        if (reasonOrdinal < Reason.values().length)
            return Reason.values()[reasonOrdinal];
        else
            return Reason.OTHER;
    }

    public void setSummaryNotes(String summaryNotes) {
        this.summaryNotesProperty.set(summaryNotes);
    }

    public StringProperty summaryNotesProperty() {
        return summaryNotesProperty;
    }

    public void setBuyerPayoutAmountBeforeCost(BigInteger buyerPayoutAmountBeforeCost) {
        if (buyerPayoutAmountBeforeCost.compareTo(BigInteger.ZERO) < 0) throw new IllegalArgumentException("buyerPayoutAmountBeforeCost cannot be negative");
        this.buyerPayoutAmountBeforeCost = buyerPayoutAmountBeforeCost.longValueExact();
    }

    public BigInteger getBuyerPayoutAmountBeforeCost() {
        return BigInteger.valueOf(buyerPayoutAmountBeforeCost);
    }

    public void setSellerPayoutAmountBeforeCost(BigInteger sellerPayoutAmountBeforeCost) {
        if (sellerPayoutAmountBeforeCost.compareTo(BigInteger.ZERO) < 0) throw new IllegalArgumentException("sellerPayoutAmountBeforeCost cannot be negative");
        this.sellerPayoutAmountBeforeCost = sellerPayoutAmountBeforeCost.longValueExact();
    }

    public BigInteger getSellerPayoutAmountBeforeCost() {
        return BigInteger.valueOf(sellerPayoutAmountBeforeCost);
    }

    public void setCloseDate(Date closeDate) {
        this.closeDate = closeDate.getTime();
    }

    public Date getCloseDate() {
        return new Date(closeDate);
    }

    @Override
    public String toString() {
        return "DisputeResult{" +
                "\n     tradeId='" + tradeId + '\'' +
                ",\n     traderId=" + traderId +
                ",\n     winner=" + winner +
                ",\n     reasonOrdinal=" + reasonOrdinal +
                ",\n     subtractFeeFrom=" + subtractFeeFrom +
                ",\n     tamperProofEvidenceProperty=" + tamperProofEvidenceProperty +
                ",\n     idVerificationProperty=" + idVerificationProperty +
                ",\n     screenCastProperty=" + screenCastProperty +
                ",\n     summaryNotesProperty=" + summaryNotesProperty +
                ",\n     chatMessage=" + chatMessage +
                ",\n     arbitratorSignature=" + Utilities.bytesAsHexString(arbitratorSignature) +
                ",\n     buyerPayoutAmountBeforeCost=" + buyerPayoutAmountBeforeCost +
                ",\n     sellerPayoutAmountBeforeCost=" + sellerPayoutAmountBeforeCost +
                ",\n     arbitratorPubKey=" + Utilities.bytesAsHexString(arbitratorPubKey) +
                ",\n     closeDate=" + closeDate +
                "\n}";
    }
}
