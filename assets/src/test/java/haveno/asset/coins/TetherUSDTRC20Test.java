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
import haveno.asset.tokens.TetherUSDTRC20;

import org.junit.jupiter.api.Test;
 
 public class TetherUSDTRC20Test extends AbstractAssetTest {
 
     public TetherUSDTRC20Test() {
         super(new TetherUSDTRC20());
     }
 
     @Test
     public void testValidAddresses() {
         assertValidAddress("TVnmu3E6DYVL4bpAoZnPNEPVUrgC7eSWaX");
     }
 
     @Test
     public void testInvalidAddresses() {
         assertInvalidAddress("0x2a65Aca4D5fC5B5C859090a6c34d1641353982266");
         assertInvalidAddress("0x2a65Aca4D5fC5B5C859090a6c34d16413539822g");
         assertInvalidAddress("2a65Aca4D5fC5B5C859090a6c34d16413539822g");
     }
 }