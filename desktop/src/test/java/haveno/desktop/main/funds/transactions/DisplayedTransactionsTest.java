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

package haveno.desktop.main.funds.transactions;

import com.google.common.collect.Lists;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import monero.wallet.model.MoneroTxWallet;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DisplayedTransactionsTest {
    @Test
    public void testUpdate() {
        List<MoneroTxWallet> transactions = Lists.newArrayList(mock(MoneroTxWallet.class), mock(MoneroTxWallet.class));

        XmrWalletService walletService = mock(XmrWalletService.class);
        when(walletService.getTxs(false)).thenReturn(transactions);

        TransactionListItemFactory transactionListItemFactory = mock(TransactionListItemFactory.class,
                RETURNS_DEEP_STUBS);
        TradableRepository tradableRepository = mock(TradableRepository.class);
        Tradable tradable = mock(Tradable.class);
        when(tradableRepository.getAll()).thenReturn(Set.of(tradable));
        TransactionAwareTradableFactory transactionAwareTradableFactory = mock(TransactionAwareTradableFactory.class);
        when(transactionAwareTradableFactory.create(tradable)).thenReturn(mock(TransactionAwareTradable.class));

        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        DisplayedTransactions testedEntity = new DisplayedTransactions(
                walletService,
                tradableRepository,
                transactionListItemFactory,
                transactionAwareTradableFactory);

        testedEntity.update();

        assertEquals(transactions.size(), testedEntity.size());
        verify(tradableRepository).getAll();
        verify(transactionAwareTradableFactory).create(tradable);
    }

    @Test
    public void testUpdateWhenRepositoryIsEmpty() {
        XmrWalletService walletService = mock(XmrWalletService.class);
        when(walletService.getTxs(false))
                .thenReturn(Collections.singletonList(mock(MoneroTxWallet.class)));

        TradableRepository tradableRepository = mock(TradableRepository.class);
        when(tradableRepository.getAll()).thenReturn(FXCollections.emptyObservableSet());

        TransactionListItemFactory transactionListItemFactory = mock(TransactionListItemFactory.class);

        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        DisplayedTransactions testedEntity = new DisplayedTransactions(
                walletService,
                tradableRepository,
                transactionListItemFactory,
                mock(TransactionAwareTradableFactory.class));

        testedEntity.update();

        assertEquals(1, testedEntity.size());
        verify(transactionListItemFactory).create(any(), nullable(TransactionAwareTradable.class));
    }

    @Test
    public void testRepositorySnapshotsTradablesWithFailedTradesLocked() {
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        TradeManager tradeManager = mock(TradeManager.class);
        ClosedTradableManager closedTradableManager = mock(ClosedTradableManager.class);
        FailedTradesManager failedTradesManager = mock(FailedTradesManager.class);
        OpenOffer offer = mock(OpenOffer.class);
        Trade openTrade = mock(Trade.class);
        Tradable closedTradable = mock(Tradable.class);
        Trade failedTrade = mock(Trade.class);
        when(openOfferManager.getOpenOffers()).thenReturn(List.of(offer));
        when(tradeManager.getOpenTrades()).thenReturn(List.of(openTrade));
        when(closedTradableManager.getTradableList()).thenReturn(List.of(closedTradable));
        @SuppressWarnings("unchecked")
        ObservableList<Trade> failedTrades = mock(ObservableList.class);
        when(failedTradesManager.getObservableList()).thenReturn(failedTrades);
        when(failedTrades.iterator()).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(failedTrades));
            return List.of(failedTrade).iterator();
        });

        TradableRepository repository = new TradableRepository(openOfferManager, tradeManager,
                closedTradableManager, failedTradesManager);

        assertEquals(Set.of(offer, openTrade, closedTradable, failedTrade), repository.getAll());
    }

    @Test
    public void testDisputedPayoutMatchingLocksDisputes() {
        Trade trade = mock(Trade.class, RETURNS_DEEP_STUBS);
        when(trade.getId()).thenReturn("trade");
        ArbitrationManager arbitrationManager = mock(ArbitrationManager.class);
        when(arbitrationManager.getDisputedTradeIds()).thenReturn(Set.of("trade"));
        Dispute dispute = mock(Dispute.class);
        when(dispute.getTradeId()).thenReturn("trade");
        when(dispute.getDisputePayoutTxId()).thenReturn("payout");
        @SuppressWarnings("unchecked")
        ObservableList<Dispute> disputes = mock(ObservableList.class);
        when(arbitrationManager.getDisputesAsObservableList()).thenReturn(disputes);
        when(disputes.stream()).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(disputes));
            return Stream.of(dispute);
        });
        MoneroTxWallet transaction = mock(MoneroTxWallet.class);
        when(transaction.getHash()).thenReturn("payout");
        TransactionAwareTrade transactionAwareTrade = new TransactionAwareTrade(trade, arbitrationManager,
                null, null, null);

        assertTrue(transactionAwareTrade.isRelatedToTransaction(transaction));
    }
}
