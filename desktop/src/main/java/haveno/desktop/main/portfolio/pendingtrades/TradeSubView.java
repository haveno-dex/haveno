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

package haveno.desktop.main.portfolio.pendingtrades;

import haveno.core.locale.Res;
import haveno.core.trade.Trade;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.SimpleMarkdownLabel;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeWizardItem;
import haveno.desktop.util.Layout;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Separator;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.Subscription;

import static haveno.desktop.util.FormBuilder.addButtonAfterGroup;
import static haveno.desktop.util.FormBuilder.addSimpleMarkdownLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;

@Slf4j
public abstract class TradeSubView extends HBox {
    protected final PendingTradesViewModel model;
    protected VBox leftVBox;
    private AnchorPane contentPane;
    private TradeStepView tradeStepView;
    protected TradeStepInfo tradeStepInfo;
    private GridPane leftGridPane;
    private TitledGroupBg tradeProcessTitledGroupBg;
    private int leftGridPaneRowIndex = 0;
    Subscription viewStateSubscription;
    private PendingTradesView.ChatCallback chatCallback;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradeSubView(PendingTradesViewModel model) {
        this.model = model;

        setSpacing(Layout.PADDING_WINDOW);
        buildViews();
    }

    protected void activate() {
    }

    protected void deactivate() {
        if (viewStateSubscription != null)
            viewStateSubscription.unsubscribe();

        if (tradeStepView != null)
            tradeStepView.deactivate();

        if (tradeStepInfo != null)
            tradeStepInfo.removeItselfFrom(leftGridPane);
    }

    private void buildViews() {
        addLeftBox();
        addContentPane();

        leftGridPane = new GridPane();
        leftGridPane.setMaxWidth(255);
        leftGridPane.setHgap(Layout.GRID_GAP);
        leftGridPane.setVgap(Layout.GRID_GAP);
        //VBox.setMargin(leftGridPane, new Insets(0, 10, 10, 10));
        leftVBox.getChildren().add(leftGridPane);

        leftGridPaneRowIndex = 0;
        tradeProcessTitledGroupBg = addTitledGroupBg(leftGridPane, leftGridPaneRowIndex, 1, Res.get("portfolio.pending.tradeProcess"));
        tradeProcessTitledGroupBg.getStyleClass().add("last");

        addWizards();

        TitledGroupBg titledGroupBg = addTitledGroupBg(leftGridPane, ++leftGridPaneRowIndex, 1, "", 10);
        titledGroupBg.getStyleClass().add("last");

        SimpleMarkdownLabel label = addSimpleMarkdownLabel(leftGridPane, ++leftGridPaneRowIndex);
        AutoTooltipButton button = (AutoTooltipButton) addButtonAfterGroup(leftGridPane, ++leftGridPaneRowIndex, "");
        SimpleMarkdownLabel footerLabel = addSimpleMarkdownLabel(leftGridPane, ++leftGridPaneRowIndex, Res.get("portfolio.pending.stillNotResolved"), 10);
        footerLabel.getStyleClass().add("medium-text");
        tradeStepInfo = new TradeStepInfo(titledGroupBg, label, button, footerLabel);
    }

    void showItem(TradeWizardItem item) {
        item.setActive();
        createAndAddTradeStepView(item.getViewClass());
    }

    protected abstract void addWizards();

    protected void onViewStateChanged(PendingTradesViewModel.State viewState) {
        tradeStepInfo.setTrade(model.getTrade());
    }

    void addWizardsToGridPane(TradeWizardItem tradeWizardItem) {
        if (leftGridPaneRowIndex == 0)
            GridPane.setMargin(tradeWizardItem, new Insets(Layout.FIRST_ROW_DISTANCE + Layout.FLOATING_LABEL_DISTANCE, 0, 0, 0));

        GridPane.setRowIndex(tradeWizardItem, leftGridPaneRowIndex++);
        leftGridPane.getChildren().add(tradeWizardItem);
        GridPane.setRowSpan(tradeProcessTitledGroupBg, leftGridPaneRowIndex);
        GridPane.setFillWidth(tradeWizardItem, true);
    }

    void addLineSeparatorToGridPane() {
        final Separator separator = new Separator(Orientation.VERTICAL);
        separator.setMinHeight(10);
        GridPane.setMargin(separator, new Insets(0, 0, 0, 13));
        GridPane.setHalignment(separator, HPos.LEFT);
        GridPane.setRowIndex(separator, leftGridPaneRowIndex++);
        leftGridPane.getChildren().add(separator);
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
            tradeStepView.activate();
        } catch (Exception e) {
            log.error("Creating viewClass {} caused an error {}", viewClass, e.getMessage());
            e.printStackTrace();
        }
    }

    private void addLeftBox() {
        leftVBox = new VBox();
        leftVBox.setSpacing(Layout.SPACING_V_BOX);
        leftVBox.setMinWidth(290);
        getChildren().add(leftVBox);
    }

    private void addContentPane() {
        contentPane = new AnchorPane();
        HBox.setHgrow(contentPane, Priority.SOMETIMES);
        getChildren().add(contentPane);
    }


    public interface ChatCallback {
        void onOpenChat(Trade trade);
    }

    public void setChatCallback(PendingTradesView.ChatCallback chatCallback) {
        this.chatCallback = chatCallback;
    }
}


