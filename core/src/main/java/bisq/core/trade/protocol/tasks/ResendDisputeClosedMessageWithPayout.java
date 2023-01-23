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
import bisq.core.support.dispute.Dispute;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositsConfirmedMessage;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.util.Validator;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

@Slf4j
public class ResendDisputeClosedMessageWithPayout extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ResendDisputeClosedMessageWithPayout(TaskRunner taskHandler, Trade trade) {
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
            TradingPeer sender = trade.getTradingPeer(request.getPubKeyRing());
            if (sender == null) throw new RuntimeException("Pub key ring is not from arbitrator, buyer, or seller");
              
            // arbitrator resends DisputeClosedMessage with payout tx when updated multisig info received
            boolean ticketClosed = false;
            if (!trade.isPayoutPublished() && trade.isArbitrator()) {
                List<Dispute> disputes = trade.getDisputes();
                for (Dispute dispute : disputes) {
                    if (!dispute.isClosed()) continue; // dispute must be closed
                    if (sender.getPubKeyRing().equals(dispute.getTraderPubKeyRing())) {
                        HavenoUtils.arbitrationManager.closeDisputeTicket(dispute.getDisputeResultProperty().get(), dispute, dispute.getDisputeResultProperty().get().summaryNotesProperty().get(), null, () -> {
                            completeAux();
                        }, (errMessage, err) -> {
                            err.printStackTrace();
                            failed(err);
                        });
                        ticketClosed = true;
                        break;
                    }
                }
            }

            // complete if not waiting for result
            if (!ticketClosed) completeAux();
          } catch (Throwable t) {
              failed(t);
          }
    }

    private void completeAux() {
        processModel.getTradeManager().requestPersistence();
        complete();
    }
}
