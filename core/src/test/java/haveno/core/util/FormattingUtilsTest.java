package haveno.core.util;

import com.natpryce.makeiteasy.Maker;
import com.natpryce.makeiteasy.Property;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.natpryce.makeiteasy.MakeItEasy.a;
import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FormattingUtilsTest {
    private static final Property<Price, String> currencyCode = new Property<>();
    private static final Property<Price, String> priceString = new Property<>();
    private static final Maker<Price> usdPrice = a(lookup ->
            new Price(TraditionalMoney.parseTraditionalMoney(lookup.valueOf(currencyCode, "USD"), lookup.valueOf(priceString, "100"))));

    @BeforeEach
    public void setUp() {
        Locale.setDefault(new Locale("en", "US"));
        GlobalSettings.setLocale(new Locale("en", "US"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testFormatDurationAsWords() {
        long oneDay = TimeUnit.DAYS.toMillis(1);
        long oneHour = TimeUnit.HOURS.toMillis(1);
        long oneMinute = TimeUnit.MINUTES.toMillis(1);
        long oneSecond = TimeUnit.SECONDS.toMillis(1);

        assertEquals("1 hour, 0 minutes", FormattingUtils.formatDurationAsWords(oneHour));
        assertEquals("1 day, 0 hours, 0 minutes", FormattingUtils.formatDurationAsWords(oneDay));
        assertEquals("2 days, 0 hours, 1 minute", FormattingUtils.formatDurationAsWords(oneDay * 2 + oneMinute));
        assertEquals("2 days, 0 hours, 2 minutes", FormattingUtils.formatDurationAsWords(oneDay * 2 + oneMinute * 2));
        assertEquals("1 hour, 0 minutes, 0 seconds", FormattingUtils.formatDurationAsWords(oneHour, true, true));
        assertEquals("1 hour, 0 minutes, 1 second", FormattingUtils.formatDurationAsWords(oneHour + oneSecond, true, true));
        assertEquals("1 hour, 0 minutes, 2 seconds", FormattingUtils.formatDurationAsWords(oneHour + oneSecond * 2, true, true));
        assertEquals("2 days, 21 hours, 28 minutes", FormattingUtils.formatDurationAsWords(oneDay * 2 + oneHour * 21 + oneMinute * 28));
        assertEquals("110 days", FormattingUtils.formatDurationAsWords(oneDay * 110, false, false));
        assertEquals("10 days, 10 hours, 10 minutes, 10 seconds", FormattingUtils.formatDurationAsWords(oneDay * 10 + oneHour * 10 + oneMinute * 10 + oneSecond * 10, true, false));
        assertEquals("1 hour, 2 seconds", FormattingUtils.formatDurationAsWords(oneHour + oneSecond * 2, true, false));
        assertEquals("1 hour", FormattingUtils.formatDurationAsWords(oneHour + oneSecond * 2, false, false));
        assertEquals("0 hours, 0 minutes, 1 second", FormattingUtils.formatDurationAsWords(oneSecond, true, true));
        assertEquals("1 second", FormattingUtils.formatDurationAsWords(oneSecond, true, false));
        assertEquals("0 hours", FormattingUtils.formatDurationAsWords(oneSecond, false, false));
        assertEquals("", FormattingUtils.formatDurationAsWords(0));
        assertTrue(FormattingUtils.formatDurationAsWords(0).isEmpty());
    }

    @Test
    public void testFormatPrice() {
        assertEquals("100.0000", FormattingUtils.formatPrice(make(usdPrice)));
        assertEquals("7098.4700", FormattingUtils.formatPrice(make(usdPrice.but(with(priceString, "7098.4700")))));
    }
}
