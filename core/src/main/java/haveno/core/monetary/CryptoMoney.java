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

package haveno.core.monetary;

import com.google.common.math.LongMath;
import haveno.core.util.ParsingUtils;
import org.bitcoinj.core.Monetary;
import org.bitcoinj.utils.MonetaryFormat;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Cloned from Fiat class and altered SMALLEST_UNIT_EXPONENT as Fiat is final.
 * <p/>
 * Represents a monetary crypto value. It was decided to not fold this into {@link org.bitcoinj.core.Coin} because of type
 * safety. Volume values always come with an attached currency code.
 * <p/>
 * This class is immutable.
 */
public final class CryptoMoney implements Monetary, Comparable<CryptoMoney> {
    /**
     * The absolute value of exponent of the value of a "smallest unit" in scientific notation. We picked 4 rather than
     * 2, because in financial applications it's common to use sub-cent precision.
     */
    public static final int SMALLEST_UNIT_EXPONENT = 8;
    private static final MonetaryFormat FRIENDLY_FORMAT = new MonetaryFormat().shift(0).minDecimals(2).repeatOptionalDecimals(2, 1).postfixCode();
    private static final MonetaryFormat PLAIN_FORMAT = new MonetaryFormat().shift(0).minDecimals(0).repeatOptionalDecimals(1, 8).noCode();

    /**
     * The number of smallest units of this monetary value.
     */
    public final long value;
    public final String currencyCode;

    private CryptoMoney(final String currencyCode, final long value) {
        this.value = value;
        this.currencyCode = currencyCode;
    }

    public static CryptoMoney valueOf(final String currencyCode, final long value) {
        return new CryptoMoney(currencyCode, value);
    }

    @Override
    public int smallestUnitExponent() {
        return SMALLEST_UNIT_EXPONENT;
    }

    /**
     * Returns the number of "smallest units" of this monetary value.
     */
    @Override
    public long getValue() {
        return value;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    /**
     * Parses an amount expressed in the way humans are used to.
     * <p/>
     * <p/>
     * This takes string in a format understood by {@link BigDecimal#BigDecimal(String)}, for example "0", "1", "0.10",
     * "1.23E3", "1234.5E-5".
     *
     * @throws IllegalArgumentException if you try to specify fractional satoshis, or a value out of range.
     */
    public static CryptoMoney parseCrypto(final String currencyCode, String input) {
        String cleaned = ParsingUtils.convertCharsForNumber(input);
        try {
            long val = new BigDecimal(cleaned).movePointRight(SMALLEST_UNIT_EXPONENT)
                    .toBigIntegerExact().longValue();
            return CryptoMoney.valueOf(currencyCode, val);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public CryptoMoney add(final CryptoMoney value) {
        checkArgument(value.currencyCode.equals(currencyCode));
        return new CryptoMoney(currencyCode, LongMath.checkedAdd(this.value, value.value));
    }

    public CryptoMoney subtract(final CryptoMoney value) {
        checkArgument(value.currencyCode.equals(currencyCode));
        return new CryptoMoney(currencyCode, LongMath.checkedSubtract(this.value, value.value));
    }

    public CryptoMoney multiply(final long factor) {
        return new CryptoMoney(currencyCode, LongMath.checkedMultiply(this.value, factor));
    }

    public CryptoMoney divide(final long divisor) {
        return new CryptoMoney(currencyCode, this.value / divisor);
    }

    public CryptoMoney[] divideAndRemainder(final long divisor) {
        return new CryptoMoney[]{new CryptoMoney(currencyCode, this.value / divisor), new CryptoMoney(currencyCode, this.value % divisor)};
    }

    public long divide(final CryptoMoney divisor) {
        checkArgument(divisor.currencyCode.equals(currencyCode));
        return this.value / divisor.value;
    }

    /**
     * Returns true if and only if this instance represents a monetary value greater than zero, otherwise false.
     */
    public boolean isPositive() {
        return signum() == 1;
    }

    /**
     * Returns true if and only if this instance represents a monetary value less than zero, otherwise false.
     */
    public boolean isNegative() {
        return signum() == -1;
    }

    /**
     * Returns true if and only if this instance represents zero monetary value, otherwise false.
     */
    public boolean isZero() {
        return signum() == 0;
    }

    /**
     * Returns true if the monetary value represented by this instance is greater than that of the given other Coin,
     * otherwise false.
     */
    public boolean isGreaterThan(CryptoMoney other) {
        return compareTo(other) > 0;
    }

    /**
     * Returns true if the monetary value represented by this instance is less than that of the given other Coin,
     * otherwise false.
     */
    public boolean isLessThan(CryptoMoney other) {
        return compareTo(other) < 0;
    }

    @Override
    public int signum() {
        if (this.value == 0)
            return 0;
        return this.value < 0 ? -1 : 1;
    }

    public CryptoMoney negate() {
        return new CryptoMoney(currencyCode, -this.value);
    }

    public String toFriendlyString() {
        return FRIENDLY_FORMAT.code(0, currencyCode).format(this).toString();
    }

    /**
     * <p>
     * Returns the value as a plain string denominated in BTC. The result is unformatted with no trailing zeroes. For
     * instance, a value of 150000 satoshis gives an output string of "0.0015" BTC
     * </p>
     */
    public String toPlainString() {
        return PLAIN_FORMAT.format(this).toString();
    }

    @Override
    public String toString() {
        return toPlainString();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (o == null || o.getClass() != getClass())
            return false;
        final CryptoMoney other = (CryptoMoney) o;
        return this.value == other.value && this.currencyCode.equals(other.currencyCode);
    }

    @Override
    public int hashCode() {
        return (int) this.value + 37 * this.currencyCode.hashCode();
    }

    @Override
    public int compareTo(@NotNull final CryptoMoney other) {
        if (!this.currencyCode.equals(other.currencyCode))
            return this.currencyCode.compareTo(other.currencyCode);
        if (this.value != other.value)
            return this.value > other.value ? 1 : -1;
        return 0;
    }
}
