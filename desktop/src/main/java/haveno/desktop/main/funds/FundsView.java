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

package haveno.desktop.main.funds;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.desktop.Navigation;
import haveno.desktop.util.Accessibility;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.deposit.DepositView;
import haveno.desktop.main.funds.transactions.TransactionsView;
import haveno.desktop.main.funds.withdrawal.WithdrawalView;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableNumberValue;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

@FxmlView
public class FundsView extends ActivatableView<VBox, Void> {

    @FXML
    TabPane tabPane;
    @FXML
    StackPane headerControls, content;
    @FXML
    Region navigationSpacer;
    @FXML
    Separator headerSeparator;
    @FXML
    Tab depositTab, withdrawalTab, transactionsTab;

    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;
    private Tab currentTab;

    private final ViewLoader viewLoader;
    private final Navigation navigation;

    @Inject
    public FundsView(CachingViewLoader viewLoader, Navigation navigation) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
    }

    @Override
    public void initialize() {
        Accessibility.fixTabs(tabPane);
        depositTab.setText(Res.get("funds.tab.deposit"));
        withdrawalTab.setText(Res.get("funds.tab.withdrawal"));
        transactionsTab.setText(Res.get("funds.tab.transactions"));

        // preserve the tab content's clipping when a view exceeds the available space
        Rectangle contentClip = new Rectangle();
        contentClip.widthProperty().bind(content.widthProperty());
        contentClip.heightProperty().bind(content.heightProperty());
        content.setClip(contentClip);
        // retain tab shortcuts from controls hosted outside the tab pane
        EventHandler<KeyEvent> tabNavigationHandler = event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown() &&
                    (event.getCode() == KeyCode.TAB || (!event.isShiftDown() &&
                            (event.getCode() == KeyCode.PAGE_UP || event.getCode() == KeyCode.PAGE_DOWN)))) {
                tabPane.fireEvent(event.copyFor(tabPane, tabPane));
                event.consume();
            }
        };
        content.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.setVisible(false);
        headerControls.managedProperty().bind(headerControls.visibleProperty());
        headerSeparator.visibleProperty().bind(headerControls.visibleProperty());
        headerSeparator.managedProperty().bind(headerControls.visibleProperty());

        navigationListener = (viewPath, data) -> {
            if (viewPath.size() == 3 && viewPath.indexOf(FundsView.class) == 1)
                loadView(viewPath.tip());
        };

        tabChangeListener = (ov, oldValue, newValue) -> {
            // navigation has already loaded the tab before selecting it
            if (newValue == currentTab) return;

            if (newValue == depositTab)
                navigation.navigateTo(MainView.class, FundsView.class, DepositView.class);
            else if (newValue == withdrawalTab)
                navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class);
            else if (newValue == transactionsTab)
                navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class);
        };
    }

    public void reserveNavigationWidth(ObservableNumberValue width) {
        navigationSpacer.prefWidthProperty().bind(width);
    }

    @Override
    protected void activate() {
        tabPane.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        navigation.addListener(navigationListener);

        if (tabPane.getSelectionModel().getSelectedItem() == depositTab)
            navigation.navigateTo(MainView.class, FundsView.class, DepositView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == withdrawalTab)
            navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == transactionsTab)
            navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class);
    }

    @Override
    protected void deactivate() {
        tabPane.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        navigation.removeListener(navigationListener);
        currentTab = null;
    }

    private void loadView(Class<? extends View> viewClass) {
        // we want to get activate/deactivate called, so we remove the old view on tab change
        if (currentTab != null)
            content.getChildren().clear();

        View view = viewLoader.load(viewClass);

        if (view instanceof DepositView)
            currentTab = depositTab;
        else if (view instanceof WithdrawalView)
            currentTab = withdrawalTab;
        else if (view instanceof TransactionsView)
            currentTab = transactionsTab;

        Region controls = view instanceof TransactionsView transactionsView ? transactionsView.getHeaderControls() : null;
        if (controls == null) headerControls.getChildren().clear();
        else if (!headerControls.getChildren().contains(controls)) headerControls.getChildren().setAll(controls);
        headerControls.setVisible(controls != null);

        if (!content.getChildren().contains(view.getRoot())) content.getChildren().setAll(view.getRoot());
        tabPane.getSelectionModel().select(currentTab);
    }
}

