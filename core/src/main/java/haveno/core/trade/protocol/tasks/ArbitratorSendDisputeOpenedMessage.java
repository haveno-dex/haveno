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

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.network.MessageState;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.network.p2p.mailbox.MailboxMessage;
import javafx.beans.value.ChangeListener;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * Arbitrator sends the DisputeOpenedMessage.
 * We wait to receive a ACK message back and resend the message
 * in case that does not happen in 10 minutes or if the message was stored in mailbox or failed. We keep repeating that
 * with doubling the interval each time and until the MAX_RESEND_ATTEMPTS is reached.
 * If never successful we give up and complete. It might be a valid case that the peer was not online for an extended
 * time but we can be very sure that our message was stored as mailbox message in the network and one the peer goes
 * online he will process it.
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
public abstract class ArbitratorSendDisputeOpenedMessage extends SendMailboxMessageTask {
    private ChangeListener<MessageState> listener;
    private Timer timer;
    private static final int MAX_RESEND_ATTEMPTS = 20;
    private int delayInMin = 10;
    private int resendCounter = 0;

    public ArbitratorSendDisputeOpenedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if not applicable or already acked
            if (getReceiver().getDisputeOpenedMessage() == null || isAckedByReceiver()) {
                if (!isCompleted()) complete();
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    protected Optional<Dispute> getDispute() {
        return HavenoUtils.arbitrationManager.findDispute(getReceiver().getDisputeOpenedMessage().getDispute());
    }

    protected ChatMessage getSystemChatMessage() {
        return getDispute().get().getChatMessages().get(0);
    }

    @Override
    protected MailboxMessage getMailboxMessage(String tradeId) {
        return getReceiver().getDisputeOpenedMessage();
    }

    @Override
    protected void setStateSent() {
        getReceiver().setDisputeOpenedMessageState(MessageState.SENT);
        tryToSendAgainLater();
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        getReceiver().setDisputeOpenedMessageState(MessageState.ARRIVED);
        getSystemChatMessage().setArrived(true);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        getReceiver().setDisputeOpenedMessageState(MessageState.STORED_IN_MAILBOX);
        getSystemChatMessage().setStoredInMailbox(true);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        getReceiver().setDisputeOpenedMessageState(MessageState.FAILED);
        getSystemChatMessage().setSendMessageError(errorMessage);
        processModel.getTradeManager().requestPersistence();
    }

    private void cleanup() {
        if (timer != null) {
            timer.stop();
        }
        if (listener != null) {
            getReceiver().getDisputeOpenedMessageStateProperty().removeListener(listener);
        }
    }

    private void tryToSendAgainLater() {

        // skip if already acked
        if (isAckedByReceiver()) return;

        if (resendCounter >= MAX_RESEND_ATTEMPTS) {
            cleanup();
            log.warn("We never received an ACK message when sending the DisputeOpenedMessage to the peer. We stop trying to send the message.");
            return;
        }

        if (timer != null) {
            timer.stop();
        }

        timer = UserThread.runAfter(this::run, delayInMin, TimeUnit.MINUTES);

        if (resendCounter == 0) {
            listener = (observable, oldValue, newValue) -> onMessageStateChange(newValue);
            getReceiver().getDisputeOpenedMessageStateProperty().addListener(listener);
            onMessageStateChange(getReceiver().getDisputeOpenedMessageStateProperty().get());
        }

        // first re-send is after 2 minutes, then increase the delay exponentially
        if (resendCounter == 0) {
            int shortDelay = 2;
            log.info("We will send the DisputeOpenedMessage again to the peer after a delay of {} min.", shortDelay);
            timer = UserThread.runAfter(this::run, shortDelay, TimeUnit.MINUTES);
        } else {
            log.info("We will send the DisputeOpenedMessage again to the peer after a delay of {} min.", delayInMin);
            timer = UserThread.runAfter(this::run, delayInMin, TimeUnit.MINUTES);
            delayInMin = (int) ((double) delayInMin * 1.5);
        }
        resendCounter++;
    }

    private void onMessageStateChange(MessageState newValue) {
        if (isAckedByReceiver()) {
            cleanup();
        }
    }

    protected boolean isAckedByReceiver() {
        return getReceiver().isDisputeOpenedMessageReceived();
    }
}
