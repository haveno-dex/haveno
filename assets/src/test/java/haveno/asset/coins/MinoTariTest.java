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

public class MinoTariTest extends AbstractAssetTest {

    public MinoTariTest() {
        super(new MinoTari());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("12BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
        assertValidAddress("12BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("");
        assertInvalidAddress("12BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg");
        assertInvalidAddress("12BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6xx");
        assertInvalidAddress("22BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
        assertInvalidAddress("32BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
        assertInvalidAddress("H2BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
        assertInvalidAddress("d2BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
        assertInvalidAddress("f2BcE5L3ijYiPPJfD1HdZboL4CD8XJ4AZ4nfhgB6sq3X8uYKCXfuB1bwodTNyXYzQkKztkpj8mxpjEWkDf6zRATAg6S");
    }
}
