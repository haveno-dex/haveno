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

public class BitcoinCashTest extends AbstractAssetTest {

    public BitcoinCashTest() {
        super(new BitcoinCash());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYhem");
        assertValidAddress("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX");
        assertValidAddress("1111111111111111111114oLvT2");
        assertValidAddress("1BitcoinEaterAddressDontSendf59kuE");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYhemqq");
        assertInvalidAddress("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYheO");
        assertInvalidAddress("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYhek#");
    }
}
