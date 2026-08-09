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

package haveno.desktop.main.portfolio.pendingtrades;

import static com.google.common.base.Preconditions.checkNotNull;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeUtil;
import haveno.core.util.FormattingUtils;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.SimpleMarkdownLabel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeWizardItem;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.FormBuilder;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
public abstract class TradeSubView extends VBox {
    protected final PendingTradesViewModel model;
    protected final Trade trade;
    protected TradeStepInfo tradeStepInfo;
    private final List<TradeWizardItem> wizardItems = new ArrayList<>();
    private AnchorPane contentPane;
    private TradeStepView tradeStepView;
    private HBox timelineBox;
    private HBox countdownRow;
    private Label remainingTimeLabel;
    private ProgressBar timeLeftProgressBar;
    private final ClockWatcher.Listener clockListener;
    private Subscription tradeStateSubscription;
    Subscription viewStateSubscription;
    private PendingTradesView.ChatCallback chatCallback;
    private Runnable closeCallback;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradeSubView(PendingTradesViewModel model) {
        this.model = model;
        this.trade = checkNotNull(model.dataModel.getTrade(), "Trade must not be null at TradeSubView");
        setSpacing(10);
        buildViews();

        clockListener = new ClockWatcher.Listener() {
            @Override
            public void onSecondTick() {
            }

            @Override
            public void onMinuteTick() {
                updateTimeLeft();
            }
        };
    }

    protected void activate() {
        model.clockWatcher.addListener(clockListener);
        tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), state -> UserThread.execute(this::updateTimeLeft));
    }

    protected void deactivate() {
        if (viewStateSubscription != null)
            viewStateSubscription.unsubscribe();

        if (tradeStateSubscription != null)
            tradeStateSubscription.unsubscribe();

        model.clockWatcher.removeListener(clockListener);

        if (tradeStepView != null)
            tradeStepView.deactivate();
    }

    private void buildViews() {
        addWizards();
        getChildren().addAll(createHeroCard(), createSupportBand(), createContentPane());
        updateTimeLeft();
    }

    // summary card: amount, chips, step timeline and trade period countdown
    private VBox createHeroCard() {
        Label amountLabel = new Label(model.getTradeVolume());
        amountLabel.getStyleClass().add("trade-hero-amount");
        String fiatVolume = model.getFiatVolume();
        String priceLine = FormattingUtils.formatPrice(trade.getPrice()) + " "
                + CurrencyUtil.getCurrencyPair(checkNotNull(trade.getOffer()).getCounterCurrencyCode());
        Label fiatLabel = new Label(fiatVolume.isEmpty() ? priceLine : fiatVolume + "  ·  " + priceLine);
        fiatLabel.getStyleClass().add("trade-hero-fiat");
        VBox amountBox = new VBox(2, amountLabel, fiatLabel);

        Text chatIcon = FormBuilder.getIcon(MaterialDesignIcon.COMMENT_MULTIPLE_OUTLINE, "1.4em");
        chatIcon.getStyleClass().add("trade-hero-chat-icon");
        AutoTooltipButton chatButton = new AutoTooltipButton("", chatIcon);
        chatButton.getStyleClass().add("trade-hero-chat-button");
        chatButton.setTooltip(new Tooltip(Res.get("tradeChat.openChat")));
        Accessibility.nameFromTooltip(chatButton);
        chatButton.setOnAction(e -> {
            if (chatCallback != null)
                chatCallback.onOpenChat(trade);
        });

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox topRow = new HBox(16, amountBox, topSpacer, chatButton);
        topRow.setAlignment(Pos.CENTER_LEFT);

        FlowPane chipsRow = new FlowPane(8, 6,
                createChip(TradeUtil.getRole(trade)),
                createChip(trade.getOffer().getPaymentMethodNameWithCountryCode()),
                createChip(Res.get("shared.tradeId") + ": " + trade.getShortId()));

        Region separator = new Region();
        separator.getStyleClass().add("hero-separator");

        Label remainingTitleLabel = new Label(Res.get("portfolio.pending.remainingTime"));
        remainingTitleLabel.getStyleClass().add("trade-hero-remaining-title");
        remainingTimeLabel = new Label();
        remainingTimeLabel.getStyleClass().add("trade-hero-remaining-value");
        Region countdownSpacer = new Region();
        HBox.setHgrow(countdownSpacer, Priority.ALWAYS);
        countdownRow = new HBox(16, remainingTitleLabel, countdownSpacer, remainingTimeLabel);
        countdownRow.setAlignment(Pos.CENTER_LEFT);

        timeLeftProgressBar = new ProgressBar(0);
        timeLeftProgressBar.getStyleClass().add("trade-hero-progress");
        timeLeftProgressBar.setMaxWidth(Double.MAX_VALUE);

        VBox heroCard = new VBox(14, topRow, chipsRow, separator, timelineBox, countdownRow, timeLeftProgressBar);
        heroCard.getStyleClass().addAll("trade-hero-card", "hero-accent-primary");
        return heroCard;
    }

    private Label createChip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("trade-hero-chip");
        return chip;
    }

    // help/dispute band; state and texts are driven by TradeStepInfo and the active step view
    private HBox createSupportBand() {
        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("trade-support-title");
        SimpleMarkdownLabel messageLabel = new SimpleMarkdownLabel(null);
        SimpleMarkdownLabel footerLabel = new SimpleMarkdownLabel(Res.get("portfolio.pending.stillNotResolved"));
        footerLabel.getStyleClass().add("medium-text");
        AutoTooltipButton button = new AutoTooltipButton("");

        VBox textBox = new VBox(4, titleLabel, messageLabel, footerLabel);
        HBox.setHgrow(textBox, Priority.ALWAYS);
        HBox band = new HBox(16, textBox, button);
        band.setAlignment(Pos.CENTER_LEFT);
        band.getStyleClass().add("trade-support-band");
        band.visibleProperty().bind(titleLabel.visibleProperty());
        band.managedProperty().bind(titleLabel.visibleProperty());

        tradeStepInfo = new TradeStepInfo(titleLabel, messageLabel, button, footerLabel);
        return band;
    }

    private AnchorPane createContentPane() {
        contentPane = new AnchorPane();
        return contentPane;
    }

    void addWizardItems(TradeWizardItem... items) {
        timelineBox = new HBox(8);
        timelineBox.getStyleClass().add("trade-timeline");
        timelineBox.setAlignment(Pos.CENTER);
        for (TradeWizardItem item : items) {
            if (!wizardItems.isEmpty()) {
                Region connector = new Region();
                connector.getStyleClass().add("trade-step-connector");
                HBox.setHgrow(connector, Priority.ALWAYS);
                wizardItems.get(wizardItems.size() - 1).stateProperty().addListener((observable, oldState, newState) -> {
                    connector.getStyleClass().remove("completed");
                    if (newState == TradeWizardItem.State.COMPLETED)
                        connector.getStyleClass().add("completed");
                });
                timelineBox.getChildren().add(connector);
            }
            wizardItems.add(item);
            timelineBox.getChildren().add(item);
        }
    }

    void showItem(TradeWizardItem item) {
        item.setActive();
        createAndAddTradeStepView(item.getViewClass());
    }

    protected abstract void addWizards();

    protected void onViewStateChanged(PendingTradesViewModel.State viewState) {
        tradeStepInfo.setTrade(model.getTrade());
    }

    private void updateTimeLeft() {
        if (model.dataModel.getTrade() == null || !trade.isInitialized()) return;

        // completed trades no longer have a running trade period
        boolean showCountdown = !trade.isPayoutPublished();
        countdownRow.setVisible(showCountdown);
        countdownRow.setManaged(showCountdown);
        timeLeftProgressBar.setVisible(showCountdown);
        timeLeftProgressBar.setManaged(showCountdown);
        if (!showCountdown) return;

        String remainingTime = model.getRemainingTradeDurationAsWords();
        timeLeftProgressBar.setProgress(model.getRemainingTradeDurationAsPercentage());
        if (!remainingTime.isEmpty()) {
            remainingTimeLabel.setText(trade.isDepositsFinalized() ?
                    Res.get("portfolio.pending.remainingTimeDetail", remainingTime, model.getDateForOpenDispute()) :
                    Res.get("portfolio.pending.remainingTimeDetail.startsAfter", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
            setCountdownError(model.showWarning() || model.showDispute());
        } else {
            remainingTimeLabel.setText(Res.get("portfolio.pending.tradeNotCompleted", model.getDateForOpenDispute()));
            setCountdownError(true);
        }
    }

    private void setCountdownError(boolean error) {
        remainingTimeLabel.getStyleClass().remove("error-text");
        timeLeftProgressBar.getStyleClass().remove("error");
        if (error) {
            remainingTimeLabel.getStyleClass().add("error-text");
            timeLeftProgressBar.getStyleClass().add("error");
        }
    }

    private void createAndAddTradeStepView(Class<? extends TradeStepView> viewClass) {
        if (tradeStepView != null)
            tradeStepView.deactivate();
        try {
            tradeStepView = viewClass.getDeclaredConstructor(PendingTradesViewModel.class).newInstance(model);
            contentPane.getChildren().setAll(tradeStepView);
            tradeStepView.setTradeStepInfo(tradeStepInfo);
            ChatCallback chatCallback = trade -> {
                // call up the chain to open chat
                if (this.chatCallback != null) {
                    this.chatCallback.onOpenChat(trade);
                }
            };
            tradeStepView.setChatCallback(chatCallback);
            tradeStepView.setCloseCallback(() -> {
                if (closeCallback != null) closeCallback.run();
            });
            tradeStepView.activate();
        } catch (Exception e) {
            log.error("Creating viewClass {} caused an error {}\n", viewClass, e.getMessage(), e);
        }
    }


    public interface ChatCallback {
        void onOpenChat(Trade trade);
    }

    public void setChatCallback(PendingTradesView.ChatCallback chatCallback) {
        this.chatCallback = chatCallback;
    }

    public void setCloseCallback(Runnable closeCallback) {
        this.closeCallback = closeCallback;
    }
}
