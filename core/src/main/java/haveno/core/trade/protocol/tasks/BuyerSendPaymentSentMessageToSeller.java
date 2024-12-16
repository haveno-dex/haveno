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
import haveno.core.network.MessageState;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.TradePeer;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class BuyerSendPaymentSentMessageToSeller extends BuyerSendPaymentSentMessage {

    public BuyerSendPaymentSentMessageToSeller(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected TradePeer getReceiver() {
        return trade.getSeller();
    }
    
    @Override
    protected void setStateSent() {
        trade.getProcessModel().setPaymentSentMessageState(MessageState.SENT);
        super.setStateSent();
    }

    @Override
    protected void setStateArrived() {
        trade.getProcessModel().setPaymentSentMessageState(MessageState.ARRIVED);
        super.setStateArrived();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.getProcessModel().setPaymentSentMessageState(MessageState.STORED_IN_MAILBOX);
        super.setStateStoredInMailbox();
    }

    @Override
    protected void setStateFault() {
        trade.getProcessModel().setPaymentSentMessageState(MessageState.FAILED);
        super.setStateFault();
    }

    // continue execution on fault so payment sent message is sent to arbitrator
    @Override
    protected void onFault(String errorMessage, TradeMessage message) {
        setStateFault();
        appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
        complete();
    }

    @Override
    protected boolean isAckedByReceiver() {
        return trade.getState().ordinal() >= Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG.ordinal();
    }
}
