/*
* This file is part of Bisq.
*
* Bisq is free software: you can redistribute it and/or modify it
* under the terms of the GNU Affero General Public License as published by
* the Free Software Foundation, either version 3 of the License, or (at
* your option) any later version.
*
* Bisq is distributed in the hope that it will be useful, but WITHOUT
* ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
* FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
* License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with Bisq. If not, see <http://www.gnu.org/licenses/>.
*/

package haveno.asset;

/**
* Validates a Solana address using the regular expression for Solana's Base58 encoding format.
* Solana addresses are typically represented as a 44-character Base58 string.
* The address must be a valid Base58 string of length 44.
*
* @author Chris Beams (modified)
* @since 1.0.0
*/
public class SolanaAddressValidator extends RegexAddressValidator {

    // Regular expression for a valid Solana address (Base58 encoded, 44 characters long)
    public SolanaAddressValidator() {
        super("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    }

    public SolanaAddressValidator(String errorMessageI18nKey) {
        super("^[1-9A-HJ-NP-Za-km-z]{32,44}$", errorMessageI18nKey);
    }
}
