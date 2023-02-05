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
import bisq.core.trade.protocol.TradePeer;
import bisq.core.util.Validator;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

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

            // get peer
            DepositsConfirmedMessage request = (DepositsConfirmedMessage) processModel.getTradeMessage();
            checkNotNull(request);
            Validator.checkTradeId(processModel.getOfferId(), request);
            TradePeer sender = trade.getTradePeer(request.getPubKeyRing());
            if (sender == null) throw new RuntimeException("Pub key ring is not from arbitrator, buyer, or seller");
              
            // update peer node address
            sender.setNodeAddress(processModel.getTempTradePeerNodeAddress());
            if (sender.getNodeAddress().equals(trade.getBuyer().getNodeAddress()) && sender != trade.getBuyer()) trade.getBuyer().setNodeAddress(null); // tests can reuse addresses
            if (sender.getNodeAddress().equals(trade.getSeller().getNodeAddress()) && sender != trade.getSeller()) trade.getSeller().setNodeAddress(null);
            if (sender.getNodeAddress().equals(trade.getArbitrator().getNodeAddress()) && sender != trade.getArbitrator()) trade.getArbitrator().setNodeAddress(null);

            // store updated multisig hex for processing on payment sent
            sender.setUpdatedMultisigHex(request.getUpdatedMultisigHex());

            // decrypt seller payment account payload if key given
            if (request.getSellerPaymentAccountKey() != null && trade.getTradePeer().getPaymentAccountPayload() == null) {
                log.info(trade.getClass().getSimpleName() + " decrypting using seller payment account key");
                trade.decryptPeerPaymentAccountPayload(request.getSellerPaymentAccountKey());
            }

            // persist and complete
            processModel.getTradeManager().requestPersistence();
            complete();
          } catch (Throwable t) {
              failed(t);
          }
    }
}
