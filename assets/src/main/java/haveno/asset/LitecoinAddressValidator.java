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
 * Validates a Litecoin address.
 */
public class LitecoinAddressValidator extends RegexAddressValidator {

    public LitecoinAddressValidator() {
        super("^(?:(?:litecoin|Litecoin|LITECOIN):)?([LM3]{1}[a-km-zA-HJ-NP-Z1-9]{26,33}|ltc1[a-z0-9]{39,59})(?:\\?time=\\d+(?:&exp=\\d+)?)?$");
    }

    public LitecoinAddressValidator(String errorMessageI18nKey) {
        super("^(?:(?:litecoin|Litecoin|LITECOIN):)?([LM3]{1}[a-km-zA-HJ-NP-Z1-9]{26,33}|ltc1[a-z0-9]{39,59})(?:\\?time=\\d+(?:&exp=\\d+)?)?$", errorMessageI18nKey);
    }
}
