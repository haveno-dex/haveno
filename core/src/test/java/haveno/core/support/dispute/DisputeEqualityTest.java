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

package haveno.core.support.dispute;

import haveno.core.support.SupportType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class DisputeEqualityTest {

    private static Dispute dispute(String tradeId, int traderId) {
        return new Dispute(0L, tradeId, traderId, true, true, true, null, 0L, 0L,
                null, null, null, null, "", null, null, null, null, null, false, SupportType.ARBITRATION);
    }

    @Test
    public void equalByIdentityAcrossInstances() {
        // Regression: disputeResultProperty (a JavaFX property with identity equality) was in
        // @EqualsAndHashCode, so two instances of the same dispute never matched, defeating the
        // disputeList.contains() dedup. Equality must be the stable id (tradeId_traderId).
        Dispute a = dispute("trade-1", 0);
        Dispute b = dispute("trade-1", 0);
        assertEquals(a, b, "same tradeId+traderId must be equal across distinct instances");
        assertEquals(a.hashCode(), b.hashCode(), "equal disputes must share a hashCode");
    }

    @Test
    public void differByTradeIdOrTrader() {
        Dispute a = dispute("trade-1", 0);
        assertNotEquals(a, dispute("trade-1", 1), "different traderId must not be equal");
        assertNotEquals(a, dispute("trade-2", 0), "different tradeId must not be equal");
    }
}
