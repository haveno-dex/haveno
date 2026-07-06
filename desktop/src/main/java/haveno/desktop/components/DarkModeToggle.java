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

package haveno.desktop.components;

import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;

/**
 * Light/dark mode toggle icon. Clicking it flips {@code cssTheme} in {@link Preferences},
 * which live-updates every scene listening on {@code cssThemeProperty}. Used on the main
 * footer as well as the startup (password and connecting) screens so the user can switch
 * theme at any point.
 */
public class DarkModeToggle extends ImageView {

    private final ChangeListener<Number> themeChangeListener;

    public DarkModeToggle(Preferences preferences) {
        setPreserveRatio(true);
        setPickOnBounds(true);
        setCursor(Cursor.HAND);
        updateIcon(preferences);

        Tooltip tooltip = new Tooltip();
        Tooltip.install(this, tooltip);
        setOnMouseEntered(e -> tooltip.setText(Res.get(preferences.getCssTheme() == 1 ? "setting.preferences.useLightMode" : "setting.preferences.useDarkMode")));

        setOnMouseClicked(e -> preferences.setCssTheme(preferences.getCssTheme() != 1));

        themeChangeListener = (observable, oldValue, newValue) -> updateIcon(preferences);
        preferences.getCssThemeProperty().addListener(new WeakChangeListener<>(themeChangeListener));
    }

    private void updateIcon(Preferences preferences) {
        setId(preferences.getCssTheme() == 1 ? "image-dark-mode-toggle" : "image-light-mode-toggle");
    }
}
