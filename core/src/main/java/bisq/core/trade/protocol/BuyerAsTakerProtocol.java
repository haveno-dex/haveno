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


import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.core.offer.Offer;
import bisq.core.trade.BuyerAsTakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.UpdateMultisigWithTradingPeer;
import bisq.core.trade.protocol.tasks.buyer.BuyerCreateAndSignPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendCounterCurrencyTransferStartedMessage;
import bisq.core.trade.protocol.tasks.buyer.BuyerSetupPayoutTxListener;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class BuyerAsTakerProtocol extends TakerProtocolBase implements BuyerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        super(trade);

        Offer offer = checkNotNull(trade.getOffer());
        processModel.getTradingPeer().setPubKeyRing(offer.getPubKeyRing());

        // TODO (woodser): setup deposit and payout listeners on construction for startup like before rebase?
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMailboxMessage(TradeMessage tradeMessage, NodeAddress peerNodeAddress) {
        super.onMailboxMessage(tradeMessage, peerNodeAddress);

        if (tradeMessage instanceof PayoutTxPublishedMessage) {
            handle((PayoutTxPublishedMessage) tradeMessage, peerNodeAddress);
        }
    }

    @Override
    protected void onTradeMessage(TradeMessage tradeMessage, NodeAddress sender) {
        super.onTradeMessage(tradeMessage, sender);

        log.info("Received {} from {} with tradeId {} and uid {}",
                tradeMessage.getClass().getSimpleName(), sender, tradeMessage.getTradeId(), tradeMessage.getUid());

        if (tradeMessage instanceof PayoutTxPublishedMessage) {
            handle((PayoutTxPublishedMessage) tradeMessage, sender);
        }
    }

    private void handle(PayoutTxPublishedMessage message, NodeAddress sender) {
        processModel.setTradeMessage(message);
        processModel.setTempTradingPeerNodeAddress(sender);
        expect(anyPhase(Trade.Phase.FIAT_SENT)
                .with(message)
                .from(sender))
                .setup(tasks(
                        getVerifyPeersFeePaymentClass(),
                        BuyerProcessPayoutTxPublishedMessage.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    handleTaskRunnerSuccess(message);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(message, errorMessage);
                                })))
                .executeTasks();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): use common implementation with BuyerAsMakerProtocol? can't because of multi-inheritance limitation
    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        BuyerEvent event = BuyerEvent.PAYMENT_SENT;
        expect(phase(Trade.Phase.DEPOSIT_CONFIRMED)
                .with(event)
                .preCondition(trade.confirmPermitted()))
                .setup(tasks(ApplyFilter.class,
                        getVerifyPeersFeePaymentClass(),
                        UpdateMultisigWithTradingPeer.class,
                        BuyerCreateAndSignPayoutTx.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendCounterCurrencyTransferStartedMessage.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    resultHandler.handleResult();
                                    handleTaskRunnerSuccess(event);
                                },
                                (errorMessage) -> {
                                    errorMessageHandler.handleErrorMessage(errorMessage);
                                    handleTaskRunnerFault(event, errorMessage);
                                })))
                .run(() -> trade.setState(Trade.State.BUYER_CONFIRMED_IN_UI_FIAT_PAYMENT_INITIATED))
                .executeTasks();
    }
}
