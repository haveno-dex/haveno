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

import haveno.common.util.Tuple2;

public final class EventThrottler {
    private final long intervalMs;
    private final AtomicLong lastSuccessTs = new AtomicLong(0);
    private final AtomicLong throttledCount = new AtomicLong(0);

    public EventThrottler(long interval, TimeUnit unit) {
        this.intervalMs = unit.toMillis(interval);
    }

    /**
     * Registers an event and returns status.
     * 
     * @return a pair where the first value is true iff the event is throttled, and the second value is the number of throttled events since last success
     */
    public Tuple2<Boolean, Long> onEvent() {
        final long now = System.currentTimeMillis();
        final long last = lastSuccessTs.get();

        if (now - last > intervalMs) {
            if (lastSuccessTs.compareAndSet(last, now)) {
                return new Tuple2<>(false, throttledCount.getAndSet(0));
            }
        }

        return new Tuple2<>(true, throttledCount.incrementAndGet());
    }
}