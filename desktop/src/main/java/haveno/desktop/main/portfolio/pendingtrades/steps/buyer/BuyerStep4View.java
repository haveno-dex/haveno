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

package haveno.desktop.main.portfolio.pendingtrades.steps.buyer;

import haveno.desktop.main.portfolio.pendingtrades.TradeFormPane;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.core.locale.Res;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.windows.TradeFeedbackWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.closedtrades.ClosedTradesView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.concurrent.TimeUnit;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;

public class BuyerStep4View extends TradeStepView {

    private Button closeButton;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerStep4View(PendingTradesViewModel model) {
        super(model);
    }

    @Override
    public void activate() {
        super.activate();
        // Don't display any trade step info when trade is complete
        hideTradeStepInfo();
    }

    @Override
    public void deactivate() {
        super.deactivate();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Content
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void addContent() {
        gridPane.getColumnConstraints().get(1).setHgrow(Priority.SOMETIMES);

        Label completedTradeLabel = new Label();
        if (trade.getDisputeState().isMediated()) {
            completedTradeLabel.setText(Res.get("portfolio.pending.step5_buyer.groupTitle.mediated"));
        } else if (trade.getDisputeState().isDisputed() && trade.getDisputeResult() != null) {
            completedTradeLabel.setText(Res.get("portfolio.pending.step5_buyer.groupTitle.arbitrated"));
        } else {
            completedTradeLabel.setText(Res.get("portfolio.pending.step5_buyer.groupTitle"));
        }

        completedTradeLabel.setWrapText(true);
        completedTradeLabel.getStyleClass().add("trade-action-title");
        completedTradeLabel.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.CHECK_CIRCLE, "22"));
        completedTradeLabel.getGraphic().getStyleClass().add("trade-completed-icon");
        completedTradeLabel.setGraphicTextGap(10);
        gridPane.add(completedTradeLabel, 0, gridRow, 2, 1);
        if (trade.isPaymentReceived()) {
            TradeFormPane summary = new TradeFormPane();
            addCompactTopLabelTextField(summary, 0, getXmrTradeAmountLabel(), model.getTradeVolume());
            addCompactTopLabelTextField(summary, 1, getTraditionalTradeAmountLabel(), model.getFiatVolume());
            addCompactTopLabelTextField(summary, 2, Res.get("portfolio.pending.step5_buyer.refunded"), model.getSecurityDeposit());
            addCompactTopLabelTextField(summary, 3, Res.get("portfolio.pending.step5_buyer.tradeFee"), model.getTradeFee());
            summary.finish(false);
            summary.getStyleClass().add("trade-completed-summary");
            gridPane.add(summary, 0, ++gridRow, 2, 1);
        }

        closeButton = new AutoTooltipButton(Res.get("shared.close"));
        closeButton.setDefaultButton(true);
        closeButton.getStyleClass().add("action-button");
        GridPane.setRowIndex(closeButton, ++gridRow);
        GridPane.setMargin(closeButton, new Insets(4, 0, 0, 0));
        gridPane.getChildren().add(closeButton);

        closeButton.setOnAction(e -> {
            handleTradeCompleted();
            model.dataModel.tradeManager.onTradeCompleted(trade);
        });

        String key = "tradeCompleted" + trade.getId();
        if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
            DontShowAgainLookup.dontShowAgain(key, true);
            new Notification().headLine(Res.get("notification.tradeCompleted.headline"))
                    .notification(Res.get("notification.tradeCompleted.msg"))
                    .autoClose()
                    .show();
        }
    }

    private void handleTradeCompleted() {
        if (closeCallback != null) closeCallback.run(); // move focus off the button before it's disabled and removed
        closeButton.setDisable(true);
        model.dataModel.xmrWalletService.swapAddressEntryToAvailable(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT);

        openTradeFeedbackWindow();
    }

    private void openTradeFeedbackWindow() {
        String key = "feedbackPopupAfterTrade";
        if (!DevEnv.isDevMode() && preferences.showAgain(key)) {
            UserThread.runAfter(() -> new TradeFeedbackWindow()
                    .dontShowAgainId(key)
                    .actionButtonTextWithGoTo("portfolio.tab.history")
                    .onAction(() -> model.dataModel.navigation.navigateTo(MainView.class, PortfolioView.class, ClosedTradesView.class))
                    .show(), 500, TimeUnit.MILLISECONDS);
        }
    }

    protected String getXmrTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_buyer.bought");
    }

    protected String getTraditionalTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_buyer.paid");
    }
}
