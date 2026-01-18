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

package haveno.asset;

/**
 * Validates a MinoTari (XTM) address.
 * https://rfc.tari.com/RFC-0155_TariAddress
 * https://github.com/tari-project/tari/raw/refs/heads/development/base_layer/common_types/src/tari_address/dual_address.rs
 */
public class MinoTariAddressValidator implements AddressValidator {

    @Override
    public AddressValidationResult validate(String address) {
        if (!isValidMinoTariAddress(address)) {
            return AddressValidationResult.invalidStructure();
        }
        return AddressValidationResult.validAddress();
    }

    private static boolean isValidMinoTariAddress(String address) {
        if (address == null || (address.length() != 90 && address.length() != 91))
            return false;

        return address.startsWith("12"); // Mainnet & one_sided only
    }

}
