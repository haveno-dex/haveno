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

public final class FpsValidator extends InputValidator {

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);
        if (!result.isValid) return result;
        // accept an FPS proxy: 8-digit mobile (optional +852 prefix), 7-9 digit FPS ID or an email address
        String stripped = input.replaceAll("[\\s-]", "");
        if (stripped.matches("\\+?852[0-9]{8}") || stripped.matches("[0-9]{7,9}") || stripped.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            return new ValidationResult(true);
        return new ValidationResult(false, Res.get("validation.accountNrFormat", "91234567, +852 9123 4567, 1234567 or name@example.com"));
    }
}
