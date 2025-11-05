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

package haveno.core.trade.protocol.tasks;

import java.util.concurrent.TimeUnit;

import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendMailboxMessageListener;
import haveno.network.p2p.mailbox.MailboxMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class SendMailboxMessageTask extends TradeTask {

    public static final long RESEND_STORED_MESSAGE_DELAY_MIN = TimeUnit.HOURS.toMinutes(6);

    public SendMailboxMessageTask(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    protected abstract TradePeer getReceiver();

    protected NodeAddress getReceiverNodeAddress() {
        return getReceiver().getNodeAddress();
    }

    protected PubKeyRing getReceiverPubKeyRing() {
        return getReceiver().getPubKeyRing();
    }

    protected abstract MailboxMessage getMailboxMessage(String tradeId);

    protected abstract void setStateSent();

    protected abstract void setStateArrived();

    protected abstract void setStateStoredInMailbox();

    protected abstract void setStateFault();

    @Override
    protected void run() {
        try {
            runInterceptHook();
            String id = processModel.getOfferId();
            MailboxMessage message = getMailboxMessage(id);
            setStateSent();
            NodeAddress peersNodeAddress = getReceiverNodeAddress();
            log.info("Send {} to peer {} for {} {}, uid={}",
                    message.getClass().getSimpleName(), peersNodeAddress, trade.getClass().getSimpleName(), trade.getId(), message.getUid());

            TradeTask task = this;
            processModel.getP2PService().getMailboxMessageService().sendEncryptedMailboxMessage(
                    peersNodeAddress,
                    getReceiverPubKeyRing(),
                    message,
                    new SendMailboxMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer {}. tradeId={}, uid={}", message.getClass().getSimpleName(), peersNodeAddress, trade.getId(), message.getUid());
                            setStateArrived();
                            if (!task.isCompleted()) complete();
                        }

                        @Override
                        public void onStoredInMailbox() {
                            log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}", message.getClass().getSimpleName(), peersNodeAddress, trade.getId(), message.getUid());
                            SendMailboxMessageTask.this.onStoredInMailbox();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            if (processModel.getP2PService().isShutDownStarted()) return;
                            log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}", message.getClass().getSimpleName(), peersNodeAddress, trade.getId(), message.getUid(), errorMessage);
                            SendMailboxMessageTask.this.onFault(errorMessage, message);
                        }
                    }
            );
        } catch (Throwable t) {
            failed(t);
        }
    }

    protected void onStoredInMailbox() {
        setStateStoredInMailbox();
        if (!isCompleted()) complete();
    }

    protected void onFault(String errorMessage, MailboxMessage message) {
        setStateFault();
        appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
        failed(errorMessage);
    }
}
