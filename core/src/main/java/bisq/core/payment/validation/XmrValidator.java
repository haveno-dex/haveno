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

import bisq.core.locale.Res;
import bisq.core.trade.HavenoUtils;
import bisq.core.util.validation.NumberValidator;

import javax.inject.Inject;

import java.math.BigDecimal;
import java.math.BigInteger;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

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
                return new ValidationResult(false, Res.get("validation.btc.fraction"));
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
                return new ValidationResult(false, Res.get("validation.btc.toLarge", HavenoUtils.formatToXmrWithCode(maxValue)));
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
                return new ValidationResult(false, Res.get("validation.btc.exceedsMaxTradeLimit", HavenoUtils.formatToXmrWithCode(maxTradeLimit)));
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
                return new ValidationResult(false, Res.get("validation.btc.toSmall", HavenoUtils.formatToXmr(minValue)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }
}
