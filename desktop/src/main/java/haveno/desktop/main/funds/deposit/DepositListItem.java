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

package haveno.desktop.main.funds.deposit;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import common.types.Filter;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.util.GUIUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Tooltip;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.List;

@Slf4j
class DepositListItem {
    private final StringProperty balance = new SimpleStringProperty();
    private final XmrAddressEntry addressEntry;
    private final XmrWalletService xmrWalletService;
    private BigInteger balanceAsBI;
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

    DepositListItem(XmrAddressEntry addressEntry, XmrWalletService xmrWalletService, CoinFormatter formatter, List<MoneroTxWallet> cachedTxs) {
        this.xmrWalletService = xmrWalletService;
        this.addressEntry = addressEntry;

        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                DepositListItem.this.balanceAsBI = balance;
                DepositListItem.this.balance.set(HavenoUtils.formatXmr(balanceAsBI));
                updateUsage(addressEntry.getSubaddressIndex(), null);
            }
        };
        xmrWalletService.addBalanceListener(balanceListener);

        balanceAsBI = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        balance.set(HavenoUtils.formatXmr(balanceAsBI));

        updateUsage(addressEntry.getSubaddressIndex(), cachedTxs);

        // confidence
        lazyFieldsSupplier = Suppliers.memoize(() -> new LazyFields() {{
            txConfidenceIndicator = new TxConfidenceIndicator();
            txConfidenceIndicator.setId("funds-confidence");
            tooltip = new Tooltip(Res.get("shared.notUsedYet"));
            txConfidenceIndicator.setProgress(0);
            txConfidenceIndicator.setTooltip(tooltip);
            MoneroTx tx = getTxWithFewestConfirmations(cachedTxs);
            if (tx == null) {
                txConfidenceIndicator.setVisible(false);
            } else {
                GUIUtil.updateConfidence(tx, tooltip, txConfidenceIndicator);
                txConfidenceIndicator.setVisible(true);
            }
        }});
    }

    private void updateUsage(int subaddressIndex, List<MoneroTxWallet> cachedTxs) {
        numTxOutputs = xmrWalletService.getNumTxOutputsForSubaddress(addressEntry.getSubaddressIndex(), cachedTxs);
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

    public BigInteger getBalanceAsBI() {
        return balanceAsBI;
    }

    public int getNumTxOutputs() {
        return numTxOutputs;
    }

    public long getNumConfirmationsSinceFirstUsed(List<MoneroTxWallet> incomingTxs) {
        MoneroTx tx = getTxWithFewestConfirmations(incomingTxs);
        return tx == null ? 0 : tx.getNumConfirmations();
    }

    private MoneroTxWallet getTxWithFewestConfirmations(List<MoneroTxWallet> incomingTxs) {

        // get txs with incoming transfers to subaddress
        MoneroTxQuery query = new MoneroTxQuery()
                .setTransferQuery(new MoneroTransferQuery()
                        .setIsIncoming(true)
                        .setSubaddressIndex(addressEntry.getSubaddressIndex()));
        List<MoneroTxWallet> txs  = incomingTxs == null ? xmrWalletService.getWallet().getTxs(query) : Filter.apply(query, incomingTxs);
        
        // get tx with fewest confirmations
        MoneroTxWallet highestTx = null;
        for (MoneroTxWallet tx : txs) if (highestTx == null || tx.getNumConfirmations() < highestTx.getNumConfirmations()) highestTx = tx;
        return highestTx;
    }
}
