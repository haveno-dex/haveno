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

import haveno.core.offer.Offer;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.trade.protocol.TradePeer;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TradeTest {

    private static final long AMOUNT = 1000;
    private static final long BUYER_DEPOSIT = 150;

    @Test
    public void testArbitratedBuyerGain() {
        assertEquals(BigInteger.valueOf(AMOUNT), mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 1)).getArbitratedBuyerGain());
        assertEquals(BigInteger.ZERO, mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).getArbitratedBuyerGain());
        assertNull(mockTrade(true, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).getArbitratedBuyerGain()); // seller confirmed payment
        assertNull(mockTrade(false, mockDispute(null, 0, 1)).getArbitratedBuyerGain()); // ticket closed without a ruling
        assertNull(mockTrade(false).getArbitratedBuyerGain());
    }

    @Test
    public void testLatestRulingAmongTickets() {
        Dispute noRuling = mock(Dispute.class);
        when(noRuling.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>());
        Trade trade = mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 2), mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1), noRuling);
        assertEquals(BigInteger.valueOf(AMOUNT), trade.getArbitratedBuyerGain());
        trade = mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1), mockDispute(null, 0, 2)); // later ticket closed without a ruling
        assertEquals(BigInteger.ZERO, trade.getArbitratedBuyerGain());
    }

    @Test
    public void testShouldPublishTradeStatistics() {
        assertTrue(mockTrade(true).shouldPublishTradeStatistics()); // seller confirmed payment
        assertTrue(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT - 1, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT, 1)).shouldPublishTradeStatistics()); // buyer gets all without a seller deposit
        assertFalse(mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false).shouldPublishTradeStatistics());
    }

    private static Trade mockTrade(boolean isPaymentReceived, Dispute... disputes) {
        TradePeer buyer = new TradePeer();
        buyer.setSecurityDeposit(BigInteger.valueOf(BUYER_DEPOSIT));
        Offer offer = mock(Offer.class, RETURNS_DEEP_STUBS);
        Trade trade = mock(Trade.class);
        when(trade.isPayoutPublished()).thenReturn(true);
        when(trade.isPaymentReceived()).thenReturn(isPaymentReceived);
        when(trade.isSeller()).thenReturn(true);
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getBuyer()).thenReturn(buyer);
        when(trade.getAmount()).thenReturn(BigInteger.valueOf(AMOUNT));
        when(trade.getDisputes()).thenReturn(List.of(disputes));
        when(trade.getArbitratedBuyerGain()).thenCallRealMethod();
        when(trade.shouldPublishTradeStatistics()).thenCallRealMethod();
        return trade;
    }

    private static Dispute mockDispute(Winner winner, long buyerPayoutAmount, long closeDate) {
        DisputeResult disputeResult = new DisputeResult("tradeId", 0);
        disputeResult.setWinner(winner);
        disputeResult.setBuyerPayoutAmountBeforeCost(BigInteger.valueOf(buyerPayoutAmount));
        disputeResult.setCloseDate(new Date(closeDate));
        Dispute dispute = mock(Dispute.class);
        when(dispute.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>(disputeResult));
        return dispute;
    }
}
