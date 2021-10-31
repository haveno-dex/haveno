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

import haveno.asset.*;

public class Genesis extends Coin {

    public Genesis() {
        super("Genesis", "GENX", new GenesisAddressValidator());
    }

    public static class GenesisAddressValidator extends Base58AddressValidator {

        public GenesisAddressValidator() {
            super(new GenesisParams());
        }

        @Override
        public AddressValidationResult validate(String address) {
            if (address.startsWith("S")) {
                return super.validate(address);
            }else if (address.startsWith("genx")){
                return AddressValidationResult.invalidAddress("Bech32 GENX addresses are not supported on haveno");
            }else if (address.startsWith("C")){
                return AddressValidationResult.invalidAddress("Legacy GENX addresses are not supported on haveno");
            }
            return AddressValidationResult.invalidStructure();
        }
    }

    public static class GenesisParams extends NetworkParametersAdapter {

        public GenesisParams() {
            addressHeader = 28;
            p2shHeader = 63;
        }
    }
}

