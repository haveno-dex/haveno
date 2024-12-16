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

package haveno.core.payment.validation;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.validation.NumberValidator;
import java.math.BigDecimal;
import java.math.BigInteger;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;

public class XmrValidator extends NumberValidator {

    @Nullable
    @Setter
    protected BigInteger minValue;

    @Nullable
    @Setter
    protected BigInteger maxValue;

    @Nullable
    @Setter
    @Getter
    protected BigInteger maxTradeLimit;

    @Inject
    public XmrValidator() {
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
                    this::validateIfNotFractionalXmrValue,
                    this::validateIfNotExceedsMaxTradeLimit,
                    this::validateIfNotExceedsMaxValue,
                    this::validateIfNotUnderMinValue);
        }

        return result;
    }

    protected ValidationResult validateIfNotFractionalXmrValue(String input) {
        try {
            BigDecimal bd = new BigDecimal(input);
            final BigDecimal atomicUnits = bd.movePointRight(HavenoUtils.XMR_SMALLEST_UNIT_EXPONENT);
            if (atomicUnits.scale() > 0)
                return new ValidationResult(false, Res.get("validation.xmr.fraction"));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }

    protected ValidationResult validateIfNotExceedsMaxValue(String input) {
        try {
            final BigInteger amount = HavenoUtils.parseXmr(input);
            if (maxValue != null && amount.compareTo(maxValue) > 0)
                return new ValidationResult(false, Res.get("validation.xmr.tooLarge", HavenoUtils.formatXmr(maxValue, true)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }

    protected ValidationResult validateIfNotExceedsMaxTradeLimit(String input) {
        try {
            final BigInteger amount = HavenoUtils.parseXmr(input);
            if (maxTradeLimit != null && amount.compareTo(maxTradeLimit) > 0)
                return new ValidationResult(false, Res.get("validation.xmr.exceedsMaxTradeLimit", HavenoUtils.formatXmr(maxTradeLimit, true)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }

    protected ValidationResult validateIfNotUnderMinValue(String input) {
        try {
            final BigInteger amount = HavenoUtils.parseXmr(input);
            if (minValue != null && amount.compareTo(minValue) < 0)
                return new ValidationResult(false, Res.get("validation.xmr.tooSmall", HavenoUtils.formatXmr(minValue)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }
}
