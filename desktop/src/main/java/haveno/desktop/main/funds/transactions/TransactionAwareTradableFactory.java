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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.core.offer.OpenOffer;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.xmr.wallet.XmrWalletService;


@Singleton
public class TransactionAwareTradableFactory {
    private final ArbitrationManager arbitrationManager;
    private final RefundManager refundManager;
    private final XmrWalletService xmrWalletService;
    private final PubKeyRingProvider pubKeyRingProvider;

    @Inject
    TransactionAwareTradableFactory(ArbitrationManager arbitrationManager,
                                    RefundManager refundManager,
                                    XmrWalletService xmrWalletService,
                                    PubKeyRingProvider pubKeyRingProvider) {
        this.arbitrationManager = arbitrationManager;
        this.refundManager = refundManager;
        this.xmrWalletService = xmrWalletService;
        this.pubKeyRingProvider = pubKeyRingProvider;
    }

    TransactionAwareTradable create(Tradable delegate) {
        if (delegate instanceof OpenOffer) {
            return new TransactionAwareOpenOffer((OpenOffer) delegate);
        } else if (delegate instanceof Trade) {
            return new TransactionAwareTrade((Trade) delegate,
                    arbitrationManager,
                    refundManager,
                    xmrWalletService,
                    pubKeyRingProvider.get());
        } else {
            return new DummyTransactionAwareTradable(delegate);
        }
    }
}
