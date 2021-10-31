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

import haveno.asset.AddressValidationResult;
import haveno.asset.Base58AddressValidator;
import haveno.asset.Coin;
import haveno.asset.NetworkParametersAdapter;

public class PZDC extends Coin {

    public PZDC() {
        super("PZDC", "PZDC", new PZDCAddressValidator());
    }


    public static class PZDCAddressValidator extends Base58AddressValidator {

        public PZDCAddressValidator() {
            super(new PZDCParams());
        }

        @Override
        public AddressValidationResult validate(String address) {
            if (!address.matches("^[P][a-km-zA-HJ-NP-Z1-9]{24,33}$"))
                return AddressValidationResult.invalidStructure();

            return super.validate(address);
        }
    }


    public static class PZDCParams extends NetworkParametersAdapter {

        public PZDCParams() {
            addressHeader = 55;
            p2shHeader = 13;
        }
    }
}
