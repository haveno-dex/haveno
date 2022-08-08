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

package bisq.core.trade.protocol;

import bisq.core.trade.SellerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.BuyerProtocol.BuyerEvent;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.SellerPreparesPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.SellerProcessesPaymentSentMessage;
import bisq.core.trade.protocol.tasks.SellerSendsPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;
import bisq.network.p2p.NodeAddress;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class SellerProtocol extends DisputeProtocol {
    enum SellerEvent implements FluentProtocol.Event {
        STARTUP,
        PAYMENT_RECEIVED
    }

    public SellerProtocol(SellerTrade trade) {
        super(trade);
    }
    
    @Override
    protected void onInitialized() {
        super.onInitialized();
        
        given(phase(Trade.Phase.DEPOSIT_PUBLISHED)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(SetupDepositTxsListener.class))
                .executeTasks();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mailbox
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        super.onMailboxMessage(message, peerNodeAddress);

        if (message instanceof PaymentSentMessage) {
            handle((PaymentSentMessage) message, peerNodeAddress);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message when buyer has clicked payment started button
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(PaymentSentMessage message, NodeAddress peer) {
        log.info("SellerProtocol.handle(PaymentSentMessage)");
        // We are more tolerant with expected phase and allow also DEPOSIT_PUBLISHED as it can be the case
        // that the wallet is still syncing and so the DEPOSIT_CONFIRMED state to yet triggered when we received
        // a mailbox message with PaymentSentMessage.
        // TODO A better fix would be to add a listener for the wallet sync state and process
        // the mailbox msg once wallet is ready and trade state set.
        synchronized (trade) {
            if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_SENT.ordinal()) {
                log.warn("Ignoring PaymentSentMessage which was already processed");
                return;
            }
            latchTrade();
            expect(anyPhase(Trade.Phase.DEPOSIT_UNLOCKED, Trade.Phase.DEPOSIT_PUBLISHED)
                    .with(message)
                    .from(peer)
                    .preCondition(trade.getPayoutTx() == null,
                            () -> {
                                log.warn("We received a PaymentSentMessage but we have already created the payout tx " +
                                        "so we ignore the message. This can happen if the ACK message to the peer did not " +
                                        "arrive and the peer repeats sending us the message. We send another ACK msg.");
                                sendAckMessage(peer, message, true, null);
                                removeMailboxMessageAfterProcessing(message);
                            }))
                    .setup(tasks(
                            ApplyFilter.class,
                            SellerProcessesPaymentSentMessage.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                handleTaskRunnerSuccess(peer, message);
                            },
                            (errorMessage) -> {
                                stopTimeout();
                                handleTaskRunnerFault(peer, message, errorMessage);
                            })))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.info("SellerProtocol.onPaymentReceived()");
        new Thread(() -> {
            synchronized (trade) {
                latchTrade();
                this.errorMessageHandler = errorMessageHandler;
                SellerEvent event = SellerEvent.PAYMENT_RECEIVED;
                expect(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                        .with(event)
                        .preCondition(trade.confirmPermitted()))
                        .setup(tasks(
                                ApplyFilter.class,
                                SellerPreparesPaymentReceivedMessage.class,
                                SellerSendsPaymentReceivedMessage.class)
                        .using(new TradeTaskRunner(trade, () -> {
                            this.errorMessageHandler = null;
                            handleTaskRunnerSuccess(event);
                            resultHandler.handleResult();
                        }, (errorMessage) -> {
                            handleTaskRunnerFault(event, errorMessage);
                        })))
                        .run(() -> trade.setState(Trade.State.SELLER_CONFIRMED_IN_UI_PAYMENT_RECEIPT))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }).start();
    }

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);

        if (message instanceof PaymentSentMessage) {
            handle((PaymentSentMessage) message, peer);
        }
    }
}
