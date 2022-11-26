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

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.core.trade.SellerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.SellerPreparePaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.SellerSendPaymentReceivedMessageToArbitrator;
import bisq.core.trade.protocol.tasks.SellerSendPaymentReceivedMessageToBuyer;
import bisq.core.trade.protocol.tasks.SendDepositsConfirmedMessageToArbitrator;
import bisq.core.trade.protocol.tasks.SendDepositsConfirmedMessageToBuyer;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.NodeAddress;
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

        // re-send payment received message if not arrived
        if (trade.getState() == Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG || trade.getState() == Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG) {
            synchronized (trade) {
                latchTrade();
                given(anyPhase(Trade.Phase.PAYMENT_RECEIVED)
                    .anyState(Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG, Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG)
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
        new Thread(() -> {
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
                                this.errorMessageHandler = null;
                                handleTaskRunnerSuccess(event);
                                resultHandler.handleResult();
                            }, (errorMessage) -> {
                                handleTaskRunnerFault(event, errorMessage);
                            })))
                            .run(() -> trade.setState(Trade.State.SELLER_CONFIRMED_IN_UI_PAYMENT_RECEIPT))
                            .executeTasks(true);
                } catch (Exception e) {
                    errorMessageHandler.handleErrorMessage("Error confirming payment received: " + e.getMessage());
                    unlatchTrade();
                }
                awaitTradeLatch();
            }
        }).start();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
        return new Class[] { SendDepositsConfirmedMessageToArbitrator.class, SendDepositsConfirmedMessageToBuyer.class };
    }
}
