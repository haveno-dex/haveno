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
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import lombok.extern.slf4j.Slf4j;

import static bisq.core.util.Validator.checkTradeId;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class TakerProcessesMakerDepositTxMessage extends TradeTask {
    @SuppressWarnings({"unused"})
    public TakerProcessesMakerDepositTxMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            DepositTxMessage message = (DepositTxMessage) processModel.getTradeMessage();
            checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);

            // TODO (woodser): verify that deposit amount + tx fee = security deposit + trade fee (+ trade amount), or require exact security deposit to multisig?
            processModel.setMakerPreparedDepositTxId(message.getDepositTxId());
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
