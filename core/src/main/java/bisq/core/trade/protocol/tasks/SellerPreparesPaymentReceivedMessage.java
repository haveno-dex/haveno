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

package bisq.core.trade.protocol.tasks;

import bisq.core.trade.Trade;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class SellerPreparesPaymentReceivedMessage extends TradeTask {

    @SuppressWarnings({"unused"})
    public SellerPreparesPaymentReceivedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // verify, sign, and publish payout tx if given. otherwise create payout tx
            if (trade.getBuyer().getPayoutTxHex() != null) {
                log.info("Seller verifying, signing, and publishing payout tx");
                trade.verifyPayoutTx(trade.getBuyer().getPayoutTxHex(), true, true);
            } else {
                log.info("Seller creating unsigned payout tx");
                MoneroTxWallet payoutTx = trade.createPayoutTx();
                System.out.println("created payout tx: " + payoutTx);
                trade.getSeller().setPayoutTx(payoutTx);
                trade.getSeller().setPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
            }

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
