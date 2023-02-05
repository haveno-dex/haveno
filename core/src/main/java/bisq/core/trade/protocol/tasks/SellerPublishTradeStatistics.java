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

package bisq.core.trade.protocol.tasks;

import bisq.core.trade.Trade;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerPublishTradeStatistics extends TradeTask {
    public SellerPublishTradeStatistics(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        throw new RuntimeException("SellerPublishesTradeStatistics needs updated for XMR");
//        try {
//            runInterceptHook();
//
//            checkNotNull(trade.getDepositTx());
//
//            processModel.getP2PService().findPeersCapabilities(trade.getTradePeer().getNodeAddress())
//                    .filter(capabilities -> capabilities.containsAll(Capability.TRADE_STATISTICS_3))
//                    .ifPresentOrElse(capabilities -> {
//                                // Our peer has updated, so as we are the seller we will publish the trade statistics.
//                                // The peer as buyer does not publish anymore with v.1.4.0 (where Capability.TRADE_STATISTICS_3 was added)
//
//                                String referralId = processModel.getReferralIdService().getOptionalReferralId().orElse(null);
//                                boolean isTorNetworkNode = model.getProcessModel().getP2PService().getNetworkNode() instanceof TorNetworkNode;
//                                TradeStatistics3 tradeStatistics = TradeStatistics3.from(trade, referralId, isTorNetworkNode);
//                                if (tradeStatistics.isValid()) {
//                                    log.info("Publishing trade statistics");
//                                    processModel.getP2PService().addPersistableNetworkPayload(tradeStatistics, true);
//                                } else {
//                                    log.warn("Trade statistics are invalid. We do not publish. {}", tradeStatistics);
//                                }
//
//                                complete();
//                            },
//                            () -> {
//                                log.info("Our peer does not has updated yet, so they will publish the trade statistics. " +
//                                        "To avoid duplicates we do not publish from our side.");
//                                complete();
//                            });
//        } catch (Throwable t) {
//            failed(t);
//        }
    }
}
