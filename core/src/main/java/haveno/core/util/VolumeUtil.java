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

package haveno.core.util;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.CryptoExchangeRate;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.TraditionalExchangeRate;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import org.bitcoinj.core.Monetary;
import org.bitcoinj.utils.MonetaryFormat;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Locale;

public class VolumeUtil {

    private static final MonetaryFormat VOLUME_FORMAT_UNIT = new MonetaryFormat().shift(0).minDecimals(0).repeatOptionalDecimals(0, 0);
    private static final MonetaryFormat VOLUME_FORMAT_PRECISE = new MonetaryFormat().shift(0).minDecimals(4).repeatOptionalDecimals(0, 0);

    private static double EXPONENT = Math.pow(10, TraditionalMoney.SMALLEST_UNIT_EXPONENT); // 1000000000000 with precision 8

    public static Volume getAdjustedVolume(Volume volumeByAmount, String paymentMethodId) {
        if (PaymentMethod.isRoundedForAtmCash(paymentMethodId))
            return VolumeUtil.getRoundedAtmCashVolume(volumeByAmount);
        else if (CurrencyUtil.isVolumeRoundedToNearestUnit(volumeByAmount.getCurrencyCode()))
            return VolumeUtil.getRoundedVolumeUnit(volumeByAmount);
        else if (CurrencyUtil.isTraditionalCurrency(volumeByAmount.getCurrencyCode()))
            return VolumeUtil.getRoundedVolumePrecise(volumeByAmount);
        return volumeByAmount;
    }

    public static Volume getRoundedVolumeUnit(Volume volumeByAmount) {
        // We want to get rounded to 1 unit of the currency, e.g. 1 EUR.
        return getAdjustedVolumeUnit(volumeByAmount, 1);
    }

    private static Volume getRoundedAtmCashVolume(Volume volumeByAmount) {
        // EUR has precision TraditionalMoney.SMALLEST_UNIT_EXPONENT and we want multiple of 10 so we divide by EXPONENT then
        // round and multiply with 10
        return getAdjustedVolumeUnit(volumeByAmount, 10);
    }

    public static Volume getRoundedVolumePrecise(Volume volumeByAmount) {
        DecimalFormat decimalFormat = new DecimalFormat("#.####", HavenoUtils.DECIMAL_FORMAT_SYMBOLS);
        double roundedVolume = Double.parseDouble(decimalFormat.format(Double.parseDouble(volumeByAmount.toString())));
        return Volume.parse(String.valueOf(roundedVolume), volumeByAmount.getCurrencyCode());
    }

    /**
     *
     * @param volumeByAmount      The volume generated from an amount
     * @param factor              The factor used for rounding. E.g. 1 means rounded to
     *                            units of 1 EUR, 10 means rounded to 10 EUR.
     * @return The adjusted Fiat volume
     */
    public static Volume getAdjustedVolumeUnit(Volume volumeByAmount, int factor) {
        // Fiat currencies use precision TraditionalMoney.SMALLEST_UNIT_EXPONENT and we want multiple of factor so we divide
        // by EXPONENT * factor then round and multiply with factor
        long roundedVolume = Math.round((double) volumeByAmount.getValue() / (EXPONENT * factor)) * factor;
        // Smallest allowed volume is factor (e.g. 10 EUR or 1 EUR,...)
        roundedVolume = Math.max(factor, roundedVolume);
        return Volume.parse(String.valueOf(roundedVolume), volumeByAmount.getCurrencyCode());
    }

    public static Volume getVolume(BigInteger amount, Price price) {
        // TODO: conversion to Coin loses precision
        if (price.getMonetary() instanceof CryptoMoney) {
            return new Volume(new CryptoExchangeRate((CryptoMoney) price.getMonetary()).coinToCrypto(HavenoUtils.atomicUnitsToCoin(amount)));
        } else {
            return new Volume(new TraditionalExchangeRate((TraditionalMoney) price.getMonetary()).coinToTraditionalMoney(HavenoUtils.atomicUnitsToCoin(amount)));
        }
    }


    public static String formatVolume(Offer offer, Boolean decimalAligned, int maxNumberOfDigits) {
        return formatVolume(offer, decimalAligned, maxNumberOfDigits, true);
    }

    public static String formatVolume(Offer offer, Boolean decimalAligned, int maxNumberOfDigits, boolean showRange) {
        String formattedVolume = offer.isRange() && showRange
                ? formatVolume(offer.getMinVolume()) + FormattingUtils.RANGE_SEPARATOR + formatVolume(offer.getVolume())
                : formatVolume(offer.getVolume());

        if (decimalAligned) {
            formattedVolume = FormattingUtils.fillUpPlacesWithEmptyStrings(formattedVolume, maxNumberOfDigits);
        }
        return formattedVolume;
    }

    public static String formatLargeFiat(double value, String currency) {
        if (value <= 0) {
            return "0";
        }
        NumberFormat numberFormat = DecimalFormat.getInstance(Locale.US);
        numberFormat.setGroupingUsed(true);
        return numberFormat.format(value) + " " + currency;
    }

    public static String formatLargeFiatWithUnitPostFix(double value, String currency) {
        if (value <= 0) {
            return "0";
        }
        String[] units = new String[]{"", "K", "M", "B"};
        int digitGroups = (int) (Math.log10(value) / Math.log10(1000));
        return new DecimalFormat("#,##0.###")
                .format(value / Math.pow(1000, digitGroups)) + units[digitGroups] + " " + currency;
    }

    public static String formatVolume(Volume volume) {
        return volume == null ? "" : formatVolume(volume, getMonetaryFormat(volume.getCurrencyCode()), false);
    }

    private static String formatVolume(Volume volume, MonetaryFormat volumeFormat, boolean appendCurrencyCode) {
        if (volume != null) {
            Monetary monetary = volume.getMonetary();
            if (monetary instanceof TraditionalMoney)
                return FormattingUtils.formatTraditionalMoney((TraditionalMoney) monetary, volumeFormat, appendCurrencyCode);
            else
                return FormattingUtils.formatCryptoVolume((CryptoMoney) monetary, appendCurrencyCode);
        } else {
            return "";
        }
    }

    public static String formatVolumeWithCode(Volume volume) {
        return formatVolume(volume, true);
    }

    public static String formatVolume(Volume volume, boolean appendCode) {
        return formatVolume(volume, volume == null ? null : getMonetaryFormat(volume.getCurrencyCode()), appendCode);
    }

    public static String formatAverageVolumeWithCode(Volume volume) {
        return formatVolume(volume, volume == null ? null : getMonetaryFormat(volume.getCurrencyCode()).minDecimals(2), true);
    }

    public static String formatVolumeLabel(String currencyCode) {
        return formatVolumeLabel(currencyCode, "");
    }

    public static String formatVolumeLabel(String currencyCode, String postFix) {
        return Res.get("formatter.formatVolumeLabel",
                currencyCode, postFix);
    }

    private static MonetaryFormat getMonetaryFormat(String currencyCode) {
        return CurrencyUtil.isVolumeRoundedToNearestUnit(currencyCode) ? VOLUME_FORMAT_UNIT : VOLUME_FORMAT_PRECISE;
    }

    public static Volume sum(Collection<Volume> volumes) {
        if (volumes == null || volumes.isEmpty()) {
            return null;
        }   
        Volume sum = null;
        for (Volume volume : volumes) {
            if (sum == null) {
                sum = volume;
            } else {
                if (!sum.getCurrencyCode().equals(volume.getCurrencyCode())) {
                    throw new IllegalArgumentException("Cannot sum volumes with different currencies");
                }
                sum = add(sum, volume);
            }
        }
        return sum;
    }

    public static Volume add(Volume volume1, Volume volume2) {
        if (volume1 == null) return volume2;
        if (volume2 == null) return volume1;
        if (!volume1.getCurrencyCode().equals(volume2.getCurrencyCode())) {
            throw new IllegalArgumentException("Cannot add volumes with different currencies");
        }
        if (volume1.getMonetary() instanceof CryptoMoney) {
            return new Volume(((CryptoMoney) volume1.getMonetary()).add((CryptoMoney) volume2.getMonetary()));
        } else {
            return new Volume(((TraditionalMoney) volume1.getMonetary()).add((TraditionalMoney) volume2.getMonetary()));
        }
    }
}
