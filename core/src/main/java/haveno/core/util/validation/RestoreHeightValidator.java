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

package haveno.core.util.validation;

import haveno.core.locale.Res;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Validates an optional wallet restore height, given as a block height or past date (YYYY-MM-DD). */
public class RestoreHeightValidator extends InputValidator {

    @Override
    public ValidationResult validate(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return new ValidationResult(true);
        try {
            if (text.matches("\\d+")) {
                Long.parseLong(text);
                return new ValidationResult(true);
            }
            if (text.matches("\\d{4}-(\\d|\\d{2}(-\\d?)?)?")) return new ValidationResult(true); // partial date still being typed
            LocalDate date = LocalDate.parse(text);
            if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("Restore date cannot be in the future");
            return new ValidationResult(true);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return new ValidationResult(false, Res.get("seed.restore.height.invalid"));
        }
    }
}
