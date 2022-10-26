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

import bisq.core.network.MessageState;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.common.Timer;
import bisq.common.taskrunner.TaskRunner;

import javafx.beans.value.ChangeListener;

import lombok.extern.slf4j.Slf4j;

/**
 * We send the seller the BuyerSendPaymentSentMessage.
 * We wait to receive a ACK message back and resend the message
 * in case that does not happen in 10 minutes or if the message was stored in mailbox or failed. We keep repeating that
 * with doubling the interval each time and until the MAX_RESEND_ATTEMPTS is reached.
 * If never successful we give up and complete. It might be a valid case that the peer was not online for an extended
 * time but we can be very sure that our message was stored as mailbox message in the network and one the peer goes
 * online he will process it.
 */
@Slf4j
public class BuyerSendPaymentSentMessage extends SendMailboxMessageTask {
    private PaymentSentMessage message;
    private ChangeListener<MessageState> listener;
    private Timer timer;

    public BuyerSendPaymentSentMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (message == null) {

            // We do not use a real unique ID here as we want to be able to re-send the exact same message in case the
            // peer does not respond with an ACK msg in a certain time interval. To avoid that we get dangling mailbox
            // messages where only the one which gets processed by the peer would be removed we use the same uid. All
            // other data stays the same when we re-send the message at any time later.
            String deterministicId = tradeId + processModel.getMyNodeAddress().getFullAddress();
            message = new PaymentSentMessage(
                    tradeId,
                    processModel.getMyNodeAddress(),
                    trade.getCounterCurrencyTxId(),
                    trade.getCounterCurrencyExtraData(),
                    deterministicId,
                    trade.getPayoutTxHex(),
                    trade.getSelf().getUpdatedMultisigHex(),
                    trade.getSelf().getPaymentAccountKey()
            );
        }
        return message;
    }

    @Override
    protected void setStateSent() {
        if (trade.getState().ordinal() < Trade.State.BUYER_SENT_PAYMENT_SENT_MSG.ordinal()) {
            trade.setStateIfValidTransitionTo(Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
        }
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.setStateIfValidTransitionTo(Trade.State.BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG);
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setStateIfValidTransitionTo(Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG);
        processModel.getTradeManager().requestPersistence();
        // TODO: schedule repeat sending like bisq?
    }

    @Override
    protected void setStateFault() {
        trade.setStateIfValidTransitionTo(Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            super.run();
        } catch (Throwable t) {
            failed(t);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        if (timer != null) {
            timer.stop();
        }
        if (listener != null) {
            processModel.getPaymentStartedMessageStateProperty().removeListener(listener);
        }
    }
}
