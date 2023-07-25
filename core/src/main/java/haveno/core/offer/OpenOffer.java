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

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.ProtoUtil;
import haveno.core.trade.Tradable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@EqualsAndHashCode
@Slf4j
public final class OpenOffer implements Tradable {
    // Timeout for offer reservation during takeoffer process. If deposit tx is not completed in that time we reset the offer to AVAILABLE state.
    private static final long TIMEOUT = 60;
    transient private Timer timeoutTimer;

    public enum State {
        SCHEDULED,
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
    private boolean splitOutput;
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
    private final long triggerPrice;
    @Getter
    @Setter
    transient private long mempoolStatus = -1;
    transient final private ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(state);

    public OpenOffer(Offer offer) {
        this(offer, 0, false);
    }

    public OpenOffer(Offer offer, long triggerPrice) {
        this(offer, triggerPrice, false);
    }

    public OpenOffer(Offer offer, long triggerPrice, boolean splitOutput) {
        this.offer = offer;
        this.triggerPrice = triggerPrice;
        this.splitOutput = splitOutput;
        state = State.SCHEDULED;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OpenOffer(Offer offer,
                      State state,
                      long triggerPrice,
                      boolean splitOutput,
                      @Nullable String scheduledAmount,
                      @Nullable List<String> scheduledTxHashes,
                      String splitOutputTxHash,
                      @Nullable String reserveTxHash,
                      @Nullable String reserveTxHex,
                      @Nullable String reserveTxKey) {
        this.offer = offer;
        this.state = state;
        this.triggerPrice = triggerPrice;
        this.splitOutput = splitOutput;
        this.scheduledTxHashes = scheduledTxHashes;
        this.splitOutputTxHash = splitOutputTxHash;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKey = reserveTxKey;

        if (this.state == State.RESERVED)
            setState(State.AVAILABLE);
    }

    @Override
    public protobuf.Tradable toProtoMessage() {
        protobuf.OpenOffer.Builder builder = protobuf.OpenOffer.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTriggerPrice(triggerPrice)
                .setState(protobuf.OpenOffer.State.valueOf(state.name()))
                .setSplitOutput(splitOutput);

        Optional.ofNullable(scheduledAmount).ifPresent(e -> builder.setScheduledAmount(scheduledAmount));
        Optional.ofNullable(scheduledTxHashes).ifPresent(e -> builder.addAllScheduledTxHashes(scheduledTxHashes));
        Optional.ofNullable(splitOutputTxHash).ifPresent(e -> builder.setSplitOutputTxHash(splitOutputTxHash));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));

        return protobuf.Tradable.newBuilder().setOpenOffer(builder).build();
    }

    public static Tradable fromProto(protobuf.OpenOffer proto) {
        OpenOffer openOffer = new OpenOffer(Offer.fromProto(proto.getOffer()),
                ProtoUtil.enumFromProto(OpenOffer.State.class, proto.getState().name()),
                proto.getTriggerPrice(),
                proto.getSplitOutput(),
                proto.getScheduledAmount(),
                proto.getScheduledTxHashesList(),
                ProtoUtil.stringOrNullFromProto(proto.getSplitOutputTxHash()),
                proto.getReserveTxHash(),
                proto.getReserveTxHex(),
                proto.getReserveTxKey());
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

        // We keep it reserved for a limited time, if trade preparation fails we revert to available state
        if (this.state == State.RESERVED) { // TODO (woodser): remove this?
            startTimeout();
        } else {
            stopTimeout();
        }
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return stateProperty;
    }

    public boolean isScheduled() {
        return state == State.SCHEDULED;
    }

    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    public boolean isDeactivated() {
        return state == State.DEACTIVATED;
    }

    private void startTimeout() {
        stopTimeout();

        timeoutTimer = UserThread.runAfter(() -> {
            log.debug("Timeout for resetting State.RESERVED reached");
            if (state == State.RESERVED) {
                // we do not need to persist that as at startup any RESERVED state would be reset to AVAILABLE anyway
                setState(State.AVAILABLE);
            }
        }, TIMEOUT);
    }

    private void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }


    @Override
    public String toString() {
        return "OpenOffer{" +
                ",\n     offer=" + offer +
                ",\n     state=" + state +
                ",\n     triggerPrice=" + triggerPrice +
                "\n}";
    }
}

