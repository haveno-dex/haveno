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

public final class PayPayValidator extends InputValidator {

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);
        if (!result.isValid) return result;
        // accept a Japanese mobile number (optional +81 prefix) or a PayPay ID
        String stripped = input.replaceAll("[\\s-]", "");
        if (stripped.matches("0[789]0[0-9]{8}") || stripped.matches("\\+?81[789]0[0-9]{8}") || stripped.matches("[a-zA-Z0-9_]{3,15}"))
            return new ValidationResult(true);
        return new ValidationResult(false, Res.get("validation.accountNrFormat", "090-1234-5678 or paypay_id"));
    }
}
