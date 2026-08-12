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

public final class WeroValidator extends InputValidator {

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);
        if (!result.isValid) return result;
        // Wero operates in Germany, France and Belgium; require a mobile number in international format
        String pureNumber = input.replaceAll("[\\s\\-()]", "");
        if (pureNumber.matches("(\\+|00)?(49|33|32)[0-9]{8,12}")) return new ValidationResult(true);
        return new ValidationResult(false, Res.get("validation.phone.invalidDialingCode", input, "DE/FR/BE", "+49/+33/+32"));
    }
}
