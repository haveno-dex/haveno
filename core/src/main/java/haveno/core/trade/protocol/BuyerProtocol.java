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
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.BuyerPreparePaymentSentMessage;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToArbitrator;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToSeller;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToArbitrator;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToSeller;
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
    protected void onInitializeAfterMailboxMessages() {
        super.onInitializeAfterMailboxMessages();
        maybeResendPaymentSentMessage();
    }

    private void maybeResendPaymentSentMessage() {

        // re-send payment sent message if not acked
        if (trade.isShutDownStarted() || trade.isPayoutPublished()) return;
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                if (trade.isShutDownStarted() || trade.isPayoutPublished()) return;
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
        }, trade.getId());
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
        log.info(TradeProtocol.LOG_HIGHLIGHT + "BuyerProtocol.onPaymentSent() for {} {}", trade.getClass().getSimpleName(), trade.getShortId());

        // advance trade state
        if (trade.isDepositsUnlocked() || trade.isDepositsFinalized() || trade.isPaymentSent()) {
            trade.setStateIfValidTransitionTo(Trade.State.BUYER_CONFIRMED_PAYMENT_SENT);
        } else {
            errorMessageHandler.handleErrorMessage("Cannot confirm payment sent for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " in state " + trade.getState());
            return;
        }

        // process on trade thread
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                latchTrade();
                this.errorMessageHandler = errorMessageHandler;
                BuyerEvent event = BuyerEvent.PAYMENT_SENT;
                try {
                    expect(anyPhase(Trade.Phase.DEPOSITS_UNLOCKED, Trade.Phase.DEPOSITS_FINALIZED, Trade.Phase.PAYMENT_SENT)
                            .with(event)
                            .preCondition(trade.confirmPermitted()))
                            .setup(tasks(ApplyFilter.class,
                                    BuyerPreparePaymentSentMessage.class,
                                    BuyerSendPaymentSentMessageToSeller.class,
                                    BuyerSendPaymentSentMessageToArbitrator.class)
                            .using(new TradeTaskRunner(trade,
                                    () -> {
                                        stopTimeout();
                                        this.errorMessageHandler = null;
                                        resultHandler.handleResult();
                                        handleTaskRunnerSuccess(event);
                                    },
                                    (errorMessage) -> {
                                        log.warn("Error confirming payment sent, reverting state to {}, error={}", Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN, errorMessage);
                                        trade.setState(Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
                                        handleTaskRunnerFault(event, errorMessage);
                                    })))
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
        return new Class[] { SendDepositsConfirmedMessageToSeller.class, SendDepositsConfirmedMessageToArbitrator.class };
    }
}
