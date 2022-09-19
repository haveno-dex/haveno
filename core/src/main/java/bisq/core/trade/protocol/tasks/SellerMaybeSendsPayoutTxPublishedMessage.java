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

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.crypto.PubKeyRing;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class SellerMaybeSendsPayoutTxPublishedMessage extends SendMailboxMessageTask {

    public SellerMaybeSendsPayoutTxPublishedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if payout tx not published
            if (trade.getPhase().ordinal() < Trade.Phase.PAYOUT_PUBLISHED.ordinal()) {
                complete();
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected NodeAddress getReceiverNodeAddress() {
        return trade.getArbitratorNodeAddress();
    }

    @Override
    protected PubKeyRing getReceiverPubKeyRing() {
        return trade.getArbitratorPubKeyRing();
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        checkNotNull(trade.getSelf().getPayoutTxHex(), "Payout tx must not be null");
        return new PayoutTxPublishedMessage(
                tradeId,
                processModel.getMyNodeAddress(),
                null, // TODO: send witness data?
                trade.getSelf().getPayoutTxHex()
        );
    }

    @Override
    protected void setStateSent() {
        log.info("Seller sent PayoutTxPublishedMessage: tradeId={} at arbitrator {}", trade.getId(), getReceiverNodeAddress());
    }

    @Override
    protected void setStateArrived() {
        log.info("Seller's PayoutTxPublishedMessage arrived: tradeId={} at arbitrator {}", trade.getId(), getReceiverNodeAddress());
    }

    @Override
    protected void setStateStoredInMailbox() {
        log.info("Seller's PayoutTxPublishedMessage stored in mailbox: tradeId={} at arbitrator {}", trade.getId(), getReceiverNodeAddress());
    }

    @Override
    protected void setStateFault() {
        log.error("Seller's PayoutTxPublishedMessage failed: tradeId={} at arbitrator {}", trade.getId(), getReceiverNodeAddress());
    }
}
