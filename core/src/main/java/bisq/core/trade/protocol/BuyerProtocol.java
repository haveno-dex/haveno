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

import bisq.core.trade.BuyerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DelayedPayoutTxSignatureRequest;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.UpdateMultisigWithTradingPeer;
import bisq.core.trade.protocol.tasks.buyer.BuyerPreparesPaymentSentMessage;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessesPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendsPaymentSentMessage;
import bisq.core.trade.protocol.tasks.buyer.BuyerSetupPayoutTxListener;

import bisq.network.p2p.NodeAddress;
import java.util.concurrent.CountDownLatch;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BuyerProtocol extends DisputeProtocol {
    enum BuyerEvent implements FluentProtocol.Event {
        STARTUP,
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
        
        given(phase(Trade.Phase.DEPOSIT_PUBLISHED)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(SetupDepositTxsListener.class))
                .executeTasks();

        given(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(BuyerSetupPayoutTxListener.class)) // TODO (woodser): mirror deposit listener setup?
                .executeTasks();

        given(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                .anyState(Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_INITIATED_MSG,
                        Trade.State.BUYER_SEND_FAILED_PAYMENT_INITIATED_MSG)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(BuyerSendsPaymentSentMessage.class))
                .executeTasks();
    }

    @Override
    public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
        super.onMailboxMessage(message, peer);

        if (message instanceof DepositTxAndDelayedPayoutTxMessage) {
            handle((DepositTxAndDelayedPayoutTxMessage) message, peer);
        } else if (message instanceof PaymentReceivedMessage) {
            handle((PaymentReceivedMessage) message, peer);
        }
    }

    protected abstract void handle(DelayedPayoutTxSignatureRequest message, NodeAddress peer);

    // The DepositTxAndDelayedPayoutTxMessage is a mailbox message as earlier we use only the deposit tx which can
    // be also with from the network once published.
    // Now we send the delayed payout tx as well and with that this message is mandatory for continuing the protocol.
    // We do not support mailbox message handling during the take offer process as it is expected that both peers
    // are online.
    // For backward compatibility and extra resilience we still keep DepositTxAndDelayedPayoutTxMessage as a
    // mailbox message but the stored in mailbox case is not expected and the seller would try to send the message again
    // in the hope to reach the buyer directly.
    protected void handle(DepositTxAndDelayedPayoutTxMessage message, NodeAddress peer) {
//        expect(anyPhase(Trade.Phase.TAKER_FEE_PUBLISHED, Trade.Phase.DEPOSIT_PUBLISHED)
//                .with(message)
//                .from(peer)
//                .preCondition(trade.getDepositTx() == null || trade.getDelayedPayoutTx() == null,
//                        () -> {
//                            log.warn("We with a DepositTxAndDelayedPayoutTxMessage but we have already processed the deposit and " +
//                                    "delayed payout tx so we ignore the message. This can happen if the ACK message to the peer did not " +
//                                    "arrive and the peer repeats sending us the message. We send another ACK msg.");
//                            stopTimeout();
//                            sendAckMessage(message, true, null);
//                            removeMailboxMessageAfterProcessing(message);
//                        }))
//                .setup(tasks(BuyerProcessDepositTxAndDelayedPayoutTxMessage.class,
//                        BuyerVerifiesFinalDelayedPayoutTx.class)
//                        .using(new TradeTaskRunner(trade,
//                                () -> {
//                                    stopTimeout();
//                                    handleTaskRunnerSuccess(message);
//                                },
//                                errorMessage -> handleTaskRunnerFault(message, errorMessage))))
//                .run(() -> processModel.witnessDebugLog(trade))
//                .executeTasks();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        System.out.println("BuyerProtocol.onPaymentStarted()");
        synchronized (trade) { // TODO (woodser): UpdateMultisigWithTradingPeer sends UpdateMultisigRequest and waits for UpdateMultisigResponse which is new thread, so synchronized (trade) in subsequent pipeline blocks forever if we hold on with countdown latch in this function
            System.out.println("BuyerProtocol.onPaymentStarted() has the lock!!!");
            BuyerEvent event = BuyerEvent.PAYMENT_SENT;
            CountDownLatch latch = new CountDownLatch(1);
            expect(phase(Trade.Phase.DEPOSIT_CONFIRMED)
                    .with(event)
                    .preCondition(trade.confirmPermitted()))
                    .setup(tasks(ApplyFilter.class,
                            getVerifyPeersFeePaymentClass(),
                            //UpdateMultisigWithTradingPeer.class, // TODO (woodser): can use this to test protocol with updated multisig from peer. peer should attempt to send updated multisig hex earlier as part of protocol. cannot use with countdown latch because response comes back in a separate thread and blocks on trade
                            BuyerPreparesPaymentSentMessage.class,
                            //BuyerSetupPayoutTxListener.class,
                            BuyerSendsPaymentSentMessage.class)
                            .using(new TradeTaskRunner(trade,
                                    () -> {
                                        latch.countDown();
                                        resultHandler.handleResult();
                                        handleTaskRunnerSuccess(event);
                                    },
                                    (errorMessage) -> {
                                        latch.countDown();
                                        errorMessageHandler.handleErrorMessage(errorMessage);
                                        handleTaskRunnerFault(event, errorMessage);
                                    })))
                    .run(() -> trade.setState(Trade.State.BUYER_CONFIRMED_IN_UI_PAYMENT_INITIATED))
                    .executeTasks();
       }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message Payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(PaymentReceivedMessage message, NodeAddress peer) {
        log.info("BuyerProtocol.handle(SellerReceivedPaymentMessage)");
        synchronized (trade) {
            processModel.setTradeMessage(message);
            processModel.setTempTradingPeerNodeAddress(peer);
            CountDownLatch latch = new CountDownLatch(1);
            expect(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYOUT_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
                    getVerifyPeersFeePaymentClass(),
                    BuyerProcessesPaymentReceivedMessage.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            latch.countDown();
                            handleTaskRunnerSuccess(peer, message);
                        },
                        errorMessage -> {
                            latch.countDown();
                            handleTaskRunnerFault(peer, message, errorMessage);
                        })))
                .executeTasks();
            wait(latch);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);

        log.info("Received {} from {} with tradeId {} and uid {}",
                message.getClass().getSimpleName(), peer, message.getTradeId(), message.getUid());

        if (message instanceof DelayedPayoutTxSignatureRequest) {
            handle((DelayedPayoutTxSignatureRequest) message, peer);
        } else if (message instanceof DepositTxAndDelayedPayoutTxMessage) {
            handle((DepositTxAndDelayedPayoutTxMessage) message, peer);
        } else if (message instanceof PaymentReceivedMessage) {
            handle((PaymentReceivedMessage) message, peer);
        }
    }

    abstract protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass();
}
