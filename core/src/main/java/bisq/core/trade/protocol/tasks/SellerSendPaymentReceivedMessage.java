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

import bisq.core.account.sign.SignedWitness;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.common.taskrunner.TaskRunner;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class SellerSendPaymentReceivedMessage extends SendMailboxMessageTask {
    SignedWitness signedWitness = null;

    public SellerSendPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            if (trade.getPayoutTxHex() == null) {
                log.error("Payout tx is null");
                failed("Payout tx is null");
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String id) {
        checkNotNull(trade.getPayoutTxHex(), "Payout tx must not be null");
        return new PaymentReceivedMessage(
                id,
                processModel.getMyNodeAddress(),
                signedWitness,
                trade.getPayoutTxHex()
        );
    }

    // TODO: using PAYOUT_TX_PUBLISHED_MSG to represent PAYMENT_RECEIVED_MSG after payout, but PAYOUT_TX_PUBLISHED_MSG is specifically for arbitrator. delete *PAYOUT_TX_PUBLISHED* messages and check payout field manually?

    @Override
    protected void setStateSent() {
        trade.setState(trade.getState().ordinal() >= Trade.State.SELLER_PUBLISHED_PAYOUT_TX.ordinal() ? Trade.State.SELLER_SENT_PAYOUT_TX_PUBLISHED_MSG : Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG);
        log.info("Sent SellerReceivedPaymentMessage: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeer().getNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.setState(trade.getState().ordinal() >= Trade.State.SELLER_PUBLISHED_PAYOUT_TX.ordinal() ? Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG : Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG);
        log.info("Seller's PaymentReceivedMessage arrived: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeer().getNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setState(trade.getState().ordinal() >= Trade.State.SELLER_PUBLISHED_PAYOUT_TX.ordinal() ? Trade.State.SELLER_STORED_IN_MAILBOX_PAYOUT_TX_PUBLISHED_MSG : Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG);
        log.info("Seller's PaymentReceivedMessage stored in mailbox: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeer().getNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        trade.setState(trade.getState().ordinal() >= Trade.State.SELLER_PUBLISHED_PAYOUT_TX.ordinal() ? Trade.State.SELLER_SEND_FAILED_PAYOUT_TX_PUBLISHED_MSG : Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG);
        log.error("SellerReceivedPaymentMessage failed: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeer().getNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }
}
