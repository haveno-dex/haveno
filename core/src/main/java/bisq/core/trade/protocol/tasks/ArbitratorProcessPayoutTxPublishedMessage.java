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


import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProcessPayoutTxPublishedMessage extends TradeTask {

    @SuppressWarnings({"unused"})
    public ArbitratorProcessPayoutTxPublishedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          PayoutTxPublishedMessage request = (PayoutTxPublishedMessage) processModel.getTradeMessage();

          // verify and publish payout tx
          trade.verifyPayoutTx(request.getSignedPayoutTxHex(), false, true);

          // update latest peer address
          if (request.isMaker()) trade.setMakerNodeAddress(processModel.getTempTradingPeerNodeAddress());
          else trade.setTakerNodeAddress(processModel.getTempTradingPeerNodeAddress());

          // TODO: publish signed witness data?
          //request.getSignedWitness()

          // close arbitrator trade
          processModel.getTradeManager().onTradeCompleted(trade);
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
