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

import haveno.core.locale.CurrencyUtil;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.ParsingUtils;
import org.bitcoinj.core.Monetary;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Monero price value with variable precision.
 * <p>
 * <br/>
 * We wrap an object implementing the {@link Monetary} interface from bitcoinj. We respect the
 * number of decimal digits of precision specified in the {@code smallestUnitExponent()}, defined in
 * those classes, like {@link TraditionalMoney} or {@link CryptoMoney}.
 */
public class Price extends MonetaryWrapper implements Comparable<Price> {
    private static final Logger log = LoggerFactory.getLogger(Price.class);

    /**
     * Create a new {@code Price} from specified {@code Monetary}.
     *
     * @param monetary
     */
    public Price(Monetary monetary) {
        super(monetary);
    }

    /**
     * Parse the Bitcoin {@code Price} given a {@code currencyCode} and {@code inputValue}.
     *
     * @param currencyCode The currency code to parse, e.g "USD" or "LTC".
     * @param input        The input value to parse as a String, e.g "2.54" or "-0.0001".
     * @return The parsed Price.
     */
    public static Price parse(String currencyCode, String input) {
        String cleaned = ParsingUtils.convertCharsForNumber(input);
        if (CurrencyUtil.isTraditionalCurrency(currencyCode))
            return new Price(TraditionalMoney.parseTraditionalMoney(currencyCode, cleaned));
        else
            return new Price(CryptoMoney.parseCrypto(currencyCode, cleaned));
    }

    /**
     * Parse the Bitcoin {@code Price} given a {@code currencyCode} and {@code inputValue}.
     *
     * @param currencyCode The currency code to parse, e.g "USD" or "LTC".
     * @param value        The value to parse.
     * @return The parsed Price.
     */
    public static Price valueOf(String currencyCode, long value) {
        if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            return new Price(TraditionalMoney.valueOf(currencyCode, value));
        } else {
            return new Price(CryptoMoney.valueOf(currencyCode, value));
        }
    }

    public Volume getVolumeByAmount(BigInteger amount) {
        if (monetary instanceof TraditionalMoney)
            return new Volume(new TraditionalExchangeRate((TraditionalMoney) monetary).coinToTraditionalMoney(HavenoUtils.atomicUnitsToCoin(amount)));
        else if (monetary instanceof CryptoMoney)
            return new Volume(new CryptoExchangeRate((CryptoMoney) monetary).coinToCrypto(HavenoUtils.atomicUnitsToCoin(amount)));
        else
            throw new IllegalStateException("Monetary must be either of type TraditionalMoney or CryptoMoney");
    }

    public BigInteger getAmountByVolume(Volume volume) {
        Monetary monetary = volume.getMonetary();
        if (monetary instanceof TraditionalMoney && this.monetary instanceof TraditionalMoney)
            return HavenoUtils.coinToAtomicUnits(new TraditionalExchangeRate((TraditionalMoney) this.monetary).traditionalMoneyToCoin((TraditionalMoney) monetary));
        else if (monetary instanceof CryptoMoney && this.monetary instanceof CryptoMoney)
            return HavenoUtils.coinToAtomicUnits(new CryptoExchangeRate((CryptoMoney) this.monetary).cryptoToCoin((CryptoMoney) monetary));
        else
            return BigInteger.valueOf(0);
    }

    public String getCurrencyCode() {
        return monetary instanceof CryptoMoney ? ((CryptoMoney) monetary).getCurrencyCode() : ((TraditionalMoney) monetary).getCurrencyCode();
    }

    @Override
    public long getValue() {
        return monetary.getValue();
    }

    /**
     * Get the amount of whole coins or units as double.
     */
    public double getDoubleValue() {
        return BigDecimal.valueOf(monetary.getValue()).movePointLeft(monetary.smallestUnitExponent()).doubleValue();
    }

    @Override
    public int compareTo(@NotNull Price other) {
        if (!this.getCurrencyCode().equals(other.getCurrencyCode()))
            return this.getCurrencyCode().compareTo(other.getCurrencyCode());
        if (this.getValue() != other.getValue())
            return this.getValue() > other.getValue() ? 1 : -1;
        return 0;
    }

    public boolean isPositive() {
        return monetary instanceof CryptoMoney ? ((CryptoMoney) monetary).isPositive() : ((TraditionalMoney) monetary).isPositive();
    }

    public Price subtract(Price other) {
        if (monetary instanceof CryptoMoney) {
            return new Price(((CryptoMoney) monetary).subtract((CryptoMoney) other.monetary));
        } else {
            return new Price(((TraditionalMoney) monetary).subtract((TraditionalMoney) other.monetary));
        }
    }

    public String toFriendlyString() {
        return monetary instanceof CryptoMoney ?
                ((CryptoMoney) monetary).toFriendlyString() + "/XMR" :
                ((TraditionalMoney) monetary).toFriendlyString().replace(((TraditionalMoney) monetary).currencyCode, "") + "XMR/" + ((TraditionalMoney) monetary).currencyCode;
    }

    public String toPlainString() {
        return monetary instanceof CryptoMoney ? ((CryptoMoney) monetary).toPlainString() : ((TraditionalMoney) monetary).toPlainString();
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
