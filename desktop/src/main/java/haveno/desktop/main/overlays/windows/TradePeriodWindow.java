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

package haveno.desktop.main.overlays.windows;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.apache.commons.lang3.StringUtils;
import java.util.concurrent.TimeUnit;

public class TradePeriodWindow extends ReviewInfoWindow<TradePeriodWindow> {
    private final Trade trade;
    private Timer timer;
    private Label timeLeft;
    private Label timeLeftSuffix;
    private Label phaseTitle;
    private Label phaseBody;
    private Label disputeTitle;
    private Label disputeBody;
    private Label deadline;
    private Pane track;
    private Region fill;
    private Region marker;
    private Region halfway;

    public TradePeriodWindow(Trade trade) {
        this.trade = trade;
        headLine = Res.get(isEnded() ? "popup.tradePeriod.ended.title" : "popup.tradePeriod.half.title");
        actionButtonText = Res.get("popup.tradePeriod.openTrade");
    }

    @Override
    public String toString() {
        return super.toString() + ": " + trade.getId();
    }

    private boolean isEnded() {
        return System.currentTimeMillis() >= trade.getMaxTradePeriodDate().getTime();
    }

    @Override
    protected void addMessage() {
        VBox header = createHeader(MaterialDesignIcon.CLOCK_ALERT, headLine, Res.get("popup.tradePeriod.subtitle", trade.getShortId(),
                StringUtils.capitalize(Res.get(trade.isBuyer() ? "shared.buy" : "shared.sell")), HavenoUtils.formatXmr(trade.getAmount(), true), HeroWindowComponents.volume(trade.getVolume()) + " " + trade.getOffer().getCounterCurrencyCode()));
        timeLeft = HeroWindowComponents.label("", "review-time-left");
        deadline = HeroWindowComponents.label("", "review-pill");
        deadline.setGraphic(HeroWindowComponents.icon(MaterialDesignIcon.CALENDAR_CLOCK, "14", "glyph-icon"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        timeLeftSuffix = HeroWindowComponents.label(Res.get("popup.tradePeriod.remaining"), "review-strip-label");
        HBox remaining = new HBox(6, timeLeft, timeLeftSuffix);
        remaining.setAlignment(Pos.BASELINE_LEFT);
        HBox top = new HBox(10, remaining, spacer, deadline);
        top.setAlignment(Pos.CENTER_LEFT);
        track = new Pane();
        track.setMinHeight(14);
        track.setPrefHeight(14);
        Region background = new Region();
        background.getStyleClass().add("review-track");
        background.prefWidthProperty().bind(track.widthProperty());
        background.setPrefHeight(8);
        background.setLayoutY(3);
        fill = new Region();
        fill.getStyleClass().add("review-track-fill");
        fill.setPrefHeight(8);
        fill.setLayoutY(3);
        halfway = new Region();
        halfway.getStyleClass().add("review-track-mid");
        halfway.setPrefSize(2, 14);
        marker = new Region();
        marker.getStyleClass().add("review-track-marker");
        marker.setPrefSize(14, 14);
        track.getChildren().addAll(background, fill, halfway, marker);
        track.widthProperty().addListener((o, oldValue, newValue) -> updateTrack());
        HBox captions = new HBox(8);
        for (String caption : new String[]{Res.get("popup.tradePeriod.started", HeroWindowComponents.dateTime(trade.getStartDate())),
                Res.get("popup.tradePeriod.halfway"), Res.get("popup.tradePeriod.end")}) {
            Label label = HeroWindowComponents.label(caption, "review-timeline-caption");
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            captions.getChildren().add(label);
        }
        ((Label) captions.getChildren().get(1)).setAlignment(Pos.CENTER);
        ((Label) captions.getChildren().get(2)).setAlignment(Pos.CENTER_RIGHT);
        VBox timeline = new VBox(8, top, track, captions);
        timeline.getStyleClass().add("review-timeline");
        VBox.setMargin(track, new Insets(4, 0, 0, 0));
        HBox phase = HeroWindowComponents.featureRow(MaterialDesignIcon.TIMER_SAND, ACCENT_PRIMARY, "", null);
        phaseTitle = (Label) HeroWindowComponents.rowText(phase).getChildren().get(0);
        phaseBody = HeroWindowComponents.label("", "hero-feature-body");
        HeroWindowComponents.rowText(phase).getChildren().add(phaseBody);
        HBox dispute = HeroWindowComponents.featureRow(MaterialDesignIcon.SCALE_BALANCE, ACCENT_GREEN, "", null);
        disputeTitle = (Label) HeroWindowComponents.rowText(dispute).getChildren().get(0);
        disputeBody = HeroWindowComponents.label("", "hero-feature-body");
        HeroWindowComponents.rowText(dispute).getChildren().add(disputeBody);
        addReview(isEnded() ? "hero-accent-red" : ACCENT_AMBER, header, timeline, HeroWindowComponents.rows(phase,
                HeroWindowComponents.featureRow(MaterialDesignIcon.MESSAGE_TEXT, ACCENT_PURPLE,
                        Res.get("popup.tradePeriod.chat.title"), Res.get("popup.tradePeriod.chat.body")), dispute));
        update();
    }

    @Override
    public void display() {
        if (isDisplayed) return;
        update();
        super.display();
        if (isDisplayed) timer = UserThread.runPeriodically(this::update, 1, TimeUnit.MINUTES);
    }

    @Override
    protected void onHidden() {
        if (timer != null) timer.stop();
        timer = null;
        super.onHidden();
    }

    private void update() {
        boolean ended = isEnded();
        gridPane.getStyleClass().removeAll(ACCENT_AMBER, "hero-accent-red");
        gridPane.getStyleClass().add(ended ? "hero-accent-red" : ACCENT_AMBER);
        headerTitle.setText(Res.get(ended ? "popup.tradePeriod.ended.title" : "popup.tradePeriod.half.title"));
        timeLeft.setText(ended ? Res.get("popup.tradePeriod.elapsed") : duration(trade.getMaxTradePeriodDate().getTime() - System.currentTimeMillis()));
        timeLeftSuffix.setVisible(!ended);
        timeLeftSuffix.setManaged(!ended);
        deadline.setText(Res.get(ended ? "popup.tradePeriod.ended" : "popup.tradePeriod.ends", HeroWindowComponents.dateTime(trade.getMaxTradePeriodDate())));
        String phase = !trade.isDepositsUnlocked() ? "waitDeposits" : trade.isPaymentReceived() ? "waitPayout" : trade.isPaymentSent() ? "waitSeller" : "waitBuyer";
        phaseTitle.setText(Res.get("popup.tradePeriod." + phase + ".title"));
        phaseBody.setText(Res.get("popup.tradePeriod." + phase + ".body"));
        disputeTitle.setText(Res.get(ended ? "popup.tradePeriod.dispute.ended.title" : "popup.tradePeriod.dispute.title"));
        disputeBody.setText(Res.get(ended ? "popup.tradePeriod.dispute.ended.body" : "popup.tradePeriod.dispute.body"));
        updateTrack();
    }

    private void updateTrack() {
        long start = trade.getStartDate().getTime();
        long end = trade.getMaxTradePeriodDate().getTime();
        double progress = end <= start ? 1 : Math.max(0, Math.min(1, (double) (System.currentTimeMillis() - start) / (end - start)));
        double width = track.getWidth();
        fill.setPrefWidth(width * progress);
        halfway.setLayoutX(width / 2 - 1);
        marker.setLayoutX(Math.max(0, Math.min(width - 14, width * progress - 7)));
    }

    private static String duration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long days = hours / 24;
        if (days > 0) return Res.get(days == 1 ? "popup.tradePeriod.duration.day" : "popup.tradePeriod.duration.days", days, hours % 24);
        return Res.get("popup.tradePeriod.duration.hours", hours, TimeUnit.MILLISECONDS.toMinutes(millis) % 60);
    }
}
