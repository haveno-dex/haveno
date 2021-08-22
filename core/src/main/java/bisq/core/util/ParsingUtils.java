package bisq.core.util;

import bisq.core.monetary.Price;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.util.MathUtils;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;

import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParsingUtils {

    /**
     * Multiplier to convert centineros (the base XMR unit of Coin) to atomic units.
     */
    private static BigInteger CENTINEROS_AU_MULTIPLIER = BigInteger.valueOf(10000);
    
    /**
     * Convert Coin (denominated in centineros) to atomic units.
     *
     * @param coin has an amount denominated in centineros
     * @return BigInteger the coin amount denominated in atomic units
     */
    public static BigInteger coinToAtomicUnits(Coin coin) {
        return centinerosToAtomicUnits(coin.value);
    }

    /**
     * Convert centineros (the base unit of Coin) to atomic units.
     *
     * @param centineros denominates an amount of XMR in centineros
     * @return BigInteger the amount denominated in atomic units
     */
    public static BigInteger centinerosToAtomicUnits(long centineros) {
        return BigInteger.valueOf(centineros).multiply(ParsingUtils.CENTINEROS_AU_MULTIPLIER);
    }
    
    public static Coin parseToCoin(String input, CoinFormatter coinFormatter) {
        return parseToCoin(input, coinFormatter.getMonetaryFormat());
    }

    public static Coin parseToCoin(String input, MonetaryFormat coinFormat) {
        if (input != null && input.length() > 0) {
            try {
                return coinFormat.parse(cleanDoubleInput(input));
            } catch (Throwable t) {
                log.warn("Exception at parseToBtc: " + t.toString());
                return Coin.ZERO;
            }
        } else {
            return Coin.ZERO;
        }
    }

    public static double parseNumberStringToDouble(String input) throws NumberFormatException {
        return Double.parseDouble(cleanDoubleInput(input));
    }

    public static double parsePercentStringToDouble(String percentString) throws NumberFormatException {
        String input = percentString.replace("%", "");
        input = cleanDoubleInput(input);
        double value = Double.parseDouble(input);
        return MathUtils.roundDouble(value / 100d, 4);
    }

    public static long parsePriceStringToLong(String currencyCode, String amount, int precision) {
        if (amount == null || amount.isEmpty())
            return 0;

        long value = 0;
        try {
            double amountValue = Double.parseDouble(amount);
            amount = FormattingUtils.formatRoundedDoubleWithPrecision(amountValue, precision);
            value = Price.parse(currencyCode, amount).getValue();
        } catch (NumberFormatException ignore) {
            // expected NumberFormatException if input is not a number
        } catch (Throwable t) {
            log.error("parsePriceStringToLong: " + t.toString());
        }

        return value;
    }

    public static String convertCharsForNumber(String input) {
        // Some languages like Finnish use the long dash for the minus
        input = input.replace("−", "-");
        input = StringUtils.deleteWhitespace(input);
        return input.replace(",", ".");
    }

    public static String cleanDoubleInput(String input) {
        input = convertCharsForNumber(input);
        if (input.equals("."))
            input = input.replace(".", "0.");
        if (input.equals("-."))
            input = input.replace("-.", "-0.");
        // don't use String.valueOf(Double.parseDouble(input)) as return value as it gives scientific
        // notation (1.0E-6) which screw up coinFormat.parse
        //noinspection ResultOfMethodCallIgnored
        // Just called to check if we have a valid double, throws exception otherwise
        //noinspection ResultOfMethodCallIgnored
        Double.parseDouble(input);
        return input;
    }
}
