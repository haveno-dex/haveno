/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

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

import java.util.concurrent.TimeUnit;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.network.MessageState;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.NodeAddress;
import javafx.beans.value.ChangeListener;
import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode(callSuper = true)
public abstract class BuyerSendPaymentSentMessage extends SendMailboxMessageTask {
    private ChangeListener<MessageState> listener;
    private Timer timer;
    private static final int MAX_RESEND_ATTEMPTS = 20;
    private int delayInMin = 10;
    private int resendCounter = 0;

    public BuyerSendPaymentSentMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    protected abstract TradePeer getReceiver();

    @Override
    protected NodeAddress getReceiverNodeAddress() {
        return getReceiver().getNodeAddress();
    }

    @Override
    protected PubKeyRing getReceiverPubKeyRing() {
        return getReceiver().getPubKeyRing();
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if already acked by receiver
            if (isAckedByReceiver()) {
                if (!isCompleted()) complete();
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (getReceiver().getPaymentSentMessage() == null) {

            // We do not use a real unique ID here as we want to be able to re-send the exact same message in case the
            // peer does not respond with an ACK msg in a certain time interval. To avoid that we get dangling mailbox
            // messages where only the one which gets processed by the peer would be removed we use the same uid. All
            // other data stays the same when we re-send the message at any time later.
            String deterministicId = HavenoUtils.getDeterministicId(trade, PaymentSentMessage.class, getReceiverNodeAddress());

            // create payment sent message
            PaymentSentMessage message = new PaymentSentMessage(
                    tradeId,
                    processModel.getMyNodeAddress(),
                    trade.getCounterCurrencyTxId(),
                    trade.getCounterCurrencyExtraData(),
                    deterministicId,
                    trade.getSelf().getUnsignedPayoutTxHex(),
                    trade.getSelf().getUpdatedMultisigHex(),
                    trade.getSelf().getPaymentAccountKey(),
                    trade.getTradePeer().getAccountAgeWitness()
            );

            // sign message
            try {
                String messageAsJson = JsonUtil.objectToJson(message);
                byte[] sig = HavenoUtils.sign(processModel.getP2PService().getKeyRing(), messageAsJson);
                message.setBuyerSignature(sig);
                getReceiver().setPaymentSentMessage(message);
                trade.requestPersistence();
            } catch (Exception e) {
                throw new RuntimeException (e);
            }
        }
        return getReceiver().getPaymentSentMessage();
    }

    @Override
    protected void setStateSent() {
        getReceiver().setPaymentSentMessageState(MessageState.SENT);
        tryToSendAgainLater();
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        getReceiver().setPaymentSentMessageState(MessageState.ARRIVED);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        getReceiver().setPaymentSentMessageState(MessageState.STORED_IN_MAILBOX);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        getReceiver().setPaymentSentMessageState(MessageState.FAILED);
        processModel.getTradeManager().requestPersistence();
    }

    private void cleanup() {
        if (timer != null) {
            timer.stop();
        }
        if (listener != null) {
            trade.getSeller().getPaymentReceivedMessageStateProperty().removeListener(listener);
        }
    }

    private void tryToSendAgainLater() {

        // skip if already acked
        if (isAckedByReceiver()) return;

        if (resendCounter >= MAX_RESEND_ATTEMPTS) {
            cleanup();
            log.warn("We never received an ACK message when sending the PaymentSentMessage to the peer. We stop trying to send the message.");
            return;
        }

        if (timer != null) {
            timer.stop();
        }

        timer = UserThread.runAfter(this::run, delayInMin, TimeUnit.MINUTES);

        if (resendCounter == 0) {
            listener = (observable, oldValue, newValue) -> onMessageStateChange(newValue);
            getReceiver().getPaymentSentMessageStateProperty().addListener(listener);
            onMessageStateChange(getReceiver().getPaymentSentMessageStateProperty().get());
        }

        // first re-send is after 2 minutes, then increase the delay exponentially
        if (resendCounter == 0) {
            int shortDelay = 2;
            log.info("We will send the message again to the peer after a delay of {} min.", shortDelay);
            timer = UserThread.runAfter(this::run, shortDelay, TimeUnit.MINUTES);
        } else {
            log.info("We will send the message again to the peer after a delay of {} min.", delayInMin);
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
        return getReceiver().isPaymentSentMessageAcked();
    }
}
