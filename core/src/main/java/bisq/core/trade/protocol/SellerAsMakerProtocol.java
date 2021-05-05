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


import bisq.core.trade.SellerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.CounterCurrencyTransferStartedMessage;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndPublishDepositTx;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndSignContract;
import bisq.core.trade.protocol.tasks.maker.MakerRemovesOpenOffer;
import bisq.core.trade.protocol.tasks.maker.MakerSendsInitTradeRequest;
import bisq.core.trade.protocol.tasks.maker.MakerSendsReadyToFundMultisigResponse;
import bisq.core.trade.protocol.tasks.maker.MakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerDepositTx;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.trade.protocol.tasks.seller.SellerCreatesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller.SellerSendDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.seller.SellerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerFinalizesDepositTx;
import bisq.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerProcessDepositTxMessage;
import bisq.core.util.Validator;

import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerAsMakerProtocol extends SellerProtocol implements MakerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsMakerProtocol(SellerAsMakerTrade trade) {
        super(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(DepositTxMessage message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
                        MakerRemovesOpenOffer.class,
                        SellerAsMakerProcessDepositTxMessage.class,
                        SellerAsMakerFinalizesDepositTx.class,
                        SellerCreatesDelayedPayoutTx.class,
                        SellerSignsDelayedPayoutTx.class,
                        SellerSendDelayedPayoutTxSignatureRequest.class)
                        .withTimeout(60))
                .executeTasks();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message when buyer has clicked payment started button
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(CounterCurrencyTransferStartedMessage message, NodeAddress peer) {
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

        if (message instanceof DepositTxMessage) {
            handle((DepositTxMessage) message, peer);
        }
    }

    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return MakerVerifyTakerFeePayment.class;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MakerProtocol  TODO (woodser): these methods are duplicated with SellerAsMakerProtocol due to single inheritance
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer,
                                       ErrorMessageHandler errorMessageHandler) {
        expect(phase(Trade.Phase.INIT)
            .with(message)
            .from(peer))
            .setup(tasks(
                    ProcessInitTradeRequest.class,
                    ApplyFilter.class,
                    VerifyPeersAccountAgeWitness.class,
                    MakerVerifyTakerFeePayment.class,
                    MakerSendsInitTradeRequest.class, // TODO (woodser): contact arbitrator here?  probably later when ready to create multisig
                    MakerRemovesOpenOffer.class,      // TODO (woodser): remove offer after taker pays trade fee or it needs to be reserved until deposit tx
                    MakerSendsReadyToFundMultisigResponse.class).
                    using(new TradeTaskRunner(trade,
                            () -> {
                              stopTimeout();
                              handleTaskRunnerSuccess(message);
                            },
                            errorMessage -> {
                                errorMessageHandler.handleErrorMessage(errorMessage);
                                handleTaskRunnerFault(message, errorMessage);
                            }))
                    .withTimeout(30))
            .executeTasks();
    }

    @Override
    public void handleMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigRequest message,
                                       NodeAddress sender,
                                       ErrorMessageHandler errorMessageHandler) {
      Validator.checkTradeId(processModel.getOfferId(), message);
      processModel.setTradeMessage(message);
      processModel.setTempTradingPeerNodeAddress(sender);

      expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
            .with(message)
            .from(sender))
            .setup(tasks(
                    MakerSendsReadyToFundMultisigResponse.class).
                    using(new TradeTaskRunner(trade,
                            () -> {
                              stopTimeout();
                              handleTaskRunnerSuccess(message);
                            },
                            errorMessage -> {
                                errorMessageHandler.handleErrorMessage(errorMessage);
                                handleTaskRunnerFault(message, errorMessage);
                            }))
                    .withTimeout(30))
            .executeTasks();
    }

    @Override
    public void handleDepositTxMessage(DepositTxMessage message,
                                      NodeAddress sender,
                                      ErrorMessageHandler errorMessageHandler) {
      Validator.checkTradeId(processModel.getOfferId(), message);
      processModel.setTradeMessage(message);
      processModel.setTempTradingPeerNodeAddress(sender);

      // TODO (woodser): MakerProcessesTakerDepositTxMessage.java which verifies deposit amount = fee + security deposit (+ trade amount), or that deposit is exact amount
      expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
            .with(message)
            .from(sender))
            .setup(tasks(
                    MakerVerifyTakerDepositTx.class,
                    MakerCreateAndSignContract.class,
                    MakerCreateAndPublishDepositTx.class,
                    MakerSetupDepositTxsListener.class).
                    using(new TradeTaskRunner(trade,
                            () -> handleTaskRunnerSuccess(message),
                            errorMessage -> {
                                errorMessageHandler.handleErrorMessage(errorMessage);
                                handleTaskRunnerFault(message, errorMessage);
                            })))
            .executeTasks();
    }
}
