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
import haveno.core.trade.messages.DepositsConfirmedMessage;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

/**
 * Send message on first confirmation to decrypt peer payment account and update multisig hex.
 */
@Slf4j
public abstract class SendDepositsConfirmedMessage extends SendMailboxMessageTask {
    private Timer timer;
    private static final int MAX_RESEND_ATTEMPTS = 20;
    private int delayInMin = 10;
    private int resendCounter = 0;

    private DepositsConfirmedMessage message;

    public SendDepositsConfirmedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if already acked or payout published
            if (isAckedByReceiver() || trade.isPayoutPublished()) {
                if (!isCompleted()) complete();
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
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
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (message == null) {

            // export multisig hex once
            if (trade.getSelf().getUpdatedMultisigHex() == null) {
                trade.exportMultisigHex();
            }

            // We do not use a real unique ID here as we want to be able to re-send the exact same message in case the
            // peer does not respond with an ACK msg in a certain time interval. To avoid that we get dangling mailbox
            // messages where only the one which gets processed by the peer would be removed we use the same uid. All
            // other data stays the same when we re-send the message at any time later.
            String deterministicId = HavenoUtils.getDeterministicId(trade, DepositsConfirmedMessage.class, getReceiverNodeAddress());
            message = new DepositsConfirmedMessage(
                    trade.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    deterministicId,
                    trade.getBuyer() == trade.getTradePeer(getReceiverNodeAddress()) ?  trade.getSeller().getPaymentAccountKey() : null, // buyer receives seller's payment account decryption key
                    trade.getSelf().getUpdatedMultisigHex());
        }
        return message;
    }

    @Override
    protected void setStateSent() {
        getReceiver().setDepositsConfirmedMessageState(MessageState.SENT);
        tryToSendAgainLater();
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        getReceiver().setDepositsConfirmedMessageState(MessageState.ARRIVED);
    }

    @Override
    protected void setStateStoredInMailbox() {
        getReceiver().setDepositsConfirmedMessageState(MessageState.STORED_IN_MAILBOX);
    }

    @Override
    protected void setStateFault() {
        getReceiver().setDepositsConfirmedMessageState(MessageState.FAILED);
    }

    private void cleanup() {
        if (timer != null) {
            timer.stop();
        }
    }

    private void tryToSendAgainLater() {

        // skip if already acked or payout published
        if (isAckedByReceiver() || trade.isPayoutPublished()) return;

        if (resendCounter >= MAX_RESEND_ATTEMPTS) {
            cleanup();
            log.warn("We never received an ACK message when sending the DepositsConfirmedMessage to the peer. We stop trying to send the message.");
            return;
        }

        if (timer != null) {
            timer.stop();
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

    private boolean isAckedByReceiver() {
        return getReceiver().isDepositsConfirmedMessageAcked();
    }
}
