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
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import monero.daemon.model.MoneroBlock;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DisplayedTransactionsTest {
    @Test
    public void testMemoEditsPersistAndReplaceCachedSnapshot() throws ReflectiveOperationException {
        MoneroWallet wallet = mock(MoneroWallet.class);
        MoneroBlock block = new MoneroBlock().setHeight(100L).setTimestamp(123456L);
        MoneroTxWallet original = new MoneroTxWallet().setHash("tx").setNote("old").setBlock(block);
        MoneroTxWallet unrelated = new MoneroTxWallet().setHash("other").setNote("unchanged");
        XmrWalletService service = memoWalletService(wallet, List.of(original, unrelated));
        var order = inOrder(wallet);

        for (String note : List.of("edited\nwith unicode: café", "")) {
            service.setTxNote("tx", note);

            order.verify(wallet).setTxNote("tx", note);
            order.verify(wallet).save();
            assertEquals(note.isEmpty() ? null : note, service.getTx("tx").getNote());
            assertSame(block, service.getTx("tx").getBlock());
            assertSame(unrelated, service.getTx("other"));
            assertEquals("old", original.getNote());
        }
    }

    @Test
    public void testMemoSaveFailureRestoresAndStoresPreviousNote() throws ReflectiveOperationException {
        for (String oldNote : List.of("old", "")) {
            MoneroWallet wallet = mock(MoneroWallet.class);
            AtomicReference<String> liveNote = new AtomicReference<>(oldNote);
            AtomicReference<String> storedNote = new AtomicReference<>(oldNote);
            when(wallet.getTxNote("tx")).thenAnswer(invocation -> liveNote.get());
            doAnswer(invocation -> {
                liveNote.set(invocation.getArgument(1));
                return null;
            }).when(wallet).setTxNote(eq("tx"), anyString());
            MoneroTxWallet original = new MoneroTxWallet().setHash("tx").setNote(oldNote.isEmpty() ? null : oldNote);
            XmrWalletService service = memoWalletService(wallet, List.of(original));
            String newNote = oldNote.isEmpty() ? "new" : "";
            IllegalStateException saveError = new IllegalStateException("response lost");
            doAnswer(invocation -> { // the store completes before its response is lost
                storedNote.set(liveNote.get());
                throw saveError;
            }).doAnswer(invocation -> {
                storedNote.set(liveNote.get());
                return null;
            }).when(wallet).save();

            assertSame(saveError, assertThrows(IllegalStateException.class, () -> service.setTxNote("tx", newNote)));

            assertEquals(oldNote, wallet.getTxNote("tx"));
            assertEquals(oldNote, storedNote.get());
            verify(wallet, times(2)).save();
            assertSame(original, service.getTx("tx"));
            assertEquals(oldNote.isEmpty() ? null : oldNote, original.getNote());
        }
    }

    @Test
    public void testMemoRollbackFailurePreservesSaveError() throws ReflectiveOperationException {
        for (String rollbackFailure : List.of("same error", "note", "save")) {
            MoneroWallet wallet = mock(MoneroWallet.class);
            when(wallet.getTxNote("tx")).thenReturn("old");
            MoneroTxWallet original = new MoneroTxWallet().setHash("tx").setNote("old");
            XmrWalletService service = memoWalletService(wallet, List.of(original));
            IllegalStateException saveError = new IllegalStateException("disk full");
            IllegalStateException rollbackError = rollbackFailure.equals("same error") ? saveError : new IllegalStateException("wallet unavailable");
            if (rollbackFailure.equals("save")) doThrow(saveError).doThrow(rollbackError).when(wallet).save();
            else {
                doThrow(saveError).when(wallet).save();
                doThrow(rollbackError).when(wallet).setTxNote("tx", "old");
            }

            assertSame(saveError, assertThrows(IllegalStateException.class, () -> service.setTxNote("tx", "new")));

            assertEquals(rollbackFailure.equals("same error") ? 0 : 1, saveError.getSuppressed().length);
            if (!rollbackFailure.equals("same error")) assertSame(rollbackError, saveError.getSuppressed()[0]);
            assertSame(original, service.getTx("tx"));
        }
    }

    private XmrWalletService memoWalletService(MoneroWallet wallet, List<MoneroTxWallet> txs) throws ReflectiveOperationException {
        XmrWalletService service = mock(XmrWalletService.class, CALLS_REAL_METHODS);
        var lockField = XmrWalletBase.class.getDeclaredField("walletLock");
        lockField.setAccessible(true);
        lockField.set(service, new Object());
        var walletField = XmrWalletBase.class.getDeclaredField("wallet");
        walletField.setAccessible(true);
        walletField.set(service, wallet);
        var cacheField = XmrWalletService.class.getDeclaredField("cachedTxs");
        cacheField.setAccessible(true);
        cacheField.set(service, txs);
        return service;
    }

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
