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

package haveno.desktop.main.overlays.popups;

import haveno.common.UserThread;
import haveno.desktop.main.overlays.Overlay;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class PopupManager {
    private static final Logger log = LoggerFactory.getLogger(PopupManager.class);
    private static final Queue<Overlay<?>> popups = new LinkedBlockingQueue<>(5);
    private static final List<Overlay<?>> displayedPopups = new ArrayList<>();
    private static final ReadOnlyBooleanWrapper hasPendingPopups = new ReadOnlyBooleanWrapper();

    public static ReadOnlyBooleanProperty hasPendingPopupsProperty() {
        return hasPendingPopups.getReadOnlyProperty();
    }

    public static void queueForDisplay(Overlay<?> popup) {
        if (hasDuplicatePopup(popup)) {
            log.warn("The popup is already in the queue or displayed.\n\t" +
                   "New popup not added=" + popup);
            return;
        }
        boolean result = popups.offer(popup);
        if (!result)
            log.warn("The capacity is full with popups in the queue.\n\t" +
                    "New popup not added=" + popup);
        displayNext();
    }

    // prompts opened from within a popup bypass the queue and block every managed popup beneath them
    public static void displayNested(Overlay<?> popup) {
        if (displayedPopups.isEmpty()) {
            queueForDisplay(popup);
            return;
        }
        if (hasDuplicatePopup(popup)) {
            log.warn("The popup is already in the queue or displayed.\n\t" +
                    "New popup not added=" + popup);
            return;
        }
        List<Overlay<?>> parents = List.copyOf(displayedPopups);
        displayedPopups.add(popup);
        popup.displayAbove(parents);
    }

    public static void onHidden(Overlay<?> popup) {
        if (displayedPopups.remove(popup)) {
            if (displayedPopups.isEmpty())
                UserThread.runAfter(PopupManager::displayNext, 100, TimeUnit.MILLISECONDS);
        } else {
            popups.remove(popup);
        }
    }

    private static void displayNext() {
        // keep notifications deferred through the gap between queued popups
        hasPendingPopups.set(!displayedPopups.isEmpty() || !popups.isEmpty());
        if (displayedPopups.isEmpty() && !popups.isEmpty()) {
            Overlay<?> popup = popups.poll();
            displayedPopups.add(popup);
            popup.display();
        }
    }

    private static boolean hasDuplicatePopup(Overlay<?> popup) {
        for (Overlay<?> p : displayedPopups) {
            if (p.toString().equals(popup.toString())) {
                return true;
            }
        }
        for (Overlay<?> p : popups) {
            if (p.toString().equals(popup.toString())) {
                return true;
            }
        }
        return false;
    }
}
