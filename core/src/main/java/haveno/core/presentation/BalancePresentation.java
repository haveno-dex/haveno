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

package haveno.core.presentation;

import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.core.api.model.XmrBalanceInfo;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.Balances;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BalancePresentation {

    @Getter
    private final StringProperty availableBalance = new SimpleStringProperty();
    @Getter
    private final StringProperty pendingBalance = new SimpleStringProperty();
    @Getter
    private final StringProperty reservedBalance = new SimpleStringProperty();

    @Inject
    public BalancePresentation(Balances balances) {
        balances.getUpdateCounter().addListener((observable, oldValue, newValue) -> {
            XmrBalanceInfo info = balances.getBalances();
            UserThread.execute(() -> {
                availableBalance.set(HavenoUtils.formatXmr(info.getAvailableBalance(), true));
                pendingBalance.set(HavenoUtils.formatXmr(info.getPendingBalance(), true));
                reservedBalance.set(HavenoUtils.formatXmr(info.getReservedBalance(), true));
            });
        });
    }
}
