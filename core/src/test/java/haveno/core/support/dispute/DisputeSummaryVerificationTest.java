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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DisputeSummaryVerificationTest {

    private static final String BEGIN = "\n-----BEGIN SIGNATURE-----\n";
    private static final String END = "\n-----END SIGNATURE-----\n";

    @Test
    public void testSignedSummaryIsWellFormed() {
        assertTrue(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END));
    }

    @Test
    public void testSignedTextMayContainEndSeparator() {
        assertTrue(DisputeSummaryVerification.isWellFormed("notes" + END + "more" + BEGIN + "abcd" + END));
    }

    @Test
    public void testContentOutsideSignatureBlockIsRejected() {
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END + "planted"));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END + "planted" + END));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + BEGIN + "efgh" + END));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd"));
    }
}
