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


import haveno.common.ThreadUtils;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.DepositsConfirmedMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.Validator;
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

            // decrypt seller payment account payload if key given
            if (request.getSellerPaymentAccountKey() != null && trade.getTradePeer().getPaymentAccountPayload() == null) {
                log.info(trade.getClass().getSimpleName() + " decrypting using seller payment account key");
                trade.decryptPeerPaymentAccountPayload(request.getSellerPaymentAccountKey());
            }

            // update multisig hex
            sender.setUpdatedMultisigHex(request.getUpdatedMultisigHex());

            log.warn("Keys on ProcessDepositsConfirmedMessage for {} {}: public spend key={}, public view key={}, address={}", trade.getClass().getSimpleName(), trade.getShortId(), trade.getWallet().getPublicSpendKey(), trade.getWallet().getPublicViewKey(), trade.getWallet().getPrimaryAddress());

            // try to import multisig hex (retry later)
            ThreadUtils.submitToPool(() -> {
                try {
                    trade.importMultisigHex();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // persist
            processModel.getTradeManager().requestPersistence();
            complete();
          } catch (Throwable t) {
              failed(t);
          }
    }
}
