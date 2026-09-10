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

package haveno.core.monetary;

import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.api.CoreOffersService;
import haveno.core.locale.Res;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.util.validation.AmountValidator8Decimals;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static haveno.core.monetary.Price.parse;
import static haveno.core.monetary.Price.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PriceTest {

    @Test
    public void testTraditionalTriggerAndAlertPricesKeepRounding() {
        Res.setup();
        for (String input : new String[]{"1.123456789", "1,123456789", "1123456789e-9"}) {
            assertTrue(PriceUtil.getPriceValidator("USD").validate(input).isValid, input);
            assertTrue(PriceUtil.isTriggerPriceValid(input, null, false, "USD").isValid, input);
            assertEquals(112345679L, PriceUtil.getMarketPriceAsLong(input, "USD"), input);
        }
        assertFalse(PriceUtil.getPriceValidator("USD").validate("0").isValid);
        assertFalse(PriceUtil.getPriceValidator("USD").validate("100000001").isValid);
        assertFalse(new AmountValidator8Decimals().validate("1.123456789").isValid);
    }

    @Test
    public void testApiCloneCryptoPricePreservesExactValuesAndNormalizedSyntax() throws Throwable {
        assertEquals(112345678L, apiClonePriceValue(" 1,123456780 ", "ERG"));
        assertEquals(9007199254740993L, apiClonePriceValue("90071992.54740993", "ERG"));
        assertEquals(Long.MAX_VALUE, apiClonePriceValue("92233720368.54775807", "ERG"));
    }

    @Test
    public void testApiCloneCryptoPriceRejectsUnrepresentableOverrides() {
        for (String input : new String[]{"1.123456789", "92233720368.54775808", "-92233720368.54775809", "1e20"}) {
            assertThrows(IllegalArgumentException.class, () -> apiClonePriceValue(input, "ERG"), input);
        }
    }

    @Test
    public void testApiCloneTraditionalPriceKeepsExistingParser() throws Throwable {
        assertEquals(112345678L, apiClonePriceValue(" 1,123456780 ", "USD"));
        assertThrows(IllegalArgumentException.class, () -> apiClonePriceValue("1.123456789", "USD"));
    }

    private long apiClonePriceValue(String input, String currencyCode) throws Throwable {
        CreateOfferService createOfferService = mock(CreateOfferService.class);
        OpenOfferManager manager = mock(OpenOfferManager.class);
        OpenOffer sourceOpenOffer = mock(OpenOffer.class);
        Offer sourceOffer = mock(Offer.class);
        User user = mock(User.class);
        PaymentAccount account = mock(PaymentAccount.class);
        when(manager.getOpenOffer("source")).thenReturn(Optional.of(sourceOpenOffer));
        when(sourceOpenOffer.getOffer()).thenReturn(sourceOffer);
        when(sourceOffer.isMyOffer(null)).thenReturn(true);
        when(user.getPaymentAccount("payment")).thenReturn(account);

        // capture the price at the real clone producer and stop before offer placement
        AtomicReference<Price> constructedPrice = new AtomicReference<>();
        RuntimeException stopBeforePlacement = new RuntimeException("price captured");
        when(createOfferService.createClonedOffer(eq(sourceOffer), eq(currencyCode), any(Price.class),
                eq(false), eq(0d), eq(account), eq("info"))).thenAnswer(invocation -> {
                    constructedPrice.set(invocation.getArgument(2));
                    throw stopBeforePlacement;
                });

        CoreOffersService service = new CoreOffersService(null, null, null, createOfferService, null, null,
                manager, null, user, null, null);
        Method clone = CoreOffersService.class.getDeclaredMethod("cloneOffer", String.class, String.class,
                String.class, boolean.class, double.class, String.class, String.class, String.class,
                Consumer.class, ErrorMessageHandler.class);
        clone.setAccessible(true);
        try {
            clone.invoke(service, "source", currencyCode, input, false, 0d, "", "payment", "info", null, null);
            throw new AssertionError("Clone did not construct an offer price");
        } catch (InvocationTargetException exception) {
            if (exception.getCause() == stopBeforePlacement)
                return constructedPrice.get().getValue();
            throw exception.getCause();
        }
    }

    @Test
    public void testApiCryptoPricePreservesEightDecimalsAndTrailingZeros() throws Throwable {
        assertEquals(112345678L, apiPriceValue("1.12345678", "ERG"));
        assertEquals(112345678L, apiPriceValue("1.123456780", "ERG"));
        assertEquals(1L, apiPriceValue("1e-8", "ERG"));
    }

    @Test
    public void testApiCryptoPriceRejectsNonzeroNinthDecimal() {
        for (String input : new String[]{"1.123456789", "0.000000001", "123456789e-9"}) {
            assertThrows(IllegalArgumentException.class, () -> apiPriceValue(input, "ERG"), input);
        }
    }

    @Test
    public void testApiCryptoPricePreservesLargeExactValues() throws Throwable {
        assertEquals(9007199254740993L, apiPriceValue("90071992.54740993", "ERG"));
        assertEquals(Long.MAX_VALUE, apiPriceValue("92233720368.54775807", "ERG"));
        assertEquals(Long.MIN_VALUE, apiPriceValue("-92233720368.54775808", "ERG"));
    }

    @Test
    public void testApiCryptoPriceRejectsOverflow() {
        for (String input : new String[]{"92233720368.54775808", "-92233720368.54775809", "1e20"}) {
            assertThrows(IllegalArgumentException.class, () -> apiPriceValue(input, "ERG"), input);
        }
    }

    @Test
    public void testApiTraditionalPriceKeepsRounding() throws Throwable {
        assertEquals(112345679L, apiPriceValue("1.123456789", "USD"));
        assertEquals(-112345679L, apiPriceValue("-1.123456789", "USD"));
    }

    @Test
    public void testApiPriceKeepsBigDecimalInputSyntax() {
        for (String currencyCode : new String[]{"ERG", "USD"}) {
            assertThrows(IllegalArgumentException.class, () -> apiPriceValue("1,23", currencyCode));
            assertThrows(IllegalArgumentException.class, () -> apiPriceValue(" 1.23", currencyCode));
        }
    }

    private long apiPriceValue(String input, String currencyCode) throws Throwable {
        // exercise the converter shared by API offer creation and editing
        CoreOffersService service = new CoreOffersService(null, null, null, null, null, null,
                null, null, null, null, null);
        Method converter = CoreOffersService.class.getDeclaredMethod("priceStringToLong", String.class, String.class);
        converter.setAccessible(true);
        try {
            return (long) converter.invoke(service, input, currencyCode);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @Test
    public void testCryptoEntryValidationRejectsExcessTradingPrecision() {
        Res.setup();
        AmountValidator8Decimals validator = new AmountValidator8Decimals();
        for (String input : new String[]{"1.123456789", "1,123456789", "123456789e-9"}) {
            assertFalse(validator.validate(input).isValid, input);
            assertEquals(Res.get("validation.crypto.tooManyDecimals"), validator.validate(input).errorMessage);
            assertThrows(IllegalArgumentException.class, () -> parse("ERG", input));
        }
    }

    @Test
    public void testCryptoEntryValidationMatchesExactNumericBoundsAndFormats() {
        Res.setup();
        AmountValidator8Decimals validator = new AmountValidator8Decimals();
        for (String input : new String[]{"1.12345678", "1,12345678", "1.123456780", "1e-8", "100000000"}) {
            assertTrue(validator.validate(input).isValid, input);
            assertTrue(parse("ERG", input).isPositive(), input);
        }
        for (String input : new String[]{"0", "-1", "0.000000001", "100000001", "100000000.00000001", "NaN", "Infinity"}) {
            assertFalse(validator.validate(input).isValid, input);
        }
    }

    @Test
    public void testErgoPaymentAmountPreservesTradingPrecision() {
        Price price = parse("ERG", "123.12345678");
        BigInteger oneXmr = new BigInteger("1000000000000");
        Volume volume = price.getVolumeByAmount(oneXmr);

        assertEquals(12312345678L, price.getValue());
        assertEquals("123.12345678", price.toPlainString());
        assertEquals("ERG", volume.getCurrencyCode());
        assertEquals(12312345678L, volume.getValue());
        assertEquals("123.12345678", VolumeUtil.formatVolume(volume));
        assertEquals("123.12345678 ERG", VolumeUtil.formatVolumeWithCode(volume));
        assertEquals(volume.getValue(), Volume.parse(VolumeUtil.formatVolume(volume), "ERG").getValue());
        assertEquals(oneXmr, price.getAmountByVolume(volume));

        Volume halfVolume = price.getVolumeByAmount(oneXmr.divide(BigInteger.TWO));
        assertEquals("61.56172839", halfVolume.toPlainString());
        assertEquals(oneXmr.divide(BigInteger.TWO), price.getAmountByVolume(halfVolume));
    }

    @Test
    public void testErgoTradingQuantumRejectsUnrepresentableNanoErg() {
        assertEquals(1L, parse("ERG", "0.00000001").getValue());
        assertEquals(1L, Volume.parse("0.00000001", "ERG").getValue());
        assertEquals("0.00000001 ERG", VolumeUtil.formatVolumeWithCode(Volume.parse("0.00000001", "ERG")));
        assertThrows(IllegalArgumentException.class, () -> parse("ERG", "0.000000001"));
        assertThrows(IllegalArgumentException.class, () -> Volume.parse("0.000000001", "ERG"));
        assertThrows(IllegalArgumentException.class, () -> Volume.parse("1.123456789", "ERG"));
    }

    @Test
    public void testParse() {
        assertEquals(
                "0.10 XMR/USD",
                parse("USD", "0.1").toFriendlyString(),
                "Fiat value should be formatted with two decimals."
        );

        assertEquals(
                "0.1234 XMR/EUR",
                parse("EUR", "0.1234").toFriendlyString(),
                "Fiat value should be given two decimals"
        );

        assertEquals(
                "0.1235 XMR/EUR",
                parse("EUR", "0.12345").toFriendlyString(),
                "Too many decimals of fiat value should get rounded up properly."
        );

        assertEquals(
                -100000000L,
                parse("LTC", "-1").getValue(),
                "Negative value should be parsed correctly."
        );

        assertEquals(
                "0.0001 XMR/USD",
                parse("USD", "0,0001").toFriendlyString(),
                "Comma (',') as decimal separator should be converted to period ('.')"
        );

        assertEquals(
                "10000.2346 XMR/LTC",
                parse("LTC", "10000,23456789").toFriendlyString(),
                "Too many decimals should get rounded up properly."
        );

        assertEquals(
                "10000.2345 XMR/LTC",
                parse("LTC", "10000,23454999").toFriendlyString(),
                "Too many decimals should get rounded down properly."
        );

        assertEquals(
                1000023456789L,
                parse("LTC", "10000,23456789").getValue(),
                "Underlying long value should be correct."
        );

        try {
            parse("XMR", "56789.123456789");
            fail("Expected IllegalArgumentException to be thrown when too many decimals are used.");
        } catch (IllegalArgumentException iae) {
            assertEquals(
                    "java.lang.ArithmeticException: Rounding necessary",
                    iae.getMessage(),
                    "Unexpected exception message."
            );
        }
    }
    @Test
    public void testValueOf() {
        assertEquals(
                "0.0001 XMR/USD",
                valueOf("USD", 10000).toFriendlyString(),
                "Fiat value should have four decimals."
        );

        assertEquals(
                "0.1234 XMR/EUR",
                valueOf("EUR", 12340000).toFriendlyString(),
                "Fiat value should be given two decimals"
        );

        assertEquals(
                -1L,
                valueOf("LTC", -1L).getValue(),
                "Negative value should be parsed correctly."
        );

        assertEquals(
                "10000.2346 XMR/LTC",
                valueOf("LTC", 1000023456789L).toFriendlyString(),
                "Too many decimals should get rounded up properly."
        );

        assertEquals(
                "10000.2345 XMR/LTC",
                valueOf("LTC", 1000023454999L).toFriendlyString(),
                "Too many decimals should get rounded down properly."
        );

        assertEquals(
                1000023456789L,
                valueOf("LTC", 1000023456789L).getValue(),
                "Underlying long value should be correct."
        );
    }
}
