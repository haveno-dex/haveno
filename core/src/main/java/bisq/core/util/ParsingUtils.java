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
     * Temporary multiplier to convert Coin satoshis (denominating XMR centineros) to XMR atomic units.
     *
     * TODO (woodser): replace bitcoinj/Coin entirely?
     */
    private static BigInteger XMR_SATOSHI_MULTIPLIER = BigInteger.valueOf(10000); // TODO (woodser): make this private and expose satoshisToXmrAtomicUnits()

    /**
     * Converts Coin satoshis (the base unit throughout Bisq) to XMR atomic units.
     *
     * @param satoshis represents an amount in XMR atomic units scaled to a long
     * @return BigInteger is the equivalent amount in XMR atomic units
     */
    public static BigInteger satoshisToXmrAtomicUnits(long satoshis) {
        return BigInteger.valueOf(satoshis).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
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
