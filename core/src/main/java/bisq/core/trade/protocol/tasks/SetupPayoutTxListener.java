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

import bisq.common.UserThread;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
public class SetupPayoutTxListener extends TradeTask {

    private Subscription tradeStateSubscription;

    @SuppressWarnings({ "unused" })
    public SetupPayoutTxListener(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if payout already published
            if (!trade.isPayoutPublished()) {

                // listen for payout tx
                trade.listenForPayoutTx();
                tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), newValue -> {
                    if (trade.isPayoutPublished()) {

                        // cleanup on trade completion
                        processModel.getXmrWalletService().resetAddressEntriesForPendingTrade(trade.getId());
                        UserThread.execute(this::unSubscribe); // unsubscribe
                    }
                });
            }

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void unSubscribe() {
        if (tradeStateSubscription != null) tradeStateSubscription.unsubscribe();
    }
}
