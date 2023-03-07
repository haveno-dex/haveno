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

package haveno.core.trade.protocol.tasks;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.filter.FilterManager;
import haveno.core.trade.Trade;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ApplyFilter extends TradeTask {
    public ApplyFilter(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            NodeAddress nodeAddress = checkNotNull(processModel.getTempTradePeerNodeAddress());
            
            FilterManager filterManager = processModel.getFilterManager();
            if (filterManager.isNodeAddressBanned(nodeAddress)) {
                failed("Other trader is banned by their node address.\n" +
                        "tradePeerNodeAddress=" + nodeAddress);
            } else if (filterManager.isOfferIdBanned(trade.getId())) {
                failed("Offer ID is banned.\n" +
                        "Offer ID=" + trade.getId());
            } else if (trade.getOffer() != null && filterManager.isCurrencyBanned(trade.getOffer().getCurrencyCode())) {
                failed("Currency is banned.\n" +
                        "Currency code=" + trade.getOffer().getCurrencyCode());
            } else if (filterManager.isPaymentMethodBanned(checkNotNull(trade.getOffer()).getPaymentMethod())) {
                failed("Payment method is banned.\n" +
                        "Payment method=" + trade.getOffer().getPaymentMethod().getId());
            } else if (filterManager.requireUpdateToNewVersionForTrading()) {
                failed("Your version of Haveno is not compatible for trading anymore. " +
                        "Please update to the latest Haveno version at https://haveno.network/downloads.");
            } else {
                complete();
            }
        } catch (Throwable t) {
            failed(t);
        }
    }
}

