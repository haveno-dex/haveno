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

import haveno.core.trade.statistics.TradeStatistics3;
import haveno.network.p2p.storage.payload.InvalidPersistableNetworkPayloadException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static haveno.core.monetary.Price.parse;
import static haveno.core.monetary.Price.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PriceTest {

    @Test
    public void validatesTradeStatisticsBeforeAcceptingNetworkData() {
        long oldDate = 1700000000000L;
        TradeStatistics3 historical = new TradeStatistics3("USD", 10000000000L, 1000000000000L, "SEPA", oldDate, "abcd", Map.of());
        assertDoesNotThrow(() -> TradeStatistics3.fromProto(historical.toProtoTradeStatistics3()));
        for (TradeStatistics3 invalid : new TradeStatistics3[]{
                new TradeStatistics3("USD", 10000000000L, 1000000000000L, "SEPA", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2), "abcd", Map.of()),
                new TradeStatistics3("USD", Long.MAX_VALUE, Long.MAX_VALUE, "SEPA", oldDate, "abcd", Map.of()),
                new TradeStatistics3("USD", 0, 1000000000000L, "SEPA", oldDate, "abcd", Map.of()),
                new TradeStatistics3("USD", 10000000000L, 1000000000000L, "SEPA", oldDate, "abcd", Map.of("x", "x".repeat(1025)))}) {
            assertThrows(InvalidPersistableNetworkPayloadException.class, () -> TradeStatistics3.fromProto(invalid.toProtoTradeStatistics3()));
        }
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
