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

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessPeerPublishedDelayedPayoutTxMessage extends TradeTask {
    public ProcessPeerPublishedDelayedPayoutTxMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("XMR adaptation does not support delayed payout tx");

//            PeerPublishedDelayedPayoutTxMessage message = (PeerPublishedDelayedPayoutTxMessage) processModel.getTradeMessage();
//            Validator.checkTradeId(processModel.getOfferId(), message);
//            checkNotNull(message);
//
//            // update to the latest peer address of our peer if the message is correct
//            trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());
//
//            // We add the tx to our wallet.
//            Transaction delayedPayoutTx = checkNotNull(trade.getDelayedPayoutTx());
//            WalletService.maybeAddSelfTxToWallet(delayedPayoutTx, processModel.getBtcWalletService().getWallet());
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
