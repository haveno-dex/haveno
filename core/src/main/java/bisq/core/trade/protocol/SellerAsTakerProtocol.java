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


import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.core.offer.Offer;
import bisq.core.trade.SellerAsTakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.CounterCurrencyTransferStartedMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.seller.SellerProcessCounterCurrencyTransferStartedMessage;
import bisq.core.trade.protocol.tasks.seller.SellerSendPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.seller.SellerSignAndPublishPayoutTx;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

// TODO (woodser): most of this file is duplicated with SellerAsMakerProtocol due to lack of multiple inheritance
@Slf4j
public class SellerAsTakerProtocol extends TakerProtocolBase implements SellerProtocol {
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerProtocol(SellerAsTakerTrade trade) {
        super(trade);
        Offer offer = checkNotNull(trade.getOffer());
        processModel.getTradingPeer().setPubKeyRing(offer.getPubKeyRing());
    }
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMailboxMessage(TradeMessage tradeMessage, NodeAddress peerNodeAddress) {
        super.onMailboxMessage(tradeMessage, peerNodeAddress);

        if (tradeMessage instanceof CounterCurrencyTransferStartedMessage) {
            handle((CounterCurrencyTransferStartedMessage) tradeMessage, peerNodeAddress);
        }
    }
    
    @Override
    protected void onTradeMessage(TradeMessage tradeMessage, NodeAddress sender) {
        super.onTradeMessage(tradeMessage, sender);

        log.info("Received {} from {} with tradeId {} and uid {}",
                tradeMessage.getClass().getSimpleName(), sender, tradeMessage.getTradeId(), tradeMessage.getUid());

        if (tradeMessage instanceof CounterCurrencyTransferStartedMessage) {
            handle((CounterCurrencyTransferStartedMessage) tradeMessage, sender);
        }
    }
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // After peer has started Fiat tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(CounterCurrencyTransferStartedMessage message, NodeAddress sender) {
      processModel.setTradeMessage(message);
      processModel.setTempTradingPeerNodeAddress(sender);
      expect(anyPhase(Trade.Phase.DEPOSIT_CONFIRMED)
          .with(message)
          .from(sender))
          .setup(tasks(
              SellerProcessCounterCurrencyTransferStartedMessage.class,
              getVerifyPeersFeePaymentClass())
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

    @Override
    public void onPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
      SellerEvent event = SellerEvent.PAYMENT_RECEIVED;
      expect(anyPhase(Trade.Phase.FIAT_SENT, Trade.Phase.PAYOUT_PUBLISHED)
              .with(event)
              .preCondition(trade.confirmPermitted()))
              .setup(tasks(
                      ApplyFilter.class,
                      getVerifyPeersFeePaymentClass(),
                      SellerSignAndPublishPayoutTx.class,
                      //SellerSignAndFinalizePayoutTx.class,
                      //SellerBroadcastPayoutTx.class,
                      SellerSendPayoutTxPublishedMessage.class)
                      .using(new TradeTaskRunner(trade,
                              () -> {
                                  resultHandler.handleResult();
                                  handleTaskRunnerSuccess(event);
                              },
                              (errorMessage) -> {
                                  errorMessageHandler.handleErrorMessage(errorMessage);
                                  handleTaskRunnerFault(event, errorMessage);
                              })))
              .run(() -> trade.setState(Trade.State.SELLER_CONFIRMED_IN_UI_FIAT_PAYMENT_RECEIPT))
              .executeTasks();
  }
}
