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

package haveno.network.p2p.network;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A leaky bucket implementation for rate limiting.
 */
public class LeakyBucket {
    private final double capacity;
    private final double leakRatePerSec;
    private final int maxStrikes;
    private final AtomicInteger strikes = new AtomicInteger(0);
    private final AtomicReference<State> state;

    private static class State {
        final double level;
        final long lastUpdate;
        State(double level, long lastUpdate) {
            this.level = level;
            this.lastUpdate = lastUpdate;
        }
    }

    public LeakyBucket(int capacity, int leakRatePerSec, int maxStrikes) {
        this.capacity = capacity;
        this.leakRatePerSec = leakRatePerSec;
        this.maxStrikes = maxStrikes;
        this.state = new AtomicReference<>(new State(0.0, System.currentTimeMillis()));
    }

    public boolean isSpamming(int amount) {
        while (true) {
            State oldState = state.get();
            long now = System.currentTimeMillis();
            
            // calculate new level after leak
            double elapsed = Math.max(0, (now - oldState.lastUpdate) / 1000.0);
            double leakedLevel = Math.max(0, oldState.level - (elapsed * leakRatePerSec));
            double nextLevel = leakedLevel + amount;

            // check for overflow
            if (nextLevel > capacity) {
                if (state.compareAndSet(oldState, new State(capacity, now))) {
                    return strikes.incrementAndGet() >= maxStrikes;
                }
                continue; // collision, retry
            }

            // commit the new level
            if (state.compareAndSet(oldState, new State(nextLevel, now))) {
                return false;
            }
        }
    }

    public int getStrikes() {
        return strikes.get();
    }

    public double getLevel() {
        return state.get().level;
    }
}