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

package haveno.common;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BackgroundTimer implements Timer {

    private final ScheduledExecutorService executor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public BackgroundTimer() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BackgroundTimer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Timer runLater(Duration delay, Runnable action) {
        if (stopped.get()) return this;
        executor.schedule(wrap(action), delay.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public Timer runPeriodically(Duration interval, Runnable runnable) {
        if (stopped.get()) return this;

        executor.scheduleAtFixedRate(
                wrap(runnable),
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        return this;
    }

    @Override
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private Runnable wrap(Runnable r) {
        return () -> {
            if (stopped.get()) return;
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        };
    }
}