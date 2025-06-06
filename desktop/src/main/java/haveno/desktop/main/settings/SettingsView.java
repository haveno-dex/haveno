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

package haveno.desktop.main.settings;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.main.MainView;
import haveno.desktop.main.presentation.SettingsPresentation;
import haveno.desktop.main.settings.about.AboutView;
import haveno.desktop.main.settings.network.NetworkSettingsView;
import haveno.desktop.main.settings.preferences.PreferencesView;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

@FxmlView
public class SettingsView extends ActivatableView<TabPane, Void> {
    @FXML
    Tab preferencesTab, networkTab, aboutTab;
    private final ViewLoader viewLoader;
    private final Navigation navigation;
    private Preferences preferences;
    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;

    @Inject
    public SettingsView(CachingViewLoader viewLoader, Navigation navigation, Preferences preferences) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        this.preferences = preferences;
    }

    @Override
    public void initialize() {
        preferencesTab.setText(Res.get("settings.tab.preferences"));
        networkTab.setText(Res.get("settings.tab.network"));
        aboutTab.setText(Res.get("settings.tab.about"));

        navigationListener = (viewPath, data) -> {
            if (viewPath.size() == 3 && viewPath.indexOf(SettingsView.class) == 1)
                loadView(viewPath.tip());
        };

        tabChangeListener = (ov, oldValue, newValue) -> {
            navigationToTabContent(newValue);
        };
    }

    private void navigationToTabContent(Tab newValue) {
        if (newValue == preferencesTab)
            navigation.navigateTo(MainView.class, SettingsView.class, PreferencesView.class);
        else if (newValue == networkTab)
            navigation.navigateTo(MainView.class, SettingsView.class, NetworkSettingsView.class);
        else if (newValue == aboutTab)
            navigation.navigateTo(MainView.class, SettingsView.class, AboutView.class);
    }

    @Override
    protected void activate() {
        // Hide new badge if user saw this section
        preferences.dontShowAgain(SettingsPresentation.SETTINGS_NEWS, true);

        root.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        navigation.addListener(navigationListener);

        Tab selectedItem = root.getSelectionModel().getSelectedItem();
        navigationToTabContent(selectedItem);
    }

    @Override
    protected void deactivate() {
        root.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        navigation.removeListener(navigationListener);
    }

    private void loadView(Class<? extends View> viewClass) {
        final Tab tab;
        View view = viewLoader.load(viewClass);

        if (view instanceof PreferencesView) tab = preferencesTab;
        else if (view instanceof NetworkSettingsView) tab = networkTab;
        else if (view instanceof AboutView) tab = aboutTab;
        else throw new IllegalArgumentException("Navigation to " + viewClass + " is not supported");

        if (tab.getContent() != null && tab.getContent() instanceof ScrollPane) {
            ((ScrollPane) tab.getContent()).setContent(view.getRoot());
        } else {
            tab.setContent(view.getRoot());
        }
        root.getSelectionModel().select(tab);
    }
}

