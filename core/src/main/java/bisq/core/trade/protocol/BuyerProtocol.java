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
import bisq.core.trade.messages.PaymentAccountKeyResponse;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.FluentProtocol.Condition;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.BuyerSendsPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.BuyerPreparesPaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerProcessesPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.BuyerSendsPaymentAccountKeyRequestToArbitrator;
import bisq.core.trade.protocol.tasks.BuyerSendsPaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerSetupPayoutTxListener;
import bisq.core.trade.protocol.tasks.BuyerProcessesPaymentAccountKeyResponse;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;

@Slf4j
public abstract class BuyerProtocol extends DisputeProtocol {
    
    private boolean listeningToSendPaymentAccountKey;
    private boolean paymentAccountPayloadKeyRequestSent;
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

        // TODO: run with trade lock and latch, otherwise getting invalid transition warnings on startup after offline trades
        
        // request key to decrypt seller's payment account payload after first confirmation
        sendPaymentAccountKeyRequestIfWhenNeeded(BuyerEvent.STARTUP, false);

        given(anyPhase(Trade.Phase.DEPOSIT_REQUESTED, Trade.Phase.DEPOSITS_PUBLISHED, Trade.Phase.DEPOSITS_CONFIRMED)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(SetupDepositTxsListener.class))
                .executeTasks();

        given(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(BuyerSetupPayoutTxListener.class)) // TODO (woodser): mirror deposit listener setup?
                .executeTasks();

        given(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                .anyState(Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG, Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG)
                .with(BuyerEvent.STARTUP))
                .setup(tasks(BuyerSendsPaymentSentMessage.class))
                .executeTasks();
    }

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);
        if (message instanceof PaymentReceivedMessage) {
            handle((PaymentReceivedMessage) message, peer);
        } if (message instanceof PaymentAccountKeyResponse) {
            handle((PaymentAccountKeyResponse) message, peer);
        }
    }

    @Override
    public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
        super.onMailboxMessage(message, peer);
        if (message instanceof PaymentReceivedMessage) {
            handle((PaymentReceivedMessage) message, peer);
        } else if (message instanceof PaymentAccountKeyResponse) {
            handle((PaymentAccountKeyResponse) message, peer);
        }
    }

    @Override
    public void handleSignContractResponse(SignContractResponse response, NodeAddress sender) {
        sendPaymentAccountKeyRequestIfWhenNeeded(BuyerEvent.DEPOSIT_TXS_CONFIRMED, true);
        super.handleSignContractResponse(response, sender);
    }

    public void handle(PaymentAccountKeyResponse response, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handlePaymentAccountKeyResponse()");
        new Thread(() -> {
            synchronized (trade) {
                latchTrade();
                expect(new Condition(trade)
                        .with(response)
                        .from(sender))
                        .setup(tasks(BuyerProcessesPaymentAccountKeyResponse.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    handleTaskRunnerSuccess(sender, response);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(sender, response, errorMessage);
                                })))
                        .executeTasks();
                awaitTradeLatch();
            }
        }).start();
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
                                    //UpdateMultisigWithTradingPeer.class, // TODO (woodser): can use this to test protocol with updated multisig from peer. peer should attempt to send updated multisig hex earlier as part of protocol. cannot use with countdown latch because response comes back in a separate thread and blocks on trade
                                    BuyerPreparesPaymentSentMessage.class,
                                    //BuyerSetupPayoutTxListener.class,
                                    BuyerSendsPaymentSentMessage.class) // don't latch trade because this blocks and runs in background
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

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message Payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(PaymentReceivedMessage message, NodeAddress peer) {
        System.out.println("BuyerProtocol.handle(PaymentReceivedMessage)");
        new Thread(() -> {
            synchronized (trade) {
                latchTrade();
                Validator.checkTradeId(processModel.getOfferId(), message);
                processModel.setTradeMessage(message);
                expect(anyPhase(Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED)
                    .with(message)
                    .from(peer))
                    .setup(tasks(
                        BuyerProcessesPaymentReceivedMessage.class,
                        BuyerSendsPayoutTxPublishedMessage.class)
                        .using(new TradeTaskRunner(trade,
                            () -> {
                                handleTaskRunnerSuccess(peer, message);
                            },
                            errorMessage -> {
                                handleTaskRunnerFault(peer, message, errorMessage);
                            })))
                    .executeTasks(true);
                awaitTradeLatch();
            }
        }).start();
    }

    private void sendPaymentAccountKeyRequestIfWhenNeeded(BuyerEvent event, boolean waitForSellerOnConfirm) {

        // skip if payment account payload already decrypted or not enough progress
        if (trade.getSeller().getPaymentAccountPayload() != null) return;
        if (trade.getPhase().ordinal() < Trade.Phase.DEPOSIT_REQUESTED.ordinal()) return;

        // if confirmed and waiting for seller, recheck later
        if (trade.getState() == Trade.State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN && waitForSellerOnConfirm) {
            UserThread.runAfter(() -> {
                sendPaymentAccountKeyRequestIfWhenNeeded(event, false);
            }, TRADE_TIMEOUT);
            return;
        }

        // else if confirmed send request and return
        else if (trade.getState().ordinal() >= Trade.State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN.ordinal()) {
            sendPaymentAccountKeyRequest(event);
            return;
        }

        // register for state changes once
        if (!listeningToSendPaymentAccountKey) {
            listeningToSendPaymentAccountKey = true;
            EasyBind.subscribe(trade.stateProperty(), state -> {
                sendPaymentAccountKeyRequestIfWhenNeeded(event, waitForSellerOnConfirm);
            });
        }
    }

    private void sendPaymentAccountKeyRequest(BuyerEvent event) {
        new Thread(() -> {
            synchronized (trade) {
                if (paymentAccountPayloadKeyRequestSent) return;
                if (trade.getSeller().getPaymentAccountPayload() != null) return; // skip if initialized
                latchTrade();
                expect(new Condition(trade))
                        .setup(tasks(BuyerSendsPaymentAccountKeyRequestToArbitrator.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    handleTaskRunnerSuccess(event);
                                },
                                (errorMessage) -> {
                                    handleTaskRunnerFault(event, errorMessage);
                                })))
                        .executeTasks(true);
                awaitTradeLatch();
                paymentAccountPayloadKeyRequestSent = true;
            }
        }).start();
    }
}
