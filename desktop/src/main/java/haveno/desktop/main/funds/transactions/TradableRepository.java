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

package haveno.desktop.main.funds.transactions;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.core.offer.OpenOfferManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import java.util.Set;

@Singleton
public class TradableRepository {
    private final OpenOfferManager openOfferManager;
    private final TradeManager tradeManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;

    @Inject
    TradableRepository(OpenOfferManager openOfferManager,
                       TradeManager tradeManager,
                       ClosedTradableManager closedTradableManager,
                       FailedTradesManager failedTradesManager) {
        this.openOfferManager = openOfferManager;
        this.tradeManager = tradeManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
    }

    Set<Tradable> getAll() {
        return ImmutableSet.<Tradable>builder()
                .addAll(openOfferManager.getObservableList())
                .addAll(tradeManager.getObservableList())
                .addAll(closedTradableManager.getObservableList())
                .addAll(failedTradesManager.getObservableList())
                .build();
    }
}
