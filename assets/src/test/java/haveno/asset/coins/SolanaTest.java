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

public class SolanaTest extends AbstractAssetTest {

    public SolanaTest() {
        super(new Solana());
    }

    @Test
    public void testValidAddresses() {
        assertValidAddress("4Nd1mYZbtJbHkj9QwxAXWah8X9M8vZ9H1fsn6uhPW33k");
        assertValidAddress("8HoQnePLqPj4M7PUDzfw8e3Ymdwgc7NqAcoH7okh4wz7");
        assertValidAddress("H3C5pGrMmD8FrGd9VRtNVbY3tWusJX3A1u33f9bdBpsk");
        assertValidAddress("7zVhJcA5s8zfg3UoDUuG4zmnqaVmLqj6L6F6L8WPLnYw");
        assertValidAddress("AVHUu155WoNexeNCGce8mrb8hvg8pBgvCJh4vtd3Q1RV");
        assertValidAddress("8HoQnePLqPj4M7PUDzfw8e3Ymdwgc7NqAcoH7okh4wz");
    }

    @Test
    public void testInvalidAddresses() {
        assertInvalidAddress("4Nd1mYZbtJbHkj9QwxAXWah8X9M8vZ9H1fsn6uhPW33O");
        assertInvalidAddress("H3C5pGrMmD8FrGd9VRtNVbY3tWusJX3A1u33f9bdBpskAAA");
        assertInvalidAddress("1");
        assertInvalidAddress("abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ123456789");
    }
}
