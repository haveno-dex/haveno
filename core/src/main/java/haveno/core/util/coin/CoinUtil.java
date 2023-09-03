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

package haveno.core.util.coin;

import com.google.common.annotations.VisibleForTesting;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.wallet.Restrictions;
import org.bitcoinj.core.Coin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;

import static com.google.common.base.Preconditions.checkArgument;
import static haveno.core.util.VolumeUtil.getAdjustedVolumeUnit;

public class CoinUtil {


    public static Coin minCoin(Coin a, Coin b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static Coin maxCoin(Coin a, Coin b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static double getFeePerVbyte(Coin miningFee, int txVsize) {
        double value = miningFee != null ? miningFee.value : 0;
        return MathUtils.roundDouble((value / (double) txVsize), 2);
    }

    /**
     * @param value Btc amount to be converted to percent value. E.g. 0.01 BTC is 1% (of 1 BTC)
     * @return The percentage value as double (e.g. 1% is 0.01)
     */
    public static double getAsPercentPerBtc(BigInteger value) {
        return getAsPercentPerBtc(value, HavenoUtils.xmrToAtomicUnits(1.0));
    }

    /**
     * @param part Btc amount to be converted to percent value, based on total value passed.
     *              E.g. 0.1 BTC is 25% (of 0.4 BTC)
     * @param total Total Btc amount the percentage part is calculated from
     *
     * @return The percentage value as double (e.g. 1% is 0.01)
     */
    public static double getAsPercentPerBtc(BigInteger part, BigInteger total) {
        return MathUtils.roundDouble(HavenoUtils.divide(part == null ? BigInteger.valueOf(0) : part, total == null ? BigInteger.valueOf(1) : total), 4);
    }

    /**
     * @param percent       The percentage value as double (e.g. 1% is 0.01)
     * @param amount        The amount as atomic units for the percentage calculation
     * @return The percentage as atomic units (e.g. 1% of 1 BTC is 0.01 BTC)
     */
    public static BigInteger getPercentOfAmount(double percent, BigInteger amount) {
        if (amount == null) amount = BigInteger.valueOf(0);
        return BigDecimal.valueOf(percent).multiply(new BigDecimal(amount)).toBigInteger();
    }

    public static BigInteger getRoundedAmount(BigInteger amount, Price price, long maxTradeLimit, String currencyCode, String paymentMethodId) {
        if (PaymentMethod.isRoundedForAtmCash(paymentMethodId)) {
            return getRoundedAtmCashAmount(amount, price, maxTradeLimit);
        } else if (CurrencyUtil.isVolumeRoundedToNearestUnit(currencyCode)) {
            return getRoundedAmountUnit(amount, price, maxTradeLimit);
        } else if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            return getRoundedAmountPrecise(amount, price, maxTradeLimit);
        }
        return amount;
    }

    public static BigInteger getRoundedAtmCashAmount(BigInteger amount, Price price, long maxTradeLimit) {
        return getAdjustedAmount(amount, price, maxTradeLimit, 10);
    }

    /**
     * Calculate the possibly adjusted amount for {@code amount}, taking into account the
     * {@code price} and {@code maxTradeLimit} and {@code factor}.
     *
     * @param amount            Monero amount which is a candidate for getting rounded.
     * @param price             Price used in relation to that amount.
     * @param maxTradeLimit     The max. trade limit of the users account, in atomic units.
     * @return The adjusted amount
     */
    public static BigInteger getRoundedAmountUnit(BigInteger amount, Price price, long maxTradeLimit) {
        return getAdjustedAmount(amount, price, maxTradeLimit, 1);
    }
    
    public static BigInteger getRoundedAmountPrecise(BigInteger amount, Price price, long maxTradeLimit) {
        DecimalFormat decimalFormat = new DecimalFormat("#.####");
        double roundedXmrAmount = Double.parseDouble(decimalFormat.format(HavenoUtils.atomicUnitsToXmr(amount)));
        return HavenoUtils.xmrToAtomicUnits(roundedXmrAmount);
    }

    /**
     * Calculate the possibly adjusted amount for {@code amount}, taking into account the
     * {@code price} and {@code maxTradeLimit} and {@code factor}.
     *
     * @param amount            amount which is a candidate for getting rounded.
     * @param price             Price used in relation to that amount.
     * @param maxTradeLimit     The max. trade limit of the users account, in satoshis.
     * @param factor            The factor used for rounding. E.g. 1 means rounded to units of
     *                          1 EUR, 10 means rounded to 10 EUR, etc.
     * @return The adjusted amount
     */
    @VisibleForTesting
    static BigInteger getAdjustedAmount(BigInteger amount, Price price, long maxTradeLimit, int factor) {
        checkArgument(
                amount.longValueExact() >= HavenoUtils.xmrToAtomicUnits(0.0001).longValueExact(),
                "amount needs to be above minimum of 0.0001 xmr" // TODO: update amount for XMR
        );
        checkArgument(
                factor > 0,
                "factor needs to be positive"
        );
        // Amount must result in a volume of min factor units of the fiat currency, e.g. 1 EUR or
        // 10 EUR in case of HalCash.
        Volume smallestUnitForVolume = Volume.parse(String.valueOf(factor), price.getCurrencyCode());
        if (smallestUnitForVolume.getValue() <= 0)
            return BigInteger.valueOf(0);

        BigInteger smallestUnitForAmount = price.getAmountByVolume(smallestUnitForVolume);
        long minTradeAmount = Restrictions.getMinTradeAmount().longValueExact();

        // We use 10 000 satoshi as min allowed amount
        checkArgument(
                minTradeAmount >= HavenoUtils.xmrToAtomicUnits(0.0001).longValueExact(),
                "MinTradeAmount must be at least 0.0001 xmr" // TODO: update amount for XMR
        );
        smallestUnitForAmount = BigInteger.valueOf(Math.max(minTradeAmount, smallestUnitForAmount.longValueExact()));
        // We don't allow smaller amount values than smallestUnitForAmount
        boolean useSmallestUnitForAmount = amount.compareTo(smallestUnitForAmount) < 0;

        // We get the adjusted volume from our amount
        Volume volume = useSmallestUnitForAmount
                ? getAdjustedVolumeUnit(price.getVolumeByAmount(smallestUnitForAmount), factor)
                : getAdjustedVolumeUnit(price.getVolumeByAmount(amount), factor);
        if (volume.getValue() <= 0)
            return BigInteger.valueOf(0);

        // From that adjusted volume we calculate back the amount. It might be a bit different as
        // the amount used as input before due rounding.
        BigInteger amountByVolume = price.getAmountByVolume(volume);

        // For the amount we allow only 4 decimal places
        // TODO: remove rounding for XMR?
        long adjustedAmount = HavenoUtils.centinerosToAtomicUnits(Math.round((double) HavenoUtils.atomicUnitsToCentineros(amountByVolume) / 10000d) * 10000).longValueExact();

        // If we are above our trade limit we reduce the amount by the smallestUnitForAmount
        while (adjustedAmount > maxTradeLimit) {
            adjustedAmount -= smallestUnitForAmount.longValueExact();
        }
        adjustedAmount = Math.max(minTradeAmount, adjustedAmount);
        adjustedAmount = Math.min(maxTradeLimit, adjustedAmount);
        return BigInteger.valueOf(adjustedAmount);
    }
}
