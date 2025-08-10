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

package haveno.asset.coins;

import haveno.asset.AbstractAssetTest;
import org.junit.jupiter.api.Test;

public class RippleTest extends AbstractAssetTest {

    public RippleTest() {
        super(new Ripple());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("r9CxAMAoZAgyVGP8CY9F1arzf9bJg3Y7U8");
        assertValidAddress("rsXMbDtCAmzSWajWiii7ffWygAjYVNDxY7");
        assertValidAddress("rE3nYkQy121JEVb37JKX8LSH6wUBnNvNo2");
        assertValidAddress("rMzucuWFUEE6aM9DC992BqqMgZNPrv4kvi");
        assertValidAddress("rJUmAFPWE36cpdbN4DUEAFBLtG2xkEavY8");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("RJUmAFPWE36cpdbN4DUEAFBLtG2xkEavY8");
        assertInvalidAddress("zJUmAFPWE36cpdbN4DUEAFBLtG2xkEavY8");
        assertInvalidAddress("1LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW");
    }
}
