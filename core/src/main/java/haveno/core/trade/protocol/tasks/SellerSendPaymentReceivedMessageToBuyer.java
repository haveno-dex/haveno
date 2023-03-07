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
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.messages.TradeMessage;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class SellerSendPaymentReceivedMessageToBuyer extends SellerSendPaymentReceivedMessage {

    public SellerSendPaymentReceivedMessageToBuyer(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }


    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (processModel.getPaymentReceivedMessage() == null) {
            processModel.setPaymentReceivedMessage((PaymentReceivedMessage) super.getTradeMailboxMessage(tradeId)); // save payment received message for buyer
        }
        return processModel.getPaymentReceivedMessage();
    }

    protected NodeAddress getReceiverNodeAddress() {
        return trade.getBuyer().getNodeAddress();
    }

    protected PubKeyRing getReceiverPubKeyRing() {
        return trade.getBuyer().getPubKeyRing();
    }

    // continue execution on fault so payment received message is sent to arbitrator
    @Override
    protected void onFault(String errorMessage, TradeMessage message) {
        setStateFault();
        appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
        complete();
    }
}
