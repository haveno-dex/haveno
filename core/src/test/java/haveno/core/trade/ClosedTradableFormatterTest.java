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

package haveno.core.trade;

import haveno.core.locale.Res;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClosedTradableFormatterTest {

    private static final long AMOUNT = 1000;

    private final ClosedTradableFormatter formatter = new ClosedTradableFormatter(mock(ClosedTradableManager.class));

    @BeforeEach
    public void setUp() {
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testTradeStates() {
        assertEquals(Res.get("portfolio.closed.completed"), getState(null));
        assertEquals(Res.get("portfolio.closed.canceledByArbitration"), getState(BigInteger.valueOf(-1)));
        assertEquals(Res.get("portfolio.closed.canceledByArbitration"), getState(BigInteger.ZERO));
        assertEquals(Res.get("portfolio.closed.ticketClosed"), getState(BigInteger.ONE));
        assertEquals(Res.get("portfolio.closed.ticketClosed"), getState(BigInteger.valueOf(AMOUNT - 1)));
        assertEquals(Res.get("portfolio.closed.completedByArbitration"), getState(BigInteger.valueOf(AMOUNT)));
    }

    private String getState(BigInteger arbitratedBuyerGain) {
        Trade trade = mock(Trade.class);
        when(trade.isPayoutPublished()).thenReturn(true);
        when(trade.getAmount()).thenReturn(BigInteger.valueOf(AMOUNT));
        when(trade.getArbitratedBuyerGain()).thenReturn(arbitratedBuyerGain);
        return formatter.getStateAsString(trade);
    }
}
