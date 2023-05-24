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

import haveno.core.offer.OpenOffer;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import monero.wallet.model.MoneroTxWallet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

public class TransactionAwareTradableFactoryTest {
    @Test
    public void testCreateWhenNotOpenOfferOrTrade() {
        ArbitrationManager arbitrationManager = mock(ArbitrationManager.class);

        TransactionAwareTradableFactory factory = new TransactionAwareTradableFactory(arbitrationManager,
                null, null, null);

        Tradable delegate = mock(Tradable.class);
        assertFalse(delegate instanceof OpenOffer);
        assertFalse(delegate instanceof Trade);

        TransactionAwareTradable tradable = factory.create(delegate);

        assertFalse(tradable.isRelatedToTransaction(mock(MoneroTxWallet.class)));
    }
}
