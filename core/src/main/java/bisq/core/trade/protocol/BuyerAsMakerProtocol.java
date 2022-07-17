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

import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import bisq.core.trade.messages.DelayedPayoutTxSignatureRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.tasks.ProcessDepositResponse;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessPaymentAccountPayloadRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractResponse;
import bisq.core.trade.protocol.tasks.SendSignContractRequestAfterMultisig;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.buyer.BuyerFinalizesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendsDelayedPayoutTxSignatureResponse;
import bisq.core.trade.protocol.tasks.buyer.BuyerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerVerifiesPreparedDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.maker.MakerRemovesOpenOffer;
import bisq.core.trade.protocol.tasks.maker.MakerSendsInitTradeRequestIfUnreserved;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.util.Validator;

import bisq.network.p2p.NodeAddress;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;

@Slf4j
public class BuyerAsMakerProtocol extends BuyerProtocol implements MakerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerProtocol(BuyerAsMakerTrade trade) {
        super(trade);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MakerProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): these methods are duplicated with SellerAsMakerProtocol due to single inheritance

    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer,
                                       ErrorMessageHandler errorMessageHandler) {
        System.out.println(getClass().getCanonicalName() + ".handleInitTradeRequest()");
        synchronized (trade) {
            this.errorMessageHandler = errorMessageHandler;
            latchTrade();
            expect(phase(Trade.Phase.INIT)
                    .with(message)
                    .from(peer))
                    .setup(tasks(
                            ProcessInitTradeRequest.class,
                            //ApplyFilter.class, // TODO (woodser): these checks apply when maker signs availability request, but not here
                            //VerifyPeersAccountAgeWitness.class, // TODO (woodser): these checks apply after in multisig, means if rejected need to reimburse other's fee
                            MakerSendsInitTradeRequestIfUnreserved.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                unlatchTrade();
                                handleTaskRunnerSuccess(peer, message);
                            },
                            errorMessage -> {
                                handleError(errorMessage);
                                handleTaskRunnerFault(peer, message, errorMessage);
                            }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks();
            awaitTradeLatch();
        }
    }
    
    @Override
    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleInitMultisigRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), request);
            processModel.setTradeMessage(request);
            latchTrade();
            expect(anyPhase(Trade.Phase.INIT)
                    .with(request)
                    .from(sender))
                    .setup(tasks(
                            ProcessInitMultisigRequest.class,
                            SendSignContractRequestAfterMultisig.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            unlatchTrade();
                            handleTaskRunnerSuccess(sender, request);
                        },
                        errorMessage -> {
                            handleError(errorMessage);
                            handleTaskRunnerFault(sender, request, errorMessage);
                        })))
                    .executeTasks();
            awaitTradeLatch();
        }
    }
    
    @Override
    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleSignContractRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), message);
            processModel.setTradeMessage(message);
            latchTrade();
            expect(anyPhase(Trade.Phase.INIT)
                    .with(message)
                    .from(sender))
                    .setup(tasks(
                            // TODO (woodser): validate request
                            ProcessSignContractRequest.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            unlatchTrade();
                            handleTaskRunnerSuccess(sender, message);
                        },
                        errorMessage -> {
                            handleError(errorMessage);
                            handleTaskRunnerFault(sender, message, errorMessage);
                        })))
                    .executeTasks();
            awaitTradeLatch();
        }
    }

    @Override
    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleSignContractResponse()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), message);
            if (trade.getState() == State.CONTRACT_SIGNATURE_REQUESTED) {
                processModel.setTradeMessage(message);
                if (tradeLatch == null) latchTrade(); // may be initialized from previous message
                expect(state(Trade.State.CONTRACT_SIGNATURE_REQUESTED)
                        .with(message)
                        .from(sender))
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessSignContractResponse.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    unlatchTrade();
                                    handleTaskRunnerSuccess(sender, message);
                                },
                                errorMessage -> {
                                    handleError(errorMessage);
                                    handleTaskRunnerFault(sender, message, errorMessage);
                                }))
                        .withTimeout(TRADE_TIMEOUT)) // extend timeout
                        .executeTasks();
                awaitTradeLatch();
            } else {
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state == State.CONTRACT_SIGNATURE_REQUESTED) handleSignContractResponse(message, sender);
                });
            }
        }
    }
    
    @Override
    public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleDepositResponse()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), response);
            processModel.setTradeMessage(response);
            latchTrade();
            expect(state(Trade.State.CONTRACT_SIGNATURE_REQUESTED)
                    .with(response)
                    .from(sender)) // TODO (woodser): ensure this asserts sender == response.getSenderNodeAddress()
                    .setup(tasks(
                            // TODO (woodser): validate request
                            ProcessDepositResponse.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            unlatchTrade();
                            handleTaskRunnerSuccess(sender, response);
                        },
                        errorMessage -> {
                            handleError(errorMessage);
                            handleTaskRunnerFault(sender, response, errorMessage);
                        }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks();
            awaitTradeLatch();
        }
    }
    
    @Override
    public void handlePaymentAccountPayloadRequest(PaymentAccountPayloadRequest request, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handlePaymentAccountPayloadRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), request);
            if (trade.getState() == State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG) {
                processModel.setTradeMessage(request);
                if (tradeLatch == null) latchTrade(); // may be initialized from previous message
                expect(state(Trade.State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG)
                        .with(request)
                        .from(sender)) // TODO (woodser): ensure this asserts sender == response.getSenderNodeAddress()
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessPaymentAccountPayloadRequest.class,
                                MakerRemovesOpenOffer.class)
                        .using(new TradeTaskRunner(trade,
                            () -> {
                                stopTimeout();
                                unlatchTrade();
                                handleTaskRunnerSuccess(sender, request);
                            },
                            errorMessage -> {
                                handleError(errorMessage);
                                handleTaskRunnerFault(sender, request, errorMessage);
                            }))
                        .withTimeout(TRADE_TIMEOUT))
                        .executeTasks();
                awaitTradeLatch();
            } else {
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state == State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG) handlePaymentAccountPayloadRequest(request, sender);
                });
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which events we expect
    @Override
    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        super.onPaymentStarted(resultHandler, errorMessageHandler);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message Payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(PaymentReceivedMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }

    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return MakerVerifyTakerFeePayment.class;
    }

    // TODO (woodser): remove or ignore any unsupported requests

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void handle(DelayedPayoutTxSignatureRequest message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
                        MakerRemovesOpenOffer.class,
                        BuyerProcessDelayedPayoutTxSignatureRequest.class,
                        BuyerVerifiesPreparedDelayedPayoutTx.class,
                        BuyerSignsDelayedPayoutTx.class,
                        BuyerFinalizesDelayedPayoutTx.class,
                        BuyerSendsDelayedPayoutTxSignatureResponse.class)
                        .withTimeout(60))
                .executeTasks();
    }

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(DepositTxAndDelayedPayoutTxMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }
}
