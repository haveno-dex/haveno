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
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.BuyerPreparePaymentSentMessage;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToArbitrator;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToSeller;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToArbitrator;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.network.p2p.NodeAddress;
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

        // done if shut down
        if (trade.isShutDown()) return;

        // re-send payment sent message if not acked
        synchronized (trade) {
            if (trade.getState().ordinal() >= Trade.State.BUYER_SENT_PAYMENT_SENT_MSG.ordinal() && trade.getState().ordinal() < Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG.ordinal()) {
                latchTrade();
                given(anyPhase(Trade.Phase.PAYMENT_SENT)
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

    public void onPaymentSent(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        System.out.println("BuyerProtocol.onPaymentSent()");
        ThreadUtils.execute(() -> {
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
        }, trade.getId());
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
        return new Class[] { SendDepositsConfirmedMessageToArbitrator.class };
    }
}
