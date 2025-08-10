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

public class CardanoTest extends AbstractAssetTest {

    public CardanoTest() {
        super(new Cardano());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("addr1vpu5vlrf4xkxv2qpwngf6cjhtw542ayty80v8dyr49rf5eg0yu80w");
        assertValidAddress("addr1q8gg2r3vf9zggn48g7m8vx62rwf6warcs4k7ej8mdzmqmesj30jz7psduyk6n4n2qrud2xlv9fgj53n6ds3t8cs4fvzs05yzmz");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("addr1Q9r4y0gx0m4hd5s2u3pnj7ufc4s0ghqzj7u6czxyfks5cty5k5yq5qp6gmw5v7uqvx2g4kw6zjhx4l6fnhcey9lg9nys6v2mpu");
        assertInvalidAddress("addr2q9r4y0gx0m4hd5s2u3pnj7ufc4s0ghqzj7u6czxyfks5cty5k5yq5qp6gmw5v7uqvx2g4kw6zjhx4l6fnhcey9lg9nys6v2mpu");
        assertInvalidAddress("addr2vpu5vlrf4xkxv2qpwngf6cjhtw542ayty80v8dyr49rf5eg0yu80w");
        assertInvalidAddress("Ae2tdPwUPEYxkYw5GrFyqb4Z9TzXo8f1WnWpPZP1sXrEn1pz2VU3CkJ8aTQ");
    }
}
