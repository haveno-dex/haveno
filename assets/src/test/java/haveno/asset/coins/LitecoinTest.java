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

public class LitecoinTest extends AbstractAssetTest {

    public LitecoinTest() {
        super(new Litecoin());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("Lg3PX8wRWmApFCoCMAsPF5P9dPHYQHEWKW");
        assertValidAddress("LTuoeY6RBHV3n3cfhXVVTbJbxzxnXs9ofm");
        assertValidAddress("LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("1LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW");
        assertInvalidAddress("LgfapHEPhZbdRF9pMd5WPT35hFXcZS1USrW");
        assertInvalidAddress("LgfapHEPhZbRF9pMd5WPT35hFXcZS1USrW#");
    }
}
