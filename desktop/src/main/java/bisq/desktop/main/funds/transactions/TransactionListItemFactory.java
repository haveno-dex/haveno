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

package bisq.desktop.main.funds.transactions;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.user.Preferences;
import bisq.core.util.FormattingUtils;
import bisq.core.util.coin.CoinFormatter;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import javax.annotation.Nullable;



import monero.wallet.model.MoneroTxWallet;


@Singleton
public class TransactionListItemFactory {
    private final XmrWalletService xmrWalletService;
    private final CoinFormatter formatter;
    private final Preferences preferences;

    @Inject
    TransactionListItemFactory(XmrWalletService xmrWalletService,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                               Preferences preferences) {
        this.xmrWalletService = xmrWalletService;
        this.formatter = formatter;
        this.preferences = preferences;
    }

    TransactionsListItem create(MoneroTxWallet transaction, @Nullable TransactionAwareTradable tradable) {
        return new TransactionsListItem(transaction,
                xmrWalletService,
                tradable,
                formatter,
                preferences.getIgnoreDustThreshold());
    }
}
