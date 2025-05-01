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
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

@FxmlView
public class FundsView extends ActivatableView<TabPane, Void> {

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
        depositTab.setText(Res.get("funds.tab.deposit"));
        withdrawalTab.setText(Res.get("funds.tab.withdrawal"));
        transactionsTab.setText(Res.get("funds.tab.transactions"));

        navigationListener = (viewPath, data) -> {
            if (viewPath.size() == 3 && viewPath.indexOf(FundsView.class) == 1)
                loadView(viewPath.tip());
        };

        tabChangeListener = (ov, oldValue, newValue) -> {
            if (newValue == depositTab)
                navigation.navigateTo(MainView.class, FundsView.class, DepositView.class);
            else if (newValue == withdrawalTab)
                navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class);
            else if (newValue == transactionsTab)
                navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class);
        };
    }

    @Override
    protected void activate() {
        root.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        navigation.addListener(navigationListener);

        if (root.getSelectionModel().getSelectedItem() == depositTab)
            navigation.navigateTo(MainView.class, FundsView.class, DepositView.class);
        else if (root.getSelectionModel().getSelectedItem() == withdrawalTab)
            navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class);
        else if (root.getSelectionModel().getSelectedItem() == transactionsTab)
            navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class);
    }

    @Override
    protected void deactivate() {
        root.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        navigation.removeListener(navigationListener);
        currentTab = null;
    }

    private void loadView(Class<? extends View> viewClass) {
        // we want to get activate/deactivate called, so we remove the old view on tab change
        if (currentTab != null)
            currentTab.setContent(null);

        View view = viewLoader.load(viewClass);

        if (view instanceof DepositView)
            currentTab = depositTab;
        else if (view instanceof WithdrawalView)
            currentTab = withdrawalTab;
        else if (view instanceof TransactionsView)
            currentTab = transactionsTab;

        currentTab.setContent(view.getRoot());
        root.getSelectionModel().select(currentTab);
    }
}

