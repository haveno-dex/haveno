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

package haveno.core.trade.protocol.tasks;

import haveno.core.trade.Trade;

import haveno.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.model.MoneroTxWallet;

@Slf4j
public abstract class BroadcastPayoutTx extends TradeTask {
    public BroadcastPayoutTx(TaskRunner<Trade> taskHandler, Trade trade) {
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
                    processModel.getProvider().getXmrWalletService().getWallet().relayTx(payoutTx);
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
