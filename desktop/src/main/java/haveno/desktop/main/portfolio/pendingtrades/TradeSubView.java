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

import com.jfoenix.controls.JFXBadge;
import haveno.core.locale.Res;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeWizardItem;
import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.Subscription;
import org.fxmisc.easybind.EasyBind;

@Slf4j
public abstract class TradeSubView extends VBox {
    protected final PendingTradesViewModel model;
    protected final TradeStepInfo tradeStepInfo;
    private final HBox summary = new HBox(14);
    private final HBox steps = new HBox(14);
    private final VBox contentPane = new VBox();
    private TradeStepView tradeStepView;
    Subscription viewStateSubscription;
    private PendingTradesView.ChatCallback chatCallback;
    private Runnable closeCallback;
    private Runnable stepChangedCallback;
    private ListChangeListener<ChatMessage> chatListener;
    private JFXBadge chatBadge;
    private String openChatTradeId;
    private Trade trade;
    private boolean active;
    private boolean completed;
    private Subscription payoutSubscription;

    public TradeSubView(PendingTradesViewModel model) {
        this.model = model;
        tradeStepInfo = new TradeStepInfo();
        setSpacing(16);
        setMinWidth(0);
        getStyleClass().add("trade-detail-view");
        getStylesheets().add(TradeSubView.class.getResource("trade-view.css").toExternalForm());
        summary.setAlignment(Pos.CENTER_LEFT);
        summary.getStyleClass().add("trade-summary");
        steps.getStyleClass().add("trade-steps");
        steps.setAlignment(Pos.CENTER_LEFT);
        steps.setMinHeight(Region.USE_PREF_SIZE);
        addWizards();
        VBox workspace = new VBox(steps, contentPane);
        workspace.getStyleClass().add("trade-workspace");
        workspace.setMinWidth(0);
        workspace.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(summary, workspace);
    }

    protected void activate() {
        active = true;
        trade = model.dataModel.getTrade();
        buildSummary();
        payoutSubscription = EasyBind.subscribe(trade.payoutStateProperty(), state -> Platform.runLater(() -> {
            if (active) updateChatAvailability();
        }));
        updateChatAvailability();
        chatListener = change -> Platform.runLater(() -> {
            if (active) updateChatBadge();
        });
        trade.getChatMessages().addListener(chatListener);
        updateChatBadge();
    }

    protected void deactivate() {
        active = false;
        if (payoutSubscription != null) {
            payoutSubscription.unsubscribe();
            payoutSubscription = null;
        }
        if (viewStateSubscription != null) viewStateSubscription.unsubscribe();
        if (tradeStepView != null) tradeStepView.deactivate();
        if (trade != null && chatListener != null) trade.getChatMessages().removeListener(chatListener);
    }

    private void buildSummary() {
        String titleKey = trade.isArbitrator() ? "portfolio.pending.tradeView.arbitratorSummary" :
                trade.isBuyer() ? "portfolio.pending.tradeView.buySummary" : "portfolio.pending.tradeView.sellSummary";
        String roleKey = trade.isArbitrator() ? "portfolio.pending.tradeView.arbitratorRole" :
                trade.isBuyer() ? "portfolio.pending.tradeView.buyerRole" : "portfolio.pending.tradeView.sellerRole";
        Label title = new Label(Res.get(titleKey, model.getTradeVolume(), model.getFiatVolume()));
        title.getStyleClass().add("trade-summary-title");
        title.setWrapText(true);
        Label detail = new Label(Res.get("portfolio.pending.tradeView.summaryDetail", Res.get(trade.getOffer().getPaymentMethod().getId()),
                trade.getShortId(), Res.get(roleKey)) + " · " + model.getTradePrice());
        detail.getStyleClass().add("trade-secondary");
        detail.setWrapText(true);
        VBox text = new VBox(5, title, detail);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        AutoTooltipButton chat = new AutoTooltipButton(Res.get("portfolio.pending.support.button.getHelp"));
        chat.getStyleClass().add("trade-chat-button");
        chat.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.COMMENT_OUTLINE, "16"));
        chat.getGraphic().getStyleClass().add("trade-chat-icon");
        chat.setGraphicTextGap(10);
        chat.setOnAction(event -> {
            if (!completed && !trade.isPayoutPublished() && chatCallback != null) chatCallback.onOpenChat(trade);
            updateChatBadge();
        });
        chatBadge = new JFXBadge(chat, Pos.TOP_RIGHT);
        chatBadge.setMinWidth(Region.USE_PREF_SIZE);
        summary.getChildren().setAll(text, chatBadge);
    }

    private void updateChatAvailability() {
        if (chatBadge == null) return;
        boolean available = !completed && !trade.isPayoutPublished();
        chatBadge.setVisible(available);
        chatBadge.setManaged(available);
        chatBadge.setDisable(!available);
    }

    void setOpenChatTradeId(String tradeId) {
        openChatTradeId = tradeId;
        updateChatBadge();
    }

    void updateChatBadge() {
        if (chatBadge == null) return;
        long unread;
        synchronized (trade.getChatMessages()) {
            unread = trade.getChatMessages().stream().filter(message -> !message.isWasDisplayed() && !message.isSystemMessage()).count();
        }
        if (openChatTradeId != null && openChatTradeId.equals(trade.getId())) unread = 0;
        chatBadge.setText(unread == 0 ? "" : Long.toString(unread));
        chatBadge.setEnabled(unread > 0);
        chatBadge.refreshBadge();
    }

    void showItem(TradeWizardItem item) {
        item.setActive();
        createAndAddTradeStepView(item);
    }

    protected abstract void addWizards();

    protected void onViewStateChanged(PendingTradesViewModel.State viewState) {
        tradeStepInfo.setTrade(model.dataModel.getTrade());
        completed = viewState == PendingTradesViewModel.BuyerState.STEP4 || viewState == PendingTradesViewModel.SellerState.STEP4;
        updateChatAvailability();
    }

    void addWizardsToGridPane(TradeWizardItem item) {
        item.setPrefWidth(0);
        HBox.setHgrow(item, Priority.ALWAYS);
        steps.getChildren().add(item);
    }

    void addLineSeparatorToGridPane() {
        ((TradeWizardItem) steps.getChildren().get(steps.getChildren().size() - 1)).addConnector();
    }

    private void createAndAddTradeStepView(TradeWizardItem item) {
        Class<? extends TradeStepView> viewClass = item.getViewClass();
        boolean changedStep = tradeStepView != null && tradeStepView.getClass() != viewClass;
        boolean expanded = tradeStepView != null && tradeStepView.isDepositDetailsExpanded();
        if (tradeStepView != null) tradeStepView.deactivate();
        try {
            tradeStepView = viewClass.getDeclaredConstructor(PendingTradesViewModel.class).newInstance(model);
            tradeStepView.setDepositDetailsExpanded(expanded);
            tradeStepView.setStepCaptionHandler(item::setCaption);
            tradeStepView.setStepWarningHandler(item::setWarningCaption);
            contentPane.getChildren().setAll(tradeStepView);
            tradeStepView.setTradeStepInfo(tradeStepInfo);
            tradeStepView.setChatCallback(selectedTrade -> {
                if (chatCallback != null) chatCallback.onOpenChat(selectedTrade);
                updateChatBadge();
            });
            tradeStepView.setCloseCallback(() -> {
                if (closeCallback != null) closeCallback.run();
            });
            tradeStepView.activate();
            if (changedStep && stepChangedCallback != null) stepChangedCallback.run();
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

    void setStepChangedCallback(Runnable stepChangedCallback) {
        this.stepChangedCallback = stepChangedCallback;
    }
}
