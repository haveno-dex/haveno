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


import haveno.common.taskrunner.TaskRunner;
import haveno.core.support.dispute.Dispute;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.DepositsConfirmedMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.Validator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class MaybeResendDisputeClosedMessageWithPayout extends TradeTask {

    @SuppressWarnings({"unused"})
    public MaybeResendDisputeClosedMessageWithPayout(TaskRunner taskHandler, Trade trade) {
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

            // arbitrator resends DisputeClosedMessage with payout tx when updated multisig info received
            boolean ticketClosed = false;
            if (!trade.isPayoutPublished() && trade.isArbitrator()) {
                List<Dispute> disputes = trade.getDisputes();
                for (Dispute dispute : disputes) {
                    if (!dispute.isClosed()) continue; // dispute must be closed
                    if (sender.getPubKeyRing().equals(dispute.getTraderPubKeyRing())) {
                        log.info("Arbitrator resending DisputeClosedMessage for trade {} after receiving updated multisig hex", trade.getId());
                        HavenoUtils.arbitrationManager.closeDisputeTicket(dispute.getDisputeResultProperty().get(), dispute, dispute.getDisputeResultProperty().get().summaryNotesProperty().get(), () -> {
                            completeAux();
                        }, (errMessage, err) -> {
                            log.error("Failed to close dispute ticket for trade {}: {}\n", trade.getId(), errMessage, err);
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
