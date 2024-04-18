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

package haveno.core.trade.protocol;

import haveno.common.ThreadUtils;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.SellerPreparePaymentReceivedMessage;
import haveno.core.trade.protocol.tasks.SellerSendPaymentReceivedMessageToArbitrator;
import haveno.core.trade.protocol.tasks.SellerSendPaymentReceivedMessageToBuyer;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToArbitrator;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToBuyer;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerProtocol extends DisputeProtocol {
    enum SellerEvent implements FluentProtocol.Event {
        STARTUP,
        DEPOSIT_TXS_CONFIRMED,
        PAYMENT_RECEIVED
    }

    public SellerProtocol(SellerTrade trade) {
        super(trade);
    }
    
    @Override
    protected void onInitialized() {
        super.onInitialized();

        // re-send payment received message if payout not published
        ThreadUtils.execute(() -> {
            if (trade.isShutDownStarted() || trade.isPayoutPublished()) return;
            synchronized (trade) {
                if (trade.isShutDownStarted() || trade.isPayoutPublished()) return;
                if (trade.getState().ordinal() >= Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG.ordinal() && !trade.isPayoutPublished()) {
                    latchTrade();
                    given(anyPhase(Trade.Phase.PAYMENT_RECEIVED)
                        .with(SellerEvent.STARTUP))
                        .setup(tasks(
                                SellerSendPaymentReceivedMessageToBuyer.class,
                                SellerSendPaymentReceivedMessageToArbitrator.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    unlatchTrade();
                                },
                                (errorMessage) -> {
                                    log.warn("Error sending PaymentReceivedMessage on startup: " + errorMessage);
                                    unlatchTrade();
                                })))
                        .executeTasks();
                    awaitTradeLatch();
                }
            }
        }, trade.getId());
    }

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);
    }

    @Override
    public void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        super.onMailboxMessage(message, peerNodeAddress);
    }

    @Override
    public void handleSignContractResponse(SignContractResponse response, NodeAddress sender) {
        super.handleSignContractResponse(response, sender);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.info("SellerProtocol.onPaymentReceived()");
        ThreadUtils.execute(() -> {
            synchronized (trade) {
                latchTrade();
                this.errorMessageHandler = errorMessageHandler;
                SellerEvent event = SellerEvent.PAYMENT_RECEIVED;
                try {
                    expect(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                            .with(event)
                            .preCondition(trade.confirmPermitted()))
                            .setup(tasks(
                                    ApplyFilter.class,
                                    SellerPreparePaymentReceivedMessage.class,
                                    SellerSendPaymentReceivedMessageToBuyer.class,
                                    SellerSendPaymentReceivedMessageToArbitrator.class)
                            .using(new TradeTaskRunner(trade, () -> {
                                stopTimeout();
                                this.errorMessageHandler = null;
                                handleTaskRunnerSuccess(event);
                                resultHandler.handleResult();
                            }, (errorMessage) -> {
                                handleTaskRunnerFault(event, errorMessage);
                            }))
                            .withTimeout(TradeProtocol.TRADE_TIMEOUT_SECONDS))
                            .run(() -> trade.advanceState(Trade.State.SELLER_CONFIRMED_PAYMENT_RECEIPT))
                            .executeTasks(true);
                } catch (Exception e) {
                    errorMessageHandler.handleErrorMessage("Error confirming payment received: " + e.getMessage());
                    unlatchTrade();
                }
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
        return new Class[] { SendDepositsConfirmedMessageToArbitrator.class, SendDepositsConfirmedMessageToBuyer.class };
    }
}
