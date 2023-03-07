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

package bisq.core.presentation;

import bisq.common.UserThread;
import bisq.core.btc.Balances;
import bisq.core.trade.HavenoUtils;

import javax.inject.Inject;

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
        balances.getAvailableBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> availableBalance.set(HavenoUtils.formatToXmrWithCode(newValue)));
        });
        balances.getPendingBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> pendingBalance.set(HavenoUtils.formatToXmrWithCode(newValue)));
        });
        balances.getReservedBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> reservedBalance.set(HavenoUtils.formatToXmrWithCode(newValue)));
        });
    }
}
