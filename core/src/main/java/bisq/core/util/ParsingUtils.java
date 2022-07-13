package bisq.core.util;

import bisq.core.monetary.Price;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.util.MathUtils;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;

import org.apache.commons.lang3.StringUtils;
import java.math.BigDecimal;
import java.math.BigInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParsingUtils {

    // multipliers to convert units
    private static BigInteger CENTINEROS_AU_MULTIPLIER = new BigInteger("10000");
    private static BigInteger XMR_AU_MULTIPLIER = new BigInteger("1000000000000");

    public static BigInteger coinToAtomicUnits(Coin coin) {
        return centinerosToAtomicUnits(coin.value);
    }

    public static BigInteger centinerosToAtomicUnits(long centineros) {
        return BigInteger.valueOf(centineros).multiply(ParsingUtils.CENTINEROS_AU_MULTIPLIER);
    }

    public static double centinerosToXmr(long centineros) {
        return atomicUnitsToXmr(centinerosToAtomicUnits(centineros));
    }

    public static long atomicUnitsToCentineros(long atomicUnits) { // TODO: atomic units should be BigInteger; remove this?
      return atomicUnits / CENTINEROS_AU_MULTIPLIER.longValue();
    }

    public static long atomicUnitsToCentineros(BigInteger atomicUnits) {
        return atomicUnits.divide(CENTINEROS_AU_MULTIPLIER).longValueExact();
      }

    public static double atomicUnitsToXmr(BigInteger atomicUnits) {
      return new BigDecimal(atomicUnits).divide(new BigDecimal(XMR_AU_MULTIPLIER)).doubleValue();
    }

    public static BigInteger xmrToAtomicUnits(double xmr) {
        return BigDecimal.valueOf(xmr).multiply(new BigDecimal(XMR_AU_MULTIPLIER)).toBigInteger();
    }

    public static long xmrToCentineros(double xmr) {
        return atomicUnitsToCentineros(xmrToAtomicUnits(xmr));
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
