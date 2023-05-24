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
import haveno.core.trade.HavenoUtils;
import org.bitcoinj.core.Monetary;
import org.bitcoinj.utils.MonetaryFormat;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

public class VolumeUtil {

    private static final MonetaryFormat FIAT_VOLUME_FORMAT = new MonetaryFormat().shift(0).minDecimals(0).repeatOptionalDecimals(0, 0);
    private static final MonetaryFormat TRADITIONAL_VOLUME_FORMAT = new MonetaryFormat().shift(0).minDecimals(4).repeatOptionalDecimals(0, 0);

    public static Volume getRoundedFiatVolume(Volume volumeByAmount) {
        // We want to get rounded to 1 unit of the fiat currency, e.g. 1 EUR.
        return getAdjustedFiatVolume(volumeByAmount, 1);
    }

    public static Volume getAdjustedVolumeForHalCash(Volume volumeByAmount) {
        // EUR has precision 8 and we want multiple of 10 so we divide by 1000000000 then
        // round and multiply with 10
        return getAdjustedFiatVolume(volumeByAmount, 10);
    }

    /**
     *
     * @param volumeByAmount      The volume generated from an amount
     * @param factor              The factor used for rounding. E.g. 1 means rounded to
     *                            units of 1 EUR, 10 means rounded to 10 EUR.
     * @return The adjusted Fiat volume
     */
    public static Volume getAdjustedFiatVolume(Volume volumeByAmount, int factor) {
        // Fiat currencies use precision 8 and we want multiple of factor so we divide by 100000000 * factor then
        // round and multiply with factor
        long roundedVolume = Math.round((double) volumeByAmount.getValue() / (100000000d * factor)) * factor;
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
        return formatVolume(volume, getMonetaryFormat(volume.getCurrencyCode()), false);
    }

    private static String formatVolume(Volume volume, MonetaryFormat fiatVolumeFormat, boolean appendCurrencyCode) {
        if (volume != null) {
            Monetary monetary = volume.getMonetary();
            if (monetary instanceof TraditionalMoney)
                return FormattingUtils.formatTraditionalMoney((TraditionalMoney) monetary, fiatVolumeFormat, appendCurrencyCode);
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
        return formatVolume(volume, getMonetaryFormat(volume.getCurrencyCode()), appendCode);
    }

    public static String formatAverageVolumeWithCode(Volume volume) {
        return formatVolume(volume, getMonetaryFormat(volume.getCurrencyCode()).minDecimals(2), true);
    }

    public static String formatVolumeLabel(String currencyCode) {
        return formatVolumeLabel(currencyCode, "");
    }

    public static String formatVolumeLabel(String currencyCode, String postFix) {
        return Res.get("formatter.formatVolumeLabel",
                currencyCode, postFix);
    }

    private static MonetaryFormat getMonetaryFormat(String currencyCode) {
        return CurrencyUtil.isFiatCurrency(currencyCode) ? FIAT_VOLUME_FORMAT : TRADITIONAL_VOLUME_FORMAT;
    }
}
