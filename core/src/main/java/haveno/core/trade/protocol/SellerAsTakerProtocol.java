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


import haveno.common.ThreadUtils;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.trade.SellerAsTakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.ProcessInitTradeRequest;
import haveno.core.trade.protocol.tasks.TakerReserveTradeFunds;
import haveno.core.trade.protocol.tasks.TakerSendInitTradeRequestToArbitrator;
import haveno.core.trade.protocol.tasks.TakerSendInitTradeRequestToMaker;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerAsTakerProtocol extends SellerProtocol implements TakerProtocol {
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerProtocol(SellerAsTakerTrade trade) {
        super(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////
    
    @Override
    public void onTakeOffer(TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
        log.info(TradeProtocol.LOG_HIGHLIGHT + "onTakerOffer for {} {}", getClass().getSimpleName(), trade.getShortId());
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                latchTrade();
                this.tradeResultHandler = tradeResultHandler;
                this.errorMessageHandler = errorMessageHandler;
                expect(phase(Trade.Phase.INIT)
                        .with(TakerEvent.TAKE_OFFER)
                        .from(trade.getTradePeer().getNodeAddress()))
                        .setup(tasks(
                                ApplyFilter.class,
                                TakerReserveTradeFunds.class,
                                TakerSendInitTradeRequestToMaker.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    startTimeout();
                                    unlatchTrade();
                                },
                                errorMessage -> {
                                    handleError(errorMessage);
                                }))
                        .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer) {
        log.info(TradeProtocol.LOG_HIGHLIGHT + "handleInitTradeRequest() for {} {} from {}", trade.getClass().getSimpleName(), trade.getShortId(), peer);
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                latchTrade();
                expect(phase(Trade.Phase.INIT)
                        .with(message)
                        .from(peer))
                        .setup(tasks(
                                ApplyFilter.class,
                                ProcessInitTradeRequest.class,
                                TakerSendInitTradeRequestToArbitrator.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    startTimeout();
                                    handleTaskRunnerSuccess(peer, message);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(peer, message, errorMessage);
                                }))
                        .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }
}
