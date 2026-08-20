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

package haveno.common.util;

public class DateUtil {

    // Inclusive bounds check; an inverted range accepts nothing so validation of peer data fails closed.
    public static boolean isWithinBounds(long timestamp, long lowerBound, long upperBound) {
        if (lowerBound > upperBound) return false;
        return timestamp >= lowerBound && timestamp <= upperBound;
    }

    // Inclusive tolerance around a reference; bounds saturate so extreme peer values cannot overflow.
    public static boolean isWithinTolerance(long timestamp, long referenceTimestamp, long tolerance) {
        if (tolerance < 0) throw new IllegalArgumentException("tolerance must not be negative");
        long lowerBound = referenceTimestamp < Long.MIN_VALUE + tolerance
                ? Long.MIN_VALUE
                : referenceTimestamp - tolerance;
        long upperBound = referenceTimestamp > Long.MAX_VALUE - tolerance
                ? Long.MAX_VALUE
                : referenceTimestamp + tolerance;
        return isWithinBounds(timestamp, lowerBound, upperBound);
    }
}
