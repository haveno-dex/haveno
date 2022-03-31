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


import bisq.core.offer.Offer;
import bisq.core.trade.SellerAsTakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InputsForDepositTxResponse;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessDepositResponse;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import bisq.core.trade.protocol.tasks.ProcessPaymentAccountPayloadRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractResponse;
import bisq.core.trade.protocol.tasks.SendSignContractRequestAfterMultisig;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.core.trade.protocol.tasks.seller.SellerCreatesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller.SellerSendDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.seller.SellerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller_as_taker.SellerAsTakerSignsDepositTx;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesInputsForDepositTxResponse;
import bisq.core.trade.protocol.tasks.taker.TakerPublishFeeTx;
import bisq.core.trade.protocol.tasks.taker.TakerReservesTradeFunds;
import bisq.core.trade.protocol.tasks.taker.TakerSendsInitTradeRequestToArbitrator;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;
import bisq.core.util.Validator;

import bisq.network.p2p.NodeAddress;
import java.util.concurrent.CountDownLatch;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO (woodser): remove unused request handling
@Slf4j
public class SellerAsTakerProtocol extends SellerProtocol implements TakerProtocol {
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerProtocol(SellerAsTakerTrade trade) {
        super(trade);
        Offer offer = checkNotNull(trade.getOffer());
        trade.getTradingPeer().setPubKeyRing(offer.getPubKeyRing());
        trade.setMakerPubKeyRing(offer.getPubKeyRing());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////
    
    // TODO (woodser): these methods are duplicated with BuyerAsTakerProtocol due to single inheritance

    @Override
    public void onTakeOffer(TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
      System.out.println(getClass().getCanonicalName() + ".onTakeOffer()");
      synchronized (trade) {
          this.tradeResultHandler = tradeResultHandler;
          this.errorMessageHandler = errorMessageHandler;
          CountDownLatch latch = new CountDownLatch(1);
          expect(phase(Trade.Phase.INIT)
                  .with(TakerEvent.TAKE_OFFER)
                  .from(trade.getTradingPeerNodeAddress()))
                  .setup(tasks(
                          ApplyFilter.class,
                          TakerReservesTradeFunds.class,
                          TakerSendsInitTradeRequestToArbitrator.class)
                  .using(new TradeTaskRunner(trade,
                          () -> {
                              latch.countDown();
                          },
                          errorMessage -> {
                              latch.countDown();
                              errorMessageHandler.handleErrorMessage(errorMessage);
                          }))
                  .withTimeout(TRADE_TIMEOUT))
                  .executeTasks();
          wait(latch);
      }
    }

    @Override
    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleInitMultisigRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), request);
            processModel.setTradeMessage(request);
            CountDownLatch latch = new CountDownLatch(1);
            expect(anyPhase(Trade.Phase.INIT)
                    .with(request)
                    .from(sender))
                    .setup(tasks(
                            ProcessInitMultisigRequest.class,
                            SendSignContractRequestAfterMultisig.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                latch.countDown();
                                handleTaskRunnerSuccess(sender, request);
                            },
                            errorMessage -> {
                                latch.countDown();
                                handleTaskRunnerFault(sender, request, errorMessage);
                                errorMessageHandler.handleErrorMessage(errorMessage);
                            }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks();
            wait(latch);
        }
    }

    @Override
    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleSignContractRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), message);
            processModel.setTradeMessage(message);
            CountDownLatch latch = new CountDownLatch(1);
            expect(anyPhase(Trade.Phase.INIT)
                    .with(message)
                    .from(sender))
                    .setup(tasks(
                            // TODO (woodser): validate request
                            ProcessSignContractRequest.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                latch.countDown();
                                handleTaskRunnerSuccess(sender, message);
                            },
                            errorMessage -> {
                                latch.countDown();
                                handleTaskRunnerFault(sender, message, errorMessage);
                                errorMessageHandler.handleErrorMessage(errorMessage);
                            }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks();
            wait(latch);
        }
    }

    @Override
    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handleSignContractResponse()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), message);
            if (trade.getState() == State.CONTRACT_SIGNATURE_REQUESTED) {
                processModel.setTradeMessage(message);
                CountDownLatch latch = new CountDownLatch(1);
                expect(state(Trade.State.CONTRACT_SIGNATURE_REQUESTED)
                        .with(message)
                        .from(sender))
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessSignContractResponse.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    latch.countDown();
                                    handleTaskRunnerSuccess(sender, message);
                                },
                                errorMessage -> {
                                    latch.countDown();
                                    handleTaskRunnerFault(sender, message, errorMessage);
                                    errorMessageHandler.handleErrorMessage(errorMessage);
                                }))
                        .withTimeout(TRADE_TIMEOUT))
                        .executeTasks();
                wait(latch);
            } else {
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state != State.CONTRACT_SIGNATURE_REQUESTED) return;
                    handleSignContractResponse(message, sender);
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
            CountDownLatch latch = new CountDownLatch(1);
            expect(state(Trade.State.CONTRACT_SIGNATURE_REQUESTED)
                    .with(response)
                    .from(sender))
                    .setup(tasks(
                            // TODO (woodser): validate request
                            ProcessDepositResponse.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                latch.countDown();
                                handleTaskRunnerSuccess(sender, response);
                            },
                            errorMessage -> {
                                latch.countDown();
                                handleTaskRunnerFault(sender, response, errorMessage);
                                errorMessageHandler.handleErrorMessage(errorMessage);
                            }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks();
            wait(latch);
        }
    }

    @Override
    public void handlePaymentAccountPayloadRequest(PaymentAccountPayloadRequest request, NodeAddress sender) {
        System.out.println(getClass().getCanonicalName() + ".handlePaymentAccountPayloadRequest()");
        synchronized (trade) {
            Validator.checkTradeId(processModel.getOfferId(), request);
            CountDownLatch latch = new CountDownLatch(1);
            if (trade.getState() == State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG) {
                processModel.setTradeMessage(request);
                expect(state(Trade.State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG) // TODO (woodser): rename to RECEIVED_DEPOSIT_TX_PUBLISHED_MSG
                        .with(request)
                        .from(sender)) // TODO (woodser): ensure this asserts sender == response.getSenderNodeAddress()
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessPaymentAccountPayloadRequest.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    latch.countDown();
                                    stopTimeout();
                                    handleTaskRunnerSuccess(sender, request);
                                    tradeResultHandler.handleResult(trade); // trade is initialized
                                },
                                errorMessage -> {
                                    latch.countDown();
                                    handleTaskRunnerFault(sender, request, errorMessage);
                                    errorMessageHandler.handleErrorMessage(errorMessage);
                                }))
                        .withTimeout(TRADE_TIMEOUT))
                        .executeTasks();
                wait(latch);
            } else {
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state == State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG) handlePaymentAccountPayloadRequest(request, sender);
                });
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message when buyer has clicked payment started button
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(PaymentSentMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which events we expect
    @Override
    public void onPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        super.onPaymentReceived(resultHandler, errorMessageHandler);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);

        log.info("Received {} from {} with tradeId {} and uid {}",
                message.getClass().getSimpleName(), peer, message.getTradeId(), message.getUid());

        if (message instanceof InputsForDepositTxResponse) {
            handle((InputsForDepositTxResponse) message, peer);
        }
    }

    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return TakerVerifyMakerFeePayment.class;
    }
    
    // TODO (woodser): remove unused calls and classes
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(InputsForDepositTxResponse message, NodeAddress peer) {
        expect(phase(Trade.Phase.INIT)
                .with(message)
                .from(peer))
                .setup(tasks(
                        TakerProcessesInputsForDepositTxResponse.class,
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        //TakerVerifyAndSignContract.class,
                        TakerPublishFeeTx.class,
                        SellerAsTakerSignsDepositTx.class,
                        SellerCreatesDelayedPayoutTx.class,
                        SellerSignsDelayedPayoutTx.class,
                        SellerSendDelayedPayoutTxSignatureRequest.class)
                        .withTimeout(60))
                .executeTasks();
    }
}
