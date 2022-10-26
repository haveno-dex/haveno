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
import bisq.core.trade.messages.DepositsConfirmedMessage;
import bisq.core.trade.protocol.TradingPeer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessDepositsConfirmedMessage extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessDepositsConfirmedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // get sender based on the pub key
          // TODO: trade.getTradingPeer(PubKeyRing)
          DepositsConfirmedMessage request = (DepositsConfirmedMessage) processModel.getTradeMessage();
          TradingPeer sender;
          if (trade.getArbitrator().getPubKeyRing().equals(request.getPubKeyRing())) sender = trade.getArbitrator();
          else if (trade.getBuyer().getPubKeyRing().equals(request.getPubKeyRing())) sender = trade.getBuyer();
          else if (trade.getSeller().getPubKeyRing().equals(request.getPubKeyRing())) sender = trade.getSeller();
          else throw new RuntimeException("Pub key ring is not from arbitrator, buyer, or seller");
          
          // update peer node address
          sender.setNodeAddress(processModel.getTempTradingPeerNodeAddress());

          // decrypt seller payment account payload if key given
          if (request.getSellerPaymentAccountKey() != null && trade.getTradingPeer().getPaymentAccountPayload() == null) {
              log.info(trade.getClass().getSimpleName() + " decryping using seller payment account key: " + request.getSellerPaymentAccountKey());
              trade.decryptPeerPaymentAccountPayload(request.getSellerPaymentAccountKey());
          }

          // store updated multisig hex for processing on payment sent
          sender.setUpdatedMultisigHex(request.getUpdatedMultisigHex());

          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
