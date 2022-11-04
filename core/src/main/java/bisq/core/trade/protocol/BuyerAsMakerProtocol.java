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

package bisq.core.trade.protocol;

import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.MakerSendInitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.network.p2p.NodeAddress;
import bisq.common.handlers.ErrorMessageHandler;
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
        System.out.println(getClass().getCanonicalName() + ".handleInitTradeRequest()");
        new Thread(() -> {
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
                                MakerSendInitTradeRequest.class)
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
        }).start();
    }
}
