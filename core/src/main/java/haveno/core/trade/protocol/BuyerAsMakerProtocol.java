/*
e * This file is part of Haveno.
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
import haveno.core.trade.BuyerAsMakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.MakerSendInitTradeRequestToArbitrator;
import haveno.core.trade.protocol.tasks.ProcessInitTradeRequest;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsMakerProtocol extends BuyerProtocol implements MakerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerProtocol(BuyerAsMakerTrade trade) {
        super(trade);
    }

    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer,
                                       ErrorMessageHandler errorMessageHandler) {
            log.info(TradeProtocol.LOG_HIGHLIGHT + "handleInitTradeRequest() for {} {} from {}", trade.getClass().getSimpleName(), trade.getShortId(), peer);
            ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                latchTrade();
                this.errorMessageHandler = errorMessageHandler;
                expect(phase(Trade.Phase.INIT)
                        .with(message)
                        .from(peer))
                        .setup(tasks(
                                ApplyFilter.class,
                                ProcessInitTradeRequest.class,
                                MakerSendInitTradeRequestToArbitrator.class)
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
