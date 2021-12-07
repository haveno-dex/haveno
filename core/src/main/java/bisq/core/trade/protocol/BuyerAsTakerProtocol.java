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

package bisq.core.trade.protocol;


import bisq.core.offer.Offer;
import bisq.core.trade.BuyerAsTakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.DelayedPayoutTxSignatureRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InputsForDepositTxResponse;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
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
import bisq.core.trade.protocol.tasks.buyer.BuyerFinalizesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendsDelayedPayoutTxSignatureResponse;
import bisq.core.trade.protocol.tasks.buyer.BuyerSetupDepositTxListener;
import bisq.core.trade.protocol.tasks.buyer.BuyerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerVerifiesPreparedDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSendsDepositTxMessage;
import bisq.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSignsDepositTx;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesInputsForDepositTxResponse;
import bisq.core.trade.protocol.tasks.taker.TakerPublishFeeTx;
import bisq.core.trade.protocol.tasks.taker.TakerReservesTradeFunds;
import bisq.core.trade.protocol.tasks.taker.TakerSendsInitTradeRequestToArbitrator;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO (woodser): remove unused request handling
@Slf4j
public class BuyerAsTakerProtocol extends BuyerProtocol implements TakerProtocol {
    
    private TradeResultHandler tradeResultHandler;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        super(trade);
        Offer offer = checkNotNull(trade.getOffer());
        trade.getTradingPeer().setPubKeyRing(offer.getPubKeyRing());
        trade.setMakerPubKeyRing(offer.getPubKeyRing());

       // TODO (woodser): setup deposit and payout listeners on construction for startup like before rebase?
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): this implementation is duplicated with SellerAsTakerProtocol
    @Override
    public void onTakeOffer(TradeResultHandler tradeResultHandler, ErrorMessageHandler errorMessageHandler) {
      System.out.println("onTakeOffer()");
      this.tradeResultHandler = tradeResultHandler;

      expect(phase(Trade.Phase.INIT)
          .with(TakerEvent.TAKE_OFFER)
          .from(trade.getTradingPeerNodeAddress()))
          .setup(tasks(
              ApplyFilter.class,
              TakerReservesTradeFunds.class,
              TakerSendsInitTradeRequestToArbitrator.class) // TODO (woodser): app hangs if this pipeline fails. use .using() like below
              .using(new TradeTaskRunner(trade,
                      () -> { },
                      errorMessageHandler))
          .withTimeout(30))
          .executeTasks();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(InputsForDepositTxResponse message, NodeAddress peer) {
        expect(phase(Trade.Phase.INIT)
                .with(message)
                .from(peer))
                .setup(tasks(TakerProcessesInputsForDepositTxResponse.class,
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        //TakerVerifyAndSignContract.class,
                        TakerPublishFeeTx.class,
                        BuyerAsTakerSignsDepositTx.class,
                        BuyerSetupDepositTxListener.class,
                        BuyerAsTakerSendsDepositTxMessage.class)
                        .withTimeout(60))
                .executeTasks();
    }

    @Override
    protected void handle(DelayedPayoutTxSignatureRequest message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
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
    protected void handle(PayoutTxPublishedMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);

        if (message instanceof InputsForDepositTxResponse) {
            handle((InputsForDepositTxResponse) message, peer);
        }
    }

    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return TakerVerifyMakerFeePayment.class;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // TakerProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): these methods are duplicated with SellerAsTakerProtocol due to single inheritance

    @Override
    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
      System.out.println("BuyerAsTakerProtocol.handleInitMultisigRequest()");
      Validator.checkTradeId(processModel.getOfferId(), request);
      processModel.setTradeMessage(request);
      expect(anyPhase(Trade.Phase.INIT)
          .with(request)
          .from(sender))
          .setup(tasks(
              ProcessInitMultisigRequest.class,
              SendSignContractRequestAfterMultisig.class)
              .using(new TradeTaskRunner(trade,
                  () -> {
                    System.out.println("handle multisig pipeline completed successfully!");
                    handleTaskRunnerSuccess(sender, request);
                  },
                  errorMessage -> {
                      System.out.println("error in handle multisig pipeline!!!: " + errorMessage);
                      errorMessageHandler.handleErrorMessage(errorMessage);
                      handleTaskRunnerFault(sender, request, errorMessage);
                  }))
          .withTimeout(30))
          .executeTasks();
    }
    
    @Override
    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("SellerAsTakerProtocol.handleSignContractRequest()");
        Validator.checkTradeId(processModel.getOfferId(), message);
        processModel.setTradeMessage(message);
        expect(anyPhase(Trade.Phase.INIT)
            .with(message)
            .from(sender))
            .setup(tasks(
                    // TODO (woodser): validate request
                    ProcessSignContractRequest.class)
                .using(new TradeTaskRunner(trade,
                    () -> {
                      handleTaskRunnerSuccess(sender, message);
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, message, errorMessage);
                    }))
            .withTimeout(30))
            .executeTasks();
    }
    
    @Override
    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("SellerAsTakerProtocol.handleSignContractResponse()");
        Validator.checkTradeId(processModel.getOfferId(), message);
        processModel.setTradeMessage(message);
        expect(anyPhase(Trade.Phase.INIT)
            .with(message)
            .from(sender))
            .setup(tasks(
                    // TODO (woodser): validate request
                    ProcessSignContractResponse.class)
                .using(new TradeTaskRunner(trade,
                    () -> {
                      handleTaskRunnerSuccess(sender, message);
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, message, errorMessage);
                    })))
            .executeTasks();
    }
    
    @Override
    public void handleDepositResponse(DepositResponse response, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("SellerAsTakerProtocol.handleDepositResponse()");
        Validator.checkTradeId(processModel.getOfferId(), response);
        processModel.setTradeMessage(response);
        expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_PUBLISHED)
            .with(response)
            .from(sender))
            .setup(tasks(
                    // TODO (woodser): validate request
                    ProcessDepositResponse.class)
                .using(new TradeTaskRunner(trade,
                    () -> {
                      handleTaskRunnerSuccess(sender, response);
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, response, errorMessage);
                    })))
            .executeTasks();
    }
    
    @Override
    public void handlePaymentAccountPayloadRequest(PaymentAccountPayloadRequest request, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("SellerAsTakerProtocol.handlePaymentAccountPayloadRequest()");
        Validator.checkTradeId(processModel.getOfferId(), request);
        processModel.setTradeMessage(request);
        expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_PUBLISHED)
            .with(request)
            .from(sender)) // TODO (woodser): ensure this asserts sender == response.getSenderNodeAddress()
            .setup(tasks(
                    // TODO (woodser): validate request
                    ProcessPaymentAccountPayloadRequest.class)
            .using(new TradeTaskRunner(trade,
                    () -> {
                        stopTimeout();
                        handleTaskRunnerSuccess(sender, request);
                        tradeResultHandler.handleResult(trade); // trade is initialized
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, request, errorMessage);
                    })))
            .executeTasks();
    }
}
