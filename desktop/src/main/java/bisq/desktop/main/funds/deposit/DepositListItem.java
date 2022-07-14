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

package bisq.desktop.main.funds.deposit;

import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.util.ParsingUtils;
import bisq.core.util.coin.CoinFormatter;
import java.math.BigInteger;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

@Slf4j
class DepositListItem {
    private final StringProperty balance = new SimpleStringProperty();
    private final XmrAddressEntry addressEntry;
    private final XmrWalletService xmrWalletService;
    private Coin balanceAsCoin;
    private String usage = "-";
    private XmrBalanceListener balanceListener;
    private int numTxOutputs = 0;

    DepositListItem(XmrAddressEntry addressEntry, XmrWalletService xmrWalletService, CoinFormatter formatter) {
        this.xmrWalletService = xmrWalletService;
        this.addressEntry = addressEntry;

        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                DepositListItem.this.balanceAsCoin = ParsingUtils.atomicUnitsToCoin(balance);
                DepositListItem.this.balance.set(formatter.formatCoin(balanceAsCoin));
                updateUsage(addressEntry.getSubaddressIndex());
            }
        };
        xmrWalletService.addBalanceListener(balanceListener);

        balanceAsCoin = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex()); // TODO: Coin represents centineros everywhere, but here it's atomic units. reconcile
        balanceAsCoin = Coin.valueOf(ParsingUtils.atomicUnitsToCentineros(balanceAsCoin.longValue())); // in centineros
        balance.set(formatter.formatCoin(balanceAsCoin));

        updateUsage(addressEntry.getSubaddressIndex());
    }

    private void updateUsage(int subaddressIndex) {
        numTxOutputs = xmrWalletService.getNumTxOutputsForSubaddress(addressEntry.getSubaddressIndex());
        usage = numTxOutputs == 0 ? Res.get("funds.deposit.unused") : Res.get("funds.deposit.usedInTx", numTxOutputs);
    }

    public void cleanup() {
        xmrWalletService.removeBalanceListener(balanceListener);
    }

    public String getAddressString() {
        return addressEntry.getAddressString();
    }

    public String getUsage() {
        return usage;
    }

    public final StringProperty balanceProperty() {
        return this.balance;
    }

    public String getBalance() {
        return balance.get();
    }

    public Coin getBalanceAsCoin() {
        return balanceAsCoin;
    }

    public int getNumTxOutputs() {
        return numTxOutputs;
    }

    public int getNumConfirmationsSinceFirstUsed() {
        throw new RuntimeException("Not implemented");
    }
}
