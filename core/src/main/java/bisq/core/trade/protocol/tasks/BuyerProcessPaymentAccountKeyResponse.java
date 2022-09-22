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
import bisq.core.trade.messages.PaymentAccountKeyResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerProcessPaymentAccountKeyResponse extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public BuyerProcessPaymentAccountKeyResponse(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // update peer node address if not from arbitrator
          if (!processModel.getTempTradingPeerNodeAddress().equals(trade.getArbitratorNodeAddress())) {
              trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());
          }

          // decrypt peer's payment account payload
          PaymentAccountKeyResponse request = (PaymentAccountKeyResponse) processModel.getTradeMessage();
          if (trade.getTradingPeer().getPaymentAccountPayload() == null) {
              trade.decryptPeersPaymentAccountPayload(request.getPaymentAccountKey());
          }

          // store updated multisig hex for processing on payment sent
          if (request.getUpdatedMultisigHex() != null) trade.getTradingPeer().setUpdatedMultisigHex(request.getUpdatedMultisigHex());

          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
