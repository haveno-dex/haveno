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

package haveno.desktop.main.funds.transactions;

import com.google.common.collect.Lists;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.FXCollections;
import monero.wallet.model.MoneroTxWallet;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(walletService.getTransactions(false)).thenReturn(transactions);

        TransactionListItemFactory transactionListItemFactory = mock(TransactionListItemFactory.class,
                RETURNS_DEEP_STUBS);

        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        DisplayedTransactions testedEntity = new DisplayedTransactions(
                walletService,
                mock(TradableRepository.class),
                transactionListItemFactory,
                mock(TransactionAwareTradableFactory.class));

        testedEntity.update();

        assertEquals(transactions.size(), testedEntity.size());
    }

    @Test
    public void testUpdateWhenRepositoryIsEmpty() {
        XmrWalletService walletService = mock(XmrWalletService.class);
        when(walletService.getTransactions(false))
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
}
