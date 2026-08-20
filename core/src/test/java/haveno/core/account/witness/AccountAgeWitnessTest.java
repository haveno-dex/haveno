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

package haveno.core.account.witness;

import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccountAgeWitnessTest {

    @Test
    public void isDateInToleranceAcceptsDatesUpToOneDayOff() {
        long now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        long oneDay = TimeUnit.DAYS.toMillis(1);

        assertTrue(witnessWithDate(now).isDateInTolerance(clock));
        assertTrue(witnessWithDate(now - oneDay).isDateInTolerance(clock));
        assertTrue(witnessWithDate(now + oneDay).isDateInTolerance(clock));
    }

    @Test
    public void isDateInToleranceRejectsDatesMoreThanOneDayOff() {
        long now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        long oneDay = TimeUnit.DAYS.toMillis(1);

        assertFalse(witnessWithDate(now - oneDay - 1).isDateInTolerance(clock));
        assertFalse(witnessWithDate(now + oneDay + 1).isDateInTolerance(clock));
    }

    @Test
    public void isDateInToleranceRejectsExtremeDates() {
        long now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);

        assertFalse(witnessWithDate(Long.MIN_VALUE).isDateInTolerance(clock));
        assertFalse(witnessWithDate(Long.MAX_VALUE).isDateInTolerance(clock));
        // This date makes (now - date) overflow to Long.MIN_VALUE. A Math.abs based check
        // stays negative in that case and would accept the payload.
        assertFalse(witnessWithDate(now + Long.MIN_VALUE).isDateInTolerance(clock));
    }

    private AccountAgeWitness witnessWithDate(long date) {
        return new AccountAgeWitness(Utils.sha256hash160(new byte[]{1}), date);
    }
}
