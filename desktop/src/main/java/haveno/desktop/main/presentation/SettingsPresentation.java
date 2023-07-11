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

package haveno.desktop.main.presentation;

import haveno.core.user.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.MapChangeListener;

import javax.inject.Inject;
import javax.inject.Singleton;


@Singleton
public class SettingsPresentation {

    public static final String SETTINGS_NEWS = "settingsNews";

    private Preferences preferences;

    private final SimpleBooleanProperty showNotification = new SimpleBooleanProperty(false);

    @Inject
    public SettingsPresentation(Preferences preferences) {

        this.preferences = preferences;

        preferences.getDontShowAgainMapAsObservable().addListener((MapChangeListener<? super String, ? super Boolean>) change -> {
            if (change.getKey().equals(SETTINGS_NEWS)) {
                showNotification.set(!change.wasAdded());
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BooleanProperty getShowSettingsUpdatesNotification() {
        return showNotification;
    }

    public void setup() {
        showNotification.set(preferences.showAgain(SETTINGS_NEWS));
    }
}
