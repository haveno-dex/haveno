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

package haveno.core.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HavenoUtilsTest {

    @Test
    public void detectsDaemonConnectionIssuesFromKnownMessages() {
        assertTrue(HavenoUtils.isDaemonConnectionIssue(new RuntimeException("no connection to daemon")));
        assertTrue(HavenoUtils.isDaemonConnectionIssue(new RuntimeException("failed to get output distribution")));
        assertTrue(HavenoUtils.isDaemonConnectionIssue(new RuntimeException("Failed to get height")));
    }

    @Test
    public void ignoresUnrelatedMessages() {
        assertFalse(HavenoUtils.isDaemonConnectionIssue(new RuntimeException("Not enough money")));
        assertFalse(HavenoUtils.isDaemonConnectionIssue(new RuntimeException("Read timed out")));
        assertFalse(HavenoUtils.isDaemonConnectionIssue(new RuntimeException((String) null)));
        assertFalse(HavenoUtils.isDaemonConnectionIssue(null));
    }
}
