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

public class TronTest extends AbstractAssetTest {

    public TronTest() {
        super(new Tron());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("TRjE1H8dxypKM1NZRdysbs9wo7huR4bdNz");
        assertValidAddress("THdUXD3mZqT5aMnPQMtBSJX9ANGjaeUwQK");
        assertValidAddress("THUE6WTLaEGytFyuGJQUcKc3r245UKypoi");
        assertValidAddress("TH7vVF9RTMXM9x7ZnPnbNcEph734hpu8cf");
        assertValidAddress("TJNtFduS4oebw3jgGKCYmgSpTdyPieb6Ha");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("TJRyWwFs9wTFGZg3L8nL62xwP9iK8QdK9R");
        assertInvalidAddress("TJRyWwFs9wTFGZg3L8nL62xwP9iK8QdK9X");
        assertInvalidAddress("1JRyWwFs9wTFGZg3L8nL62xwP9iK8QdK9R");
        assertInvalidAddress("TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVo");
    }
}
