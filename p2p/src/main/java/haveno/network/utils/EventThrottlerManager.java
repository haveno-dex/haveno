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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import haveno.common.util.Tuple2;

public final class EventThrottlerManager {
    private final ConcurrentHashMap<String, EventThrottler> throttlers = new ConcurrentHashMap<>();
    private final long interval;
    private final TimeUnit unit;

    public EventThrottlerManager(long interval, TimeUnit unit) {
        this.interval = interval;
        this.unit = unit;
    }

    /**
     * Throttles an event based on a specific ID.
     * 
     * @param id the unique identifier for the event source
     * @return Tuple2 where first is throttled status, second is count of throttled events
     */
    public Tuple2<Boolean, Long> onEvent(String id) {
        EventThrottler throttler = throttlers.computeIfAbsent(id, 
            k -> new EventThrottler(interval, unit));
        
        return throttler.onEvent();
    }

    public void remove(String id) {
        throttlers.remove(id);
    }
    
    public void clear() {
        throttlers.clear();
    }
}
