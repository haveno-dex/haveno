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

package haveno.core.payment.validation;

import haveno.core.locale.Res;
import haveno.core.util.validation.InputValidator;

public final class KaspiValidator extends InputValidator {

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);
        if (!result.isValid) return result;
        // accept a Kazakh mobile number (+7/8 prefix) or a 16-digit card number
        String stripped = input.replaceAll("[\\s-]", "");
        if (stripped.matches("(\\+?7|8)7[0-9]{9}") || stripped.matches("[0-9]{16}"))
            return new ValidationResult(true);
        return new ValidationResult(false, Res.get("validation.accountNrFormat", "+7 702 123 4567 or 4400 4300 1234 5678"));
    }
}
