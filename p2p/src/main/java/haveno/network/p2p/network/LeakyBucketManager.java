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

import java.util.concurrent.ConcurrentHashMap;

public final class LeakyBucketManager {
    private final ConcurrentHashMap<String, LeakyBucket> buckets = new ConcurrentHashMap<>();
    public final double leakRatePerSec;
    public final double burstCapacity;
    public final long maxStrikes;

    public LeakyBucketManager(double leakRatePerSec, double burstCapacity, long maxStrikes) {
        this.leakRatePerSec = leakRatePerSec;
        this.burstCapacity = burstCapacity;
        this.maxStrikes = maxStrikes;
    }

    public boolean isSpamming(String id, double amount) {
        LeakyBucket bucket = buckets.computeIfAbsent(id, k -> new LeakyBucket(leakRatePerSec, burstCapacity, maxStrikes));
        return bucket.isSpamming(amount);
    }

    public long getStrikes(String id) {
        LeakyBucket bucket = buckets.get(id);
        return bucket != null ? bucket.getStrikes() : 0;
    }

    public double getLevel(String id) {
        LeakyBucket bucket = buckets.get(id);
        return bucket != null ? bucket.getLevel() : 0.0;
    }

    public LeakyBucket get(String id) {
        return buckets.get(id);
    }

    public LeakyBucket getOrCreate(String id) {
        return buckets.computeIfAbsent(id, k -> new LeakyBucket(leakRatePerSec, burstCapacity, maxStrikes));
    }

    public void remove(String id) {
        buckets.remove(id);
    }
    
    public void clear() {
        buckets.clear();
    }

    public String toString() {
        return String.format("LeakyBucketManager(rate=%g/sec, burst=%.1f, maxStrikes=%d, buckets=%d)", 
                leakRatePerSec, burstCapacity, maxStrikes, buckets.size());
    }
}
