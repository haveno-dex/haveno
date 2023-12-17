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

package haveno.core.trade.protocol;


import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.trade.BuyerAsTakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.TakerReserveTradeFunds;
import haveno.core.trade.protocol.tasks.TakerSendInitTradeRequestToArbitrator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsTakerProtocol extends BuyerProtocol implements TakerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        super(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onTakeOffer(TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
      System.out.println(getClass().getCanonicalName() + ".onTakeOffer()");
      new Thread(() -> {
          synchronized (trade) {
              latchTrade();
              this.tradeResultHandler = tradeResultHandler;
              this.errorMessageHandler = errorMessageHandler;
              expect(phase(Trade.Phase.INIT)
                      .with(TakerEvent.TAKE_OFFER)
                      .from(trade.getTradePeer().getNodeAddress()))
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
      }).start();
    }
}
