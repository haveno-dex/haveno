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

public class SixEleven extends Coin {

    public SixEleven() {
        super("SixEleven", "SIL", new SixElevenAddressValidator());
    }

    public static class SixElevenAddressValidator extends Base58AddressValidator {

        public SixElevenAddressValidator() {
            super(new SixEleven.SixElevenChainParams());
        }

        @Override
        public AddressValidationResult validate(String address) {
            if (!address.matches("^[MN][123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{33}$"))
                return AddressValidationResult.invalidStructure();

            return super.validate(address);
        }
    }

    public static class SixElevenChainParams extends NetworkParametersAdapter {
        public SixElevenChainParams() {
            addressHeader = 52;
        }
    }
}
