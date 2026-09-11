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

package haveno.core.util.validation;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;

import java.math.BigDecimal;

public class AmountValidator8Decimals extends MonetaryValidator {
    private final boolean requireExactAmount;

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);
        if (!result.isValid || !requireExactAmount)
            return result;

        try {
            BigDecimal amount = new BigDecimal(cleanInput(input));
            if (amount.compareTo(BigDecimal.valueOf(getMinValue())) < 0)
                return new ValidationResult(false, Res.get("validation.traditional.tooSmall"));
            if (amount.compareTo(BigDecimal.valueOf(getMaxValue())) > 0)
                return new ValidationResult(false, Res.get("validation.traditional.tooLarge"));
            if (amount.stripTrailingZeros().scale() > CryptoMoney.SMALLEST_UNIT_EXPONENT)
                return new ValidationResult(false, Res.get("validation.crypto.tooManyDecimals"));
            return result;
        } catch (NumberFormatException ex) {
            return new ValidationResult(false, Res.get("validation.NaN"));
        }
    }

    @Override
    public double getMinValue() {
        return 0.00000001;
    }

    @Override
    public double getMaxValue() {
        // hard to say what the max value should be with cryptos
        return 100_000_000;
    }

    @Inject
    public AmountValidator8Decimals() {
        this(true);
    }

    public AmountValidator8Decimals(boolean requireExactAmount) {
        this.requireExactAmount = requireExactAmount;
    }
}
