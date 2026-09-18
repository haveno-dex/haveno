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

package haveno.desktop.main.overlays.notifications;

import java.util.ArrayList;
import java.util.List;

public class NotificationManager {
    private static final List<Notification> notifications = new ArrayList<>();
    private static Notification displayedNotification;

    public static void show(Notification popup) {
        if (popup.isClosing() || notifications.contains(popup)) return;
        notifications.add(0, popup);
        displayNext();
    }

    static void onUpdated(Notification popup) {
        if (popup.isClosing() || !notifications.remove(popup)) return;
        notifications.add(0, popup);
        displayNext();
    }

    static boolean isCurrent(Notification popup) {
        return popup == displayedNotification;
    }

    public static void onHidden(Notification popup) {
        if (popup.isClosing()) notifications.remove(popup);
        if (isCurrent(popup)) displayedNotification = null;
        displayNext();
    }

    private static void displayNext() {
        Notification newest = notifications.isEmpty() ? null : notifications.get(0);
        if (displayedNotification != null) {
            // finish hiding the previous card before showing another notification
            if (displayedNotification != newest) displayedNotification.suspend();
        } else if (newest != null) {
            displayedNotification = newest;
            newest.onReadyForDisplay();
        }
    }
}
