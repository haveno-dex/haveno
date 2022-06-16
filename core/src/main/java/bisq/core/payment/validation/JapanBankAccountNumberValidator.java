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

package bisq.core.payment.validation;

import bisq.core.util.validation.InputValidator;
import bisq.core.payment.JapanBankData;

public final class JapanBankAccountNumberValidator extends InputValidator
{
    @Override
    public ValidationResult validate(String input)
    {
        boolean lengthOK = (
            isNumberWithFixedLength(input, 3) ||
            isNumberWithFixedLength(input, 4) ||
            isNumberWithFixedLength(input, 5) ||
            isNumberWithFixedLength(input, 6) ||
            isNumberWithFixedLength(input, 7) ||
            isNumberWithFixedLength(input, 8));

        if (lengthOK)
            return super.validate(input);

        return new ValidationResult(false, JapanBankData.getString("account.number.validation.error"));
    }
}
