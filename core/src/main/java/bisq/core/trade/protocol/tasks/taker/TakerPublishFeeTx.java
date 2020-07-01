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

package bisq.core.trade.protocol.tasks.taker;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class TakerPublishFeeTx extends TradeTask {
    @SuppressWarnings({"unused"})
    public TakerPublishFeeTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            MoneroTxWallet takeOfferFeeTx = processModel.getTakeOfferFeeTx();

            if (trade.isCurrencyForTakerFeeBtc()) {
                // We committed to be sure the tx gets into the wallet even in the broadcast process it would be
                // committed as well, but if user would close app before success handler returns the commit would not
                // be done.
                try {
                    processModel.getXmrWalletService().getWallet().relayTx(takeOfferFeeTx);
                    trade.setState(Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX);
                    complete();
                } catch (Exception exception) {
                  failed(exception);
                }
            } else {
                throw new RuntimeException("BSQ wallet not supported in xmr integration");
            }
        } catch (Throwable t) {
            failed(t);
        }
    }
}
