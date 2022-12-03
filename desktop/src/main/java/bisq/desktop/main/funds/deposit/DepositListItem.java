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
import bisq.core.trade.HavenoUtils;
import bisq.core.util.coin.CoinFormatter;
import bisq.desktop.components.indicator.TxConfidenceIndicator;
import bisq.desktop.util.GUIUtil;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.math.BigInteger;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Tooltip;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
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
    private final Supplier<LazyFields> lazyFieldsSupplier;

    private static class LazyFields {
        TxConfidenceIndicator txConfidenceIndicator;
        Tooltip tooltip;
    }

    private LazyFields lazy() {
        return lazyFieldsSupplier.get();
    }

    DepositListItem(XmrAddressEntry addressEntry, XmrWalletService xmrWalletService, CoinFormatter formatter) {
        this.xmrWalletService = xmrWalletService;
        this.addressEntry = addressEntry;

        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                DepositListItem.this.balanceAsCoin = HavenoUtils.atomicUnitsToCoin(balance);
                DepositListItem.this.balance.set(formatter.formatCoin(balanceAsCoin));
                updateUsage(addressEntry.getSubaddressIndex());
            }
        };
        xmrWalletService.addBalanceListener(balanceListener);

        balanceAsCoin = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        balance.set(formatter.formatCoin(balanceAsCoin));

        updateUsage(addressEntry.getSubaddressIndex());

        // confidence
        lazyFieldsSupplier = Suppliers.memoize(() -> new LazyFields() {{
            txConfidenceIndicator = new TxConfidenceIndicator();
            txConfidenceIndicator.setId("funds-confidence");
            tooltip = new Tooltip(Res.get("shared.notUsedYet"));
            txConfidenceIndicator.setProgress(0);
            txConfidenceIndicator.setTooltip(tooltip);
            MoneroTx tx = getTxWithFewestConfirmations();
            if (tx == null) {
                txConfidenceIndicator.setVisible(false);
            } else {
                GUIUtil.updateConfidence(tx, tooltip, txConfidenceIndicator);
                txConfidenceIndicator.setVisible(true);
            }
        }});
    }

    private void updateUsage(int subaddressIndex) {
        numTxOutputs = xmrWalletService.getNumTxOutputsForSubaddress(addressEntry.getSubaddressIndex());
        usage = numTxOutputs == 0 ? Res.get("funds.deposit.unused") : Res.get("funds.deposit.usedInTx", numTxOutputs);
    }

    public void cleanup() {
        xmrWalletService.removeBalanceListener(balanceListener);
    }

    public TxConfidenceIndicator getTxConfidenceIndicator() {
        return lazy().txConfidenceIndicator;
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

    public long getNumConfirmationsSinceFirstUsed() {
        MoneroTx tx = getTxWithFewestConfirmations();
        return tx == null ? 0 : tx.getNumConfirmations();
    }
    
    private MoneroTxWallet getTxWithFewestConfirmations() {
        
        // get txs with incoming transfers to subaddress
        List<MoneroTxWallet> txs = xmrWalletService.getWallet()
                .getTxs(new MoneroTxQuery()
                        .setTransferQuery(new MoneroTransferQuery()
                                .setIsIncoming(true)
                                .setSubaddressIndex(addressEntry.getSubaddressIndex())));
        
        // get tx with fewest confirmations
        MoneroTxWallet highestTx = null;
        for (MoneroTxWallet tx : txs) if (highestTx == null || tx.getNumConfirmations() < highestTx.getNumConfirmations()) highestTx = tx;
        return highestTx;
    }
}
