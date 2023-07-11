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

import haveno.core.util.validation.InputValidator;

import javax.inject.Inject;

public final class TransferwiseValidator extends InputValidator {
    private final EmailValidator emailValidator;

    @Inject
    public TransferwiseValidator(EmailValidator emailValidator) {
        this.emailValidator = emailValidator;
    }

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);

        if (!result.isValid)
            return result;

        return emailValidator.validate(input);
    }
}
