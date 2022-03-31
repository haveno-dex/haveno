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

package bisq.core.trade.protocol.tasks.seller;

import bisq.core.account.sign.SignedWitness;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.core.trade.protocol.tasks.SendMailboxMessageTask;

import bisq.common.taskrunner.TaskRunner;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class SellerSendsPaymentReceivedMessage extends SendMailboxMessageTask {
    SignedWitness signedWitness = null;

    public SellerSendsPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            if (trade.getSeller().getPayoutTxHex() == null) {
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
        checkNotNull(trade.getSeller().getPayoutTxHex(), "Payout tx must not be null");

        AccountAgeWitnessService accountAgeWitnessService = processModel.getAccountAgeWitnessService();
        if (accountAgeWitnessService.isSignWitnessTrade(trade)) {
            // Broadcast is done in accountAgeWitness domain.
            accountAgeWitnessService.traderSignAndPublishPeersAccountAgeWitness(trade).ifPresent(witness -> signedWitness = witness);
        }

        return new PaymentReceivedMessage(
                id,
                processModel.getMyNodeAddress(),
                signedWitness,
                trade.getSeller().getPayoutTxHex()
        );
    }

    @Override
    protected void setStateSent() {
        trade.setState(Trade.State.SELLER_SENT_PAYOUT_TX_PUBLISHED_MSG);
        log.info("Sent SellerReceivedPaymentMessage: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeerNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.setState(Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG);
        log.info("SellerReceivedPaymentMessage arrived: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeerNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setState(Trade.State.SELLER_STORED_IN_MAILBOX_PAYOUT_TX_PUBLISHED_MSG);
        log.info("SellerReceivedPaymentMessage storedInMailbox: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeerNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        trade.setState(Trade.State.SELLER_SEND_FAILED_PAYOUT_TX_PUBLISHED_MSG);
        log.error("SellerReceivedPaymentMessage failed: tradeId={} at peer {} SignedWitness {}",
                trade.getId(), trade.getTradingPeerNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }
}
