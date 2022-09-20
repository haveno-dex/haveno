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
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.TakerReserveTradeFunds;
import bisq.core.trade.protocol.tasks.TakerSendInitTradeRequestToArbitrator;
import bisq.network.p2p.NodeAddress;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

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
    
    @Override
    public void onTakeOffer(TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
      System.out.println(getClass().getCanonicalName() + ".onTakeOffer()");
      synchronized (trade) {
          latchTrade();
          this.tradeResultHandler = tradeResultHandler;
          this.errorMessageHandler = errorMessageHandler;
          expect(phase(Trade.Phase.INIT)
                  .with(TakerEvent.TAKE_OFFER)
                  .from(trade.getTradingPeerNodeAddress()))
                  .setup(tasks(
                          ApplyFilter.class,
                          TakerReserveTradeFunds.class,
                          TakerSendInitTradeRequestToArbitrator.class)
                  .using(new TradeTaskRunner(trade,
                          () -> {
                              startTimeout(TRADE_TIMEOUT);
                              unlatchTrade();
                          },
                          errorMessage -> {
                              handleError(errorMessage);
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
    }
}
