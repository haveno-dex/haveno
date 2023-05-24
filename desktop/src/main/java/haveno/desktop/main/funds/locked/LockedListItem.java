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

package haveno.desktop.main.funds.locked;

import haveno.core.locale.Res;
import haveno.core.trade.Trade;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.BalanceListener;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.desktop.util.DisplayUtils;
import javafx.scene.control.Label;
import lombok.Getter;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;

import javax.annotation.Nullable;

class LockedListItem {
    private final BalanceListener balanceListener;
    private final BtcWalletService btcWalletService;
    private final CoinFormatter formatter;

    @Getter
    private final Label balanceLabel;
    @Getter
    private final Trade trade;
    @Getter
    private final AddressEntry addressEntry;
    @Getter
    private final String addressString;
    @Nullable
    private final Address address;
    @Getter
    private Coin balance;
    @Getter
    private String balanceString;

    public LockedListItem(Trade trade,
                          AddressEntry addressEntry,
                          BtcWalletService btcWalletService,
                          CoinFormatter formatter) {
        this.trade = trade;
        this.addressEntry = addressEntry;
        this.btcWalletService = btcWalletService;
        this.formatter = formatter;

        throw new RuntimeException("Cannot listen to multisig deposits in xmr without exchanging multisig info");

//        if (trade.getDepositTx() != null && !trade.getDepositTx().getOutputs().isEmpty()) {
//            address = WalletService.getAddressFromOutput(trade.getDepositTx().getOutput(0));
//            addressString = address != null ? address.toString() : "";
//        } else {
//            address = null;
//            addressString = "";
//        }
//        balanceLabel = new AutoTooltipLabel();
//        balanceListener = new BalanceListener(address) {
//            @Override
//            public void onBalanceChanged(Coin balance, Transaction tx) {
//                updateBalance();
//            }
//        };
//        btcWalletService.addBalanceListener(balanceListener);
//        updateBalance();
    }

    LockedListItem() {
        this.trade = null;
        this.addressEntry = null;
        this.btcWalletService = null;
        this.formatter = null;
        addressString = null;
        address = null;
        balanceLabel = null;
        balanceListener = null;
    }

    public void cleanup() {
        btcWalletService.removeBalanceListener(balanceListener);
    }

    private void updateBalance() {
        balance = addressEntry.getCoinLockedInMultiSigAsCoin();
        balanceString = formatter.formatCoin(this.balance);
        balanceLabel.setText(balanceString);
    }

    public String getDetails() {
        return trade != null ?
                Res.get("funds.locked.locked", trade.getShortId()) :
                Res.get("shared.noDetailsAvailable");
    }

    public String getDateString() {
        return trade != null ?
                DisplayUtils.formatDateTime(trade.getDate()) :
                Res.get("shared.noDateAvailable");
    }
}
