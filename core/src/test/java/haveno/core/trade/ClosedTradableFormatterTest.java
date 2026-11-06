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
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.trade.protocol.TradePeer;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClosedTradableFormatterTest {

    private static final long AMOUNT = 1000;
    private static final long BUYER_DEPOSIT = 150;

    private final ClosedTradableFormatter formatter = new ClosedTradableFormatter(mock(ClosedTradableManager.class));

    @BeforeEach
    public void setUp() {
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testArbitratedStates() {
        assertEquals(Res.get("portfolio.closed.canceledByArbitration"), getState(DisputeResult.Winner.SELLER, BUYER_DEPOSIT, false));
        assertEquals(Res.get("portfolio.closed.canceledByArbitration"), getState(DisputeResult.Winner.SELLER, 0, false));
        assertEquals(Res.get("portfolio.closed.completedByArbitration"), getState(DisputeResult.Winner.BUYER, BUYER_DEPOSIT + AMOUNT, false));
        assertEquals(Res.get("portfolio.closed.ticketClosed"), getState(DisputeResult.Winner.BUYER, BUYER_DEPOSIT + AMOUNT - 1, false));
        assertEquals(Res.get("portfolio.closed.ticketClosed"), getState(DisputeResult.Winner.SELLER, BUYER_DEPOSIT + 1, false));
    }

    @Test
    public void testCompletedStates() {
        assertEquals(Res.get("portfolio.closed.completed"), getState(DisputeResult.Winner.SELLER, BUYER_DEPOSIT, true)); // seller confirmed payment
        assertEquals(Res.get("portfolio.closed.completed"), getState(null, 0, false)); // ticket closed without a ruling
        Trade trade = mockTrade(false);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.NO_DISPUTE);
        assertEquals(Res.get("portfolio.closed.completed"), formatter.getStateAsString(trade));
    }

    @Test
    public void testLatestRulingAmongTickets() {
        Trade trade = mockTrade(false);
        Dispute canceled = mockDispute(DisputeResult.Winner.SELLER, BUYER_DEPOSIT, 1);
        Dispute completed = mockDispute(DisputeResult.Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 2);
        Dispute noRuling = mock(Dispute.class);
        when(noRuling.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>());
        when(trade.getDisputes()).thenReturn(List.of(completed, canceled, noRuling));
        assertEquals(Res.get("portfolio.closed.completedByArbitration"), formatter.getStateAsString(trade));
    }

    private String getState(DisputeResult.Winner winner, long buyerPayoutAmount, boolean isPaymentReceived) {
        Trade trade = mockTrade(isPaymentReceived);
        Dispute dispute = mockDispute(winner, buyerPayoutAmount, 1);
        when(trade.getDisputes()).thenReturn(List.of(dispute));
        return formatter.getStateAsString(trade);
    }

    private static Dispute mockDispute(DisputeResult.Winner winner, long buyerPayoutAmount, long closeDate) {
        DisputeResult disputeResult = new DisputeResult("tradeId", 0);
        disputeResult.setWinner(winner);
        disputeResult.setBuyerPayoutAmountBeforeCost(BigInteger.valueOf(buyerPayoutAmount));
        disputeResult.setCloseDate(new Date(closeDate));
        Dispute dispute = mock(Dispute.class);
        when(dispute.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>(disputeResult));
        return dispute;
    }

    private static Trade mockTrade(boolean isPaymentReceived) {
        TradePeer buyer = new TradePeer();
        buyer.setSecurityDeposit(BigInteger.valueOf(BUYER_DEPOSIT));
        Trade trade = mock(Trade.class);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.DISPUTE_CLOSED);
        when(trade.isPayoutPublished()).thenReturn(true);
        when(trade.isPaymentReceived()).thenReturn(isPaymentReceived);
        when(trade.getBuyer()).thenReturn(buyer);
        when(trade.getAmount()).thenReturn(BigInteger.valueOf(AMOUNT));
        return trade;
    }
}
