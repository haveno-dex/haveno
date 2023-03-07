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

package haveno.core.trade.protocol.tasks.mediation;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.support.dispute.mediation.MediationResultState;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.protocol.tasks.SendMailboxMessageTask;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class SendMediatedPayoutTxPublishedMessage extends SendMailboxMessageTask {
    public SendMediatedPayoutTxPublishedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String id) {
        throw new RuntimeException("SendMediatedPayoutTxPublishedMessage.getMessage(id) not implemented for xmr");
//        Transaction payoutTx = checkNotNull(trade.getPayoutTx(), "trade.getPayoutTx() must not be null");
//        return new MediatedPayoutTxPublishedMessage(
//                id,
//                payoutTx.bitcoinSerialize(),
//                processModel.getMyNodeAddress(),
//                UUID.randomUUID().toString()
//        );
    }

    @Override
    protected void setStateSent() {
        trade.setMediationResultState(MediationResultState.PAYOUT_TX_PUBLISHED_MSG_SENT);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.setMediationResultState(MediationResultState.PAYOUT_TX_PUBLISHED_MSG_ARRIVED);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setMediationResultState(MediationResultState.PAYOUT_TX_PUBLISHED_MSG_IN_MAILBOX);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        trade.setMediationResultState(MediationResultState.PAYOUT_TX_PUBLISHED_MSG_SEND_FAILED);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            if (trade.getPayoutTx() == null) {
                log.error("PayoutTx is null");
                failed("PayoutTx is null");
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
