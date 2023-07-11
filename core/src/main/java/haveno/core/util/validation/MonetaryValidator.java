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

import javax.inject.Inject;

public abstract class MonetaryValidator extends NumberValidator {

    public abstract double getMinValue();

    @SuppressWarnings("SameReturnValue")
    public abstract double getMaxValue();

    @Inject
    public MonetaryValidator() {
    }

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = validateIfNotEmpty(input);
        if (result.isValid) {
            input = cleanInput(input);
            result = validateIfNumber(input);
        }

        if (result.isValid) {
            result = result.andValidation(input,
                    this::validateIfNotZero,
                    this::validateIfNotNegative,
                    this::validateIfNotExceedsMinValue,
                    this::validateIfNotExceedsMaxValue);
        }

        return result;
    }

    protected ValidationResult validateIfNotExceedsMinValue(String input) {
        double d = Double.parseDouble(input);
        if (d < getMinValue())
            return new ValidationResult(false, Res.get("validation.traditional.tooSmall"));
        else
            return new ValidationResult(true);
    }

    protected ValidationResult validateIfNotExceedsMaxValue(String input) {
        double d = Double.parseDouble(input);
        if (d > getMaxValue())
            return new ValidationResult(false, Res.get("validation.traditional.tooLarge"));
        else
            return new ValidationResult(true);
    }
}
