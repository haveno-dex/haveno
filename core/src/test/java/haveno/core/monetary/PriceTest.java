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

import org.junit.jupiter.api.Test;

import static haveno.core.monetary.Price.parse;
import static haveno.core.monetary.Price.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class PriceTest {

    @Test
    public void testParse() {
        Price result = parse("USD", "0.1");
        assertEquals(
                "0.10 XMR/USD",
                result.toFriendlyString(),
                "Fiat value should be formatted with two decimals."
        );

        result = parse("EUR", "0.1234");
        assertEquals(
                "0.1234 XMR/EUR",
                result.toFriendlyString(),
                "Fiat value should be given two decimals"
        );

        try {
            parse("EUR", "0.12345");
            fail("Expected IllegalArgumentException to be thrown when too many decimals are used.");
        } catch (IllegalArgumentException iae) {
            assertEquals(
                    "java.lang.ArithmeticException: Rounding necessary",
                    iae.getMessage(),
                    "Unexpected exception message."
            );
        }

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
                "10000.2346 LTC/XMR",
                parse("LTC", "10000,23456789").toFriendlyString(),
                "Too many decimals should get rounded up properly."
        );

        assertEquals(
                "10000.2345 LTC/XMR",
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
        Price result = valueOf("USD", 1);
        assertEquals(
                "0.0001 XMR/USD",
                result.toFriendlyString(),
                "Fiat value should have four decimals."
        );

        result = valueOf("EUR", 1234);
        assertEquals(
                "0.1234 XMR/EUR",
                result.toFriendlyString(),
                "Fiat value should be given two decimals"
        );

        assertEquals(
                -1L,
                valueOf("LTC", -1L).getValue(),
                "Negative value should be parsed correctly."
        );

        assertEquals(
                "10000.2346 LTC/XMR",
                valueOf("LTC", 1000023456789L).toFriendlyString(),
                "Too many decimals should get rounded up properly."
        );

        assertEquals(
                "10000.2345 LTC/XMR",
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
