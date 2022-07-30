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


import bisq.core.trade.SellerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.maker.MaybeRemoveOpenOffer;
import bisq.core.trade.protocol.tasks.maker.MakerSendsInitTradeRequestIfUnreserved;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.trade.protocol.tasks.seller.SellerCreatesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller.SellerSendDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.seller.SellerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerFinalizesDepositTx;
import bisq.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerProcessDepositTxMessage;
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
    // MakerProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////
    
    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer,
                                       ErrorMessageHandler errorMessageHandler) {
        System.out.println(getClass().getCanonicalName() + ".handleInitTradeRequest()");
        synchronized (trade) {
            latchTrade();
            this.errorMessageHandler = errorMessageHandler;
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
                                startTimeout(TRADE_TIMEOUT);
                                handleTaskRunnerSuccess(peer, message);
                            },
                            errorMessage -> {
                                handleTaskRunnerFault(peer, message, errorMessage);
                            }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }

    @Override
    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        super.handleInitMultisigRequest(request, sender);
    }

    @Override
    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
        super.handleSignContractRequest(message, sender);
    }

    @Override
    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
        super.handleSignContractResponse(message, sender);
    }

    @Override
    public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
        super.handleDepositResponse(response, sender);
    }

    @Override
    public void handlePaymentAccountPayloadRequest(PaymentAccountPayloadRequest request, NodeAddress sender) {
        super.handlePaymentAccountPayloadRequest(request, sender);
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

    // TODO (woodser): remove unused

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(DepositTxMessage message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
                        MaybeRemoveOpenOffer.class,
                        SellerAsMakerProcessDepositTxMessage.class,
                        SellerAsMakerFinalizesDepositTx.class,
                        SellerCreatesDelayedPayoutTx.class,
                        SellerSignsDelayedPayoutTx.class,
                        SellerSendDelayedPayoutTxSignatureRequest.class)
                .withTimeout(60))
                .executeTasks(true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message when buyer has clicked payment started button
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(PaymentSentMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }
}
