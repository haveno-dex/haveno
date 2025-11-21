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
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.mailbox.MailboxMessage;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class SellerSendPaymentReceivedMessageToBuyer extends SellerSendPaymentReceivedMessage {

    public SellerSendPaymentReceivedMessageToBuyer(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected TradePeer getReceiver() {
        return trade.getBuyer();
    }

    @Override
    protected void setStateSent() {
        if (trade.getState().equals(Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG)) {
            trade.setState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG);
        } else {
            trade.advanceState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG); // do not revert previous send progress
        }
        super.setStateSent();
    }

    @Override
    protected void setStateFault() {
        trade.setStateIfValidTransitionTo(Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG);
        super.setStateFault();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setStateIfValidTransitionTo(Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG);
        super.setStateStoredInMailbox();
    }

    @Override
    protected void setStateArrived() {
        trade.setStateIfValidTransitionTo(Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG);
        super.setStateArrived();
    }

    // continue execution on fault so payment received message is sent to arbitrator
    @Override
    protected void onFault(String errorMessage, MailboxMessage message) {
        setStateFault();
        appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
        complete();
    }
}
