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

package haveno.core.trade.protocol.tasks.maker;

import haveno.core.trade.Trade;
import haveno.core.trade.protocol.tasks.TradeTask;

import haveno.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MakerVerifyTakerFeePayment extends TradeTask {

    public MakerVerifyTakerFeePayment(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            //TODO missing impl.
            // int numOfPeersSeenTx = processModel.getWalletService().getNumOfPeersSeenTx(processModel.getTakeOfferFeeTxId());
       /* if (numOfPeersSeenTx > 2) {
            resultHandler.handleResult();
        }*/

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
