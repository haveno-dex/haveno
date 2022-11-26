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
import bisq.core.trade.BuyerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.BuyerPreparePaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToArbitrator;
import bisq.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToSeller;
import bisq.core.trade.protocol.tasks.SendDepositsConfirmedMessageToArbitrator;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerProtocol extends DisputeProtocol {
    
    enum BuyerEvent implements FluentProtocol.Event {
        STARTUP,
        DEPOSIT_TXS_CONFIRMED,
        PAYMENT_SENT
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerProtocol(BuyerTrade trade) {
        super(trade);
    }

    @Override
    protected void onInitialized() {
        super.onInitialized();

        // re-send payment sent message if not arrived
        if (trade.getState() == Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG || trade.getState() == Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG) {
            synchronized (trade) {
                latchTrade();
                given(anyPhase(Trade.Phase.PAYMENT_SENT)
                    .anyState(Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG, Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG)
                    .with(BuyerEvent.STARTUP))
                    .setup(tasks(
                            BuyerSendPaymentSentMessageToSeller.class,
                            BuyerSendPaymentSentMessageToArbitrator.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                unlatchTrade();
                            },
                            (errorMessage) -> {
                                log.warn("Error sending PaymentSentMessage on startup: " + errorMessage);
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
    public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
        super.onMailboxMessage(message, peer);
    }

    @Override
    public void handleSignContractResponse(SignContractResponse response, NodeAddress sender) {
        super.handleSignContractResponse(response, sender);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        System.out.println("BuyerProtocol.onPaymentStarted()");
        new Thread(() -> {
            synchronized (trade) {
                latchTrade();
                this.errorMessageHandler = errorMessageHandler;
                BuyerEvent event = BuyerEvent.PAYMENT_SENT;
                try {
                    expect(anyPhase(Trade.Phase.DEPOSITS_UNLOCKED, Trade.Phase.PAYMENT_SENT)
                            .with(event)
                            .preCondition(trade.confirmPermitted()))
                            .setup(tasks(ApplyFilter.class,
                                    BuyerPreparePaymentSentMessage.class,
                                    BuyerSendPaymentSentMessageToSeller.class,
                                    BuyerSendPaymentSentMessageToArbitrator.class)
                            .using(new TradeTaskRunner(trade,
                                    () -> {
                                        this.errorMessageHandler = null;
                                        resultHandler.handleResult();
                                        handleTaskRunnerSuccess(event);
                                    },
                                    (errorMessage) -> {
                                        handleTaskRunnerFault(event, errorMessage);
                                    })))
                            .run(() -> trade.setState(Trade.State.BUYER_CONFIRMED_IN_UI_PAYMENT_SENT))
                            .executeTasks(true);
                } catch (Exception e) {
                    errorMessageHandler.handleErrorMessage("Error confirming payment sent: " + e.getMessage());
                    unlatchTrade();
                }
                awaitTradeLatch();
            }
        }).start();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
        return new Class[] { SendDepositsConfirmedMessageToArbitrator.class };
    }
}
