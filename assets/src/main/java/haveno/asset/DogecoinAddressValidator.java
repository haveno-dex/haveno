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
* Validates a Dogecoin address using the regular expression for Dogecoin's Base58 encoding format.
* Dogecoin addresses are typically represented as Base58 strings starting with either "D" (for P2PKH) 
* or "9" (for P2SH). The address should be 34 characters long.
*
* @author Your Name
* @since 1.0.0
*/
public class DogecoinAddressValidator extends RegexAddressValidator {

    // Regular expression for a valid Dogecoin address (Base58 encoded, 34 characters long)
    // Dogecoin addresses start with 'D' (P2PKH) or '9' (P2SH), followed by 33 characters
    public DogecoinAddressValidator() {
        super("^[D9][A-HJ-NP-Za-km-z1-9]{33}$");
    }

    public DogecoinAddressValidator(String errorMessageI18nKey) {
        super("^[D9][A-HJ-NP-Za-km-z1-9]{33}$", errorMessageI18nKey);
    }
}
