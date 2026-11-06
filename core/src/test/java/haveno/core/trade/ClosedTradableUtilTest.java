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

import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClosedTradableUtilTest {

    private static final long AMOUNT = 1000;
    private static final long VOLUME = 500;

    @Test
    public void testTotalsExcludeTradesCanceledByArbitration() {
        List<Tradable> trades = List.of(mockTrade(null), mockTrade(BigInteger.ONE), mockTrade(BigInteger.ZERO));
        assertEquals(BigInteger.valueOf(2 * AMOUNT), ClosedTradableUtil.getTotalAmount(trades));
        assertEquals(Map.of("USD", 2 * VOLUME), ClosedTradableUtil.getTotalVolumeByCurrency(trades));
    }

    private static Trade mockTrade(BigInteger arbitratedBuyerGain) {
        Trade trade = mock(Trade.class);
        when(trade.isCompleted()).thenReturn(true);
        when(trade.getArbitratedBuyerGain()).thenReturn(arbitratedBuyerGain);
        when(trade.getOptionalAmount()).thenReturn(Optional.of(BigInteger.valueOf(AMOUNT)));
        when(trade.getOptionalVolume()).thenReturn(Optional.of(new Volume(TraditionalMoney.valueOf("USD", VOLUME))));
        return trade;
    }
}
