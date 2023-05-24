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

import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.messages.TradeMessage;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendMailboxMessageListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class SendMailboxMessageTask extends TradeTask {
    public SendMailboxMessageTask(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    protected NodeAddress getReceiverNodeAddress() {
        return trade.getTradePeer().getNodeAddress();
    }

    protected PubKeyRing getReceiverPubKeyRing() {
        return trade.getTradePeer().getPubKeyRing();
    }

    protected abstract TradeMailboxMessage getTradeMailboxMessage(String tradeId);

    protected abstract void setStateSent();

    protected abstract void setStateArrived();

    protected abstract void setStateStoredInMailbox();

    protected abstract void setStateFault();

    @Override
    protected void run() {
        try {
            runInterceptHook();
            String id = processModel.getOfferId();
            TradeMailboxMessage message = getTradeMailboxMessage(id);
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
                            log.info("{} arrived at peer {}. tradeId={}, uid={}", message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                            setStateArrived();
                            if (!task.isCompleted()) complete();
                        }

                        @Override
                        public void onStoredInMailbox() {
                            log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}", message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                            SendMailboxMessageTask.this.onStoredInMailbox();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}", message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
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

    protected void onFault(String errorMessage, TradeMessage message) {
        setStateFault();
        appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
        failed(errorMessage);
    }
}
