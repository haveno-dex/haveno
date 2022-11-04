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

import javax.inject.Inject;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.math.BigInteger;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BalancePresentation {
    private static final BigInteger AU_PER_XMR = new BigInteger("1000000000000");

    @Getter
    private final StringProperty availableBalance = new SimpleStringProperty();
    @Getter
    private final StringProperty pendingBalance = new SimpleStringProperty();
    @Getter
    private final StringProperty reservedBalance = new SimpleStringProperty();

    @Inject
    public BalancePresentation(Balances balances) {
        balances.getAvailableBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> availableBalance.set(longToXmr(newValue.value)));
        });
        balances.getPendingBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> pendingBalance.set(longToXmr(newValue.value)));
        });
        balances.getReservedBalance().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> reservedBalance.set(longToXmr(newValue.value)));
        });
    }

    // TODO: truncate full precision with ellipses to not break layout?
    // TODO (woodser): formatting utils in monero-java
    private static String longToXmr(long amt) {
      BigInteger auAmt = BigInteger.valueOf(amt);
      BigInteger[] quotientAndRemainder = auAmt.divideAndRemainder(AU_PER_XMR);
      double decimalRemainder = quotientAndRemainder[1].doubleValue() / AU_PER_XMR.doubleValue();
      return quotientAndRemainder[0].doubleValue() + decimalRemainder + " XMR";
    }
}
