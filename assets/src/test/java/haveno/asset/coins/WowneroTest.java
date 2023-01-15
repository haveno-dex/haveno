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
import org.junit.Test;

public class WowneroTest extends AbstractAssetTest {

    public WowneroTest() {
        super(new Wownero());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("WW2FZKUtFwaBc7wsdQ1jNHGFmSNw9xq3WKLBgYJob4M5Kbzk3oKYfVT8WEBnre6dbzMYg9q3gacG2fJyCgCyu6TV1THkny26P");
        assertValidAddress("WW2vMLhorF55xGs2RLkGVTGAksWg1mjVH4VKJtDep76FGzj7XVjvAbmYyQHY34ZRwGQqRpq4sZgWCCY6cd9XuDdh2TKaQKQbh");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("");
        assertInvalidAddress("Wo3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP");
        assertInvalidAddress("Wo3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94");
        assertInvalidAddress("Wo3MWeKwtA918DU4c69hVSNgejdWFCRCuWjShRY66mJkU2Hv58eygJWDJS1MNa2Ge5M1WjUkGHuLqHkweDxwZZU42d16v94mP69");
        assertInvalidAddress("694hqQ2xftk9UNDThFPWcQ4VtgC4Ciz7ES3XQ81qdfYjXe17kUxPSGGWwisoxYvZb5Y36DpGVKVLZXHhwjwB7TZr1evyvgMg2");
        assertInvalidAddress("W14hqQ2xftk9UNDThFPWcQ4VtgC4Ciz7ES3XQ81qdfYjXe17kUxPSGGWwisoxYvZb5Y36DpGVKVLZXHhwjwB7TZr1evyvgMg2");
    }
}
