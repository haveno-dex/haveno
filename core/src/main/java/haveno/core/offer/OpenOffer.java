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

package haveno.core.offer;

import haveno.common.proto.ProtoUtil;
import haveno.core.trade.Tradable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@EqualsAndHashCode
public final class OpenOffer implements Tradable {

    public enum State {
        PENDING,
        AVAILABLE,
        RESERVED,
        CLOSED,
        CANCELED,
        DEACTIVATED
    }

    @Getter
    private final Offer offer;
    @Getter
    private State state;
    @Setter
    @Getter
    private boolean reserveExactAmount;
    @Setter
    @Getter
    @Nullable
    private String scheduledAmount;
    @Setter
    @Getter
    @Nullable
    private List<String> scheduledTxHashes;
    @Setter
    @Getter
    @Nullable
    String splitOutputTxHash;
    @Getter
    @Setter
    long splitOutputTxFee;
    @Nullable
    @Setter
    @Getter
    private String reserveTxHash;
    @Nullable
    @Setter
    @Getter
    private String reserveTxHex;
    @Nullable
    @Setter
    @Getter
    private String reserveTxKey;
    @Getter
    @Setter
    private String challenge;
    @Getter
    private final long triggerPrice;
    @Getter
    @Setter
    transient private long mempoolStatus = -1;
    transient final private ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(state);
    @Getter
    @Setter
    transient boolean isProcessing = false;
    @Getter
    @Setter
    transient int numProcessingAttempts = 0;
    @Getter
    @Setter
    private boolean deactivatedByTrigger;
    @Getter
    @Setter
    private String groupId;

    public OpenOffer(Offer offer) {
        this(offer, 0, false);
    }

    public OpenOffer(Offer offer, long triggerPrice) {
        this(offer, triggerPrice, false);
    }

    public OpenOffer(Offer offer, long triggerPrice, boolean reserveExactAmount) {
        this.offer = offer;
        this.triggerPrice = triggerPrice;
        this.reserveExactAmount = reserveExactAmount;
        this.challenge = offer.getChallenge();
        this.groupId = UUID.randomUUID().toString();
        state = State.PENDING;
    }

    public OpenOffer(Offer offer, long triggerPrice, OpenOffer openOffer) {
        this.offer = offer;
        this.triggerPrice = triggerPrice;

        // copy open offer fields
        this.state = openOffer.state;
        this.reserveExactAmount = openOffer.reserveExactAmount;
        this.scheduledAmount = openOffer.scheduledAmount;
        this.scheduledTxHashes = openOffer.scheduledTxHashes == null ? null : new ArrayList<String>(openOffer.scheduledTxHashes);
        this.splitOutputTxHash = openOffer.splitOutputTxHash;
        this.splitOutputTxFee = openOffer.splitOutputTxFee;
        this.reserveTxHash = openOffer.reserveTxHash;
        this.reserveTxHex = openOffer.reserveTxHex;
        this.reserveTxKey = openOffer.reserveTxKey;
        this.challenge = openOffer.challenge;
        this.deactivatedByTrigger = openOffer.deactivatedByTrigger;
        this.groupId = openOffer.groupId;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OpenOffer(Offer offer,
                      State state,
                      long triggerPrice,
                      boolean reserveExactAmount,
                      @Nullable String scheduledAmount,
                      @Nullable List<String> scheduledTxHashes,
                      String splitOutputTxHash,
                      long splitOutputTxFee,
                      @Nullable String reserveTxHash,
                      @Nullable String reserveTxHex,
                      @Nullable String reserveTxKey,
                      @Nullable String challenge,
                      boolean deactivatedByTrigger,
                      @Nullable String groupId) {
        this.offer = offer;
        this.state = state;
        this.triggerPrice = triggerPrice;
        this.reserveExactAmount = reserveExactAmount;
        this.scheduledTxHashes = scheduledTxHashes;
        this.splitOutputTxHash = splitOutputTxHash;
        this.splitOutputTxFee = splitOutputTxFee;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKey = reserveTxKey;
        this.challenge = challenge;
        this.deactivatedByTrigger = deactivatedByTrigger;
        if (groupId == null) groupId = UUID.randomUUID().toString(); // initialize groupId if not set (added in v1.0.19)
        this.groupId = groupId;

        // reset reserved state to available
        if (this.state == State.RESERVED) setState(State.AVAILABLE);
    }

    @Override
    public protobuf.Tradable toProtoMessage() {
        protobuf.OpenOffer.Builder builder = protobuf.OpenOffer.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTriggerPrice(triggerPrice)
                .setState(protobuf.OpenOffer.State.valueOf(state.name()))
                .setSplitOutputTxFee(splitOutputTxFee)
                .setReserveExactAmount(reserveExactAmount)
                .setDeactivatedByTrigger(deactivatedByTrigger);

        Optional.ofNullable(scheduledAmount).ifPresent(e -> builder.setScheduledAmount(scheduledAmount));
        Optional.ofNullable(scheduledTxHashes).ifPresent(e -> builder.addAllScheduledTxHashes(scheduledTxHashes));
        Optional.ofNullable(splitOutputTxHash).ifPresent(e -> builder.setSplitOutputTxHash(splitOutputTxHash));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(challenge).ifPresent(e -> builder.setChallenge(challenge));
        Optional.ofNullable(groupId).ifPresent(e -> builder.setGroupId(groupId));

        return protobuf.Tradable.newBuilder().setOpenOffer(builder).build();
    }

    public static Tradable fromProto(protobuf.OpenOffer proto) {
        OpenOffer openOffer = new OpenOffer(Offer.fromProto(proto.getOffer()),
                ProtoUtil.enumFromProto(OpenOffer.State.class, proto.getState().name()),
                proto.getTriggerPrice(),
                proto.getReserveExactAmount(),
                proto.getScheduledAmount(),
                proto.getScheduledTxHashesList(),
                ProtoUtil.stringOrNullFromProto(proto.getSplitOutputTxHash()),
                proto.getSplitOutputTxFee(),
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxHash()),
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxHex()),
                ProtoUtil.stringOrNullFromProto(proto.getReserveTxKey()),
                ProtoUtil.stringOrNullFromProto(proto.getChallenge()),
                proto.getDeactivatedByTrigger(),
                ProtoUtil.stringOrNullFromProto(proto.getGroupId()));
        return openOffer;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Date getDate() {
        return offer.getDate();
    }

    @Override
    public String getId() {
        return offer.getId();
    }

    @Override
    public String getShortId() {
        return offer.getShortId();
    }

    public void setState(State state) {
        this.state = state;
        stateProperty.set(state);
        if (state == State.AVAILABLE) {
            deactivatedByTrigger = false;
        }
    }

    public void deactivate(boolean deactivatedByTrigger) {
        this.deactivatedByTrigger = deactivatedByTrigger;
        setState(State.DEACTIVATED);
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return stateProperty;
    }

    public boolean isPending() {
        return state == State.PENDING;
    }

    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    public boolean isDeactivated() {
        return state == State.DEACTIVATED;
    }

    public boolean isCanceled() {
        return state == State.CANCELED;
    }

    @Override
    public String toString() {
        return "OpenOffer{" +
                ",\n     offer=" + offer +
                ",\n     state=" + state +
                ",\n     triggerPrice=" + triggerPrice +
                ",\n     reserveExactAmount=" + reserveExactAmount +
                ",\n     scheduledAmount=" + scheduledAmount +
                ",\n     splitOutputTxFee=" + splitOutputTxFee +
                ",\n     groupId=" + groupId +
                "\n}";
    }
}

