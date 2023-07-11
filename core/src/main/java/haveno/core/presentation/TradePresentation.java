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

package haveno.core.presentation;

import haveno.common.UserThread;
import haveno.core.trade.TradeManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;

import javax.inject.Inject;

public class TradePresentation {
    @Getter
    private final StringProperty numPendingTrades = new SimpleStringProperty();
    @Getter
    private final BooleanProperty showPendingTradesNotification = new SimpleBooleanProperty();

    @Inject
    public TradePresentation(TradeManager tradeManager) {
        tradeManager.getNumPendingTrades().addListener((observable, oldValue, newValue) -> {
            long numPendingTrades = (long) newValue;
            UserThread.execute(() -> {
                if (numPendingTrades > 0)
                    this.numPendingTrades.set(String.valueOf(numPendingTrades));

                showPendingTradesNotification.set(numPendingTrades > 0);
            });
        });
    }
}
