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

package bisq.desktop.main.funds.transactions;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OpenOffer;
import bisq.core.support.dispute.arbitration.ArbitrationManager;
import bisq.core.support.dispute.refund.RefundManager;
import bisq.core.trade.Tradable;
import bisq.core.trade.Trade;

import bisq.common.crypto.PubKeyRingProvider;

import javax.inject.Inject;
import javax.inject.Singleton;


@Singleton
public class TransactionAwareTradableFactory {
    private final ArbitrationManager arbitrationManager;
    private final RefundManager refundManager;
    private final XmrWalletService xmrWalletService;
    private final PubKeyRingProvider pubKeyRing;

    @Inject
    TransactionAwareTradableFactory(ArbitrationManager arbitrationManager,
                                    RefundManager refundManager,
                                    XmrWalletService xmrWalletService,
                                    PubKeyRingProvider pubKeyRing) {
        this.arbitrationManager = arbitrationManager;
        this.refundManager = refundManager;
        this.xmrWalletService = xmrWalletService;
        this.pubKeyRing = pubKeyRing;
    }

    TransactionAwareTradable create(Tradable delegate) {
        if (delegate instanceof OpenOffer) {
            return new TransactionAwareOpenOffer((OpenOffer) delegate);
        } else if (delegate instanceof Trade) {
            return new TransactionAwareTrade((Trade) delegate,
                    arbitrationManager,
                    refundManager,
                    xmrWalletService,
                    pubKeyRing.get());
        } else {
            return new DummyTransactionAwareTradable(delegate);
        }
    }
}
