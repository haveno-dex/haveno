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

import haveno.core.trade.Tradable;
import haveno.core.xmr.wallet.XmrWalletService;
import monero.wallet.model.MoneroTxWallet;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class DisplayedTransactions extends ObservableListDecorator<TransactionsListItem> {
    private final XmrWalletService xmrWalletService;
    private final TradableRepository tradableRepository;
    private final TransactionListItemFactory transactionListItemFactory;
    private final TransactionAwareTradableFactory transactionAwareTradableFactory;

    DisplayedTransactions(XmrWalletService xmrWalletService, TradableRepository tradableRepository,
                          TransactionListItemFactory transactionListItemFactory,
                          TransactionAwareTradableFactory transactionAwareTradableFactory) {
        this.xmrWalletService = xmrWalletService;
        this.tradableRepository = tradableRepository;
        this.transactionListItemFactory = transactionListItemFactory;
        this.transactionAwareTradableFactory = transactionAwareTradableFactory;
    }

    void update() {
        List<TransactionsListItem> transactionsListItems = getTransactionListItems();
        // are sorted by getRecentTransactions
        forEach(TransactionsListItem::cleanup);
        setAll(transactionsListItems);
    }

    private List<TransactionsListItem> getTransactionListItems() {
        List<MoneroTxWallet> transactions = xmrWalletService.getTransactions(false);
        return transactions.stream()
                .map(this::convertTransactionToListItem)
                .collect(Collectors.toList());
    }

    private TransactionsListItem convertTransactionToListItem(MoneroTxWallet transaction) {
        Set<Tradable> tradables = tradableRepository.getAll();

        TransactionAwareTradable maybeTradable = tradables.stream()
                .map(transactionAwareTradableFactory::create)
                .filter(tradable -> tradable.isRelatedToTransaction(transaction))
                .findAny()
                .orElse(null);

        return transactionListItemFactory.create(transaction, maybeTradable);
    }
}
