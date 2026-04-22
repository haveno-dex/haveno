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

package haveno.network.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class EventThrottler {
    private final long intervalMs;
    private final AtomicLong lastSuccessTs = new AtomicLong(0);
    private final AtomicLong throttledCount = new AtomicLong(0);

    public EventThrottler(long interval, TimeUnit unit) {
        this.intervalMs = unit.toMillis(interval);
    }

    public static class ThrottleResult {

        /**
         * Indicates if the event was throttled (true) or allowed (false).
         */
        public final boolean throttled;

        /**
         * The number of events that have been throttled since the last successful (non-throttled) event. This count resets to zero after a successful event.
         */
        public final long throttledCount;

        ThrottleResult(boolean throttled, long throttledCount) {
            this.throttled = throttled;
            this.throttledCount = throttledCount;
        }
    }

    /**
     * Registers an event and returns status.
     * 
     * @return the result of the throttling check
     */
    public ThrottleResult onEvent() {
        final long now = System.currentTimeMillis();
        final long last = lastSuccessTs.get();

        if (now - last > intervalMs) {
            if (lastSuccessTs.compareAndSet(last, now)) {
                return new ThrottleResult(false, throttledCount.getAndSet(0));
            }
        }

        return new ThrottleResult(true, throttledCount.incrementAndGet());
    }
}