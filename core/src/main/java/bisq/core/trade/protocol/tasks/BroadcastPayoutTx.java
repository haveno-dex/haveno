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

package bisq.core.trade.protocol.tasks;

import static com.google.common.base.Preconditions.checkNotNull;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.exceptions.TxBroadcastException;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public abstract class BroadcastPayoutTx extends TradeTask {
    @SuppressWarnings({"unused"})
    public BroadcastPayoutTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    protected abstract void setState();

    @Override
    protected void run() {
        try {
            runInterceptHook();
            if (true) throw new RuntimeException("BroadcastPayoutTx not implemented for xmr");
            MoneroTxWallet payoutTx = trade.getPayoutTx();
            checkNotNull(payoutTx, "payoutTx must not be null");
            

            if (payoutTx.isRelayed()) {
                log.debug("payoutTx was already published");
                setState();
                complete();
            } else {
                try {
                    processModel.getXmrWalletService().getWallet().relayTx(payoutTx);
                    if (!completed) {
                        log.debug("BroadcastTx succeeded. Transaction:" + payoutTx);
                        setState();
                        complete();
                    } else {
                        log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
                    }
                } catch (Exception e) {
                    if (!completed) {
                        log.error("BroadcastTx failed. Error:" + e.getMessage());
                        failed(e);
                    } else {
                        log.warn("We got the onFailure callback called after the timeout has been triggered a complete().");
                    }
                }
            }
        } catch (Throwable t) {
            failed(t);
        }
    }
}
