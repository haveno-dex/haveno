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

package haveno.desktop.common;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.reactfx.FxTimer;

import javafx.application.Platform;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UITimer implements Timer {
    private final Logger log = LoggerFactory.getLogger(UITimer.class);
    private haveno.common.reactfx.Timer timer;

    public UITimer() {
    }

    @Override
    public Timer runLater(Duration delay, Runnable runnable) {
        executeDirectlyIfPossible(() -> {
            if (timer == null) {
                timer = FxTimer.create(delay, runnable);
                timer.restart();
            } else {
                log.warn("runLater called on an already running timer.");
            }
        });
        return this;
    }

    @Override
    public Timer runPeriodically(Duration interval, Runnable runnable) {
        executeDirectlyIfPossible(() -> {
            if (timer == null) {
                timer = FxTimer.createPeriodic(interval, runnable);
                timer.restart();
            } else {
                log.warn("runPeriodically called on an already running timer.");
            }
        });
        return this;
    }

    @Override
    public void stop() {
        executeDirectlyIfPossible(() -> {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        });
    }

    private void executeDirectlyIfPossible(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            UserThread.execute(runnable);
        }
    }
}
