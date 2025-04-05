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

package haveno.desktop.main.funds.withdrawal;

import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipLabel;
import javafx.scene.control.Label;
import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;

class WithdrawalListItem {
    private final XmrBalanceListener balanceListener;
    private final Label balanceLabel;
    private final XmrAddressEntry addressEntry;
    private final XmrWalletService walletService;
    private final CoinFormatter formatter;
    private BigInteger balance;
    private final String addressString;
    @Setter
    @Getter
    private boolean isSelected;

    public WithdrawalListItem(XmrAddressEntry addressEntry, XmrWalletService walletService,
                              CoinFormatter formatter) {
        this.addressEntry = addressEntry;
        this.walletService = walletService;
        this.formatter = formatter;
        addressString = addressEntry.getAddressString();

        // balance
        balanceLabel = new AutoTooltipLabel();
        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateBalance();
            }
        };
        walletService.addBalanceListener(balanceListener);

        updateBalance();
    }

    public void cleanup() {
        walletService.removeBalanceListener(balanceListener);
    }

    private void updateBalance() {
        balance = walletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        if (balance != null) {
            UserThread.execute(() -> balanceLabel.setText(HavenoUtils.formatXmr(this.balance)));
        }
    }

    public final String getLabel() {
        if (addressEntry.isOpenOffer())
            return Res.getWithCol("shared.offerId") + " " + addressEntry.getShortOfferId();
        else if (addressEntry.isTradePayout())
            return Res.getWithCol("shared.tradeId") + " " + addressEntry.getShortOfferId();
        else if (addressEntry.getContext() == XmrAddressEntry.Context.ARBITRATOR)
            return Res.get("funds.withdrawal.arbitrationFee");
        else
            return "-";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WithdrawalListItem)) return false;

        WithdrawalListItem that = (WithdrawalListItem) o;

        return addressEntry.equals(that.addressEntry);
    }

    @Override
    public int hashCode() {
        return addressEntry.hashCode();
    }

    public XmrAddressEntry getAddressEntry() {
        return addressEntry;
    }

    public Label getBalanceLabel() {
        return balanceLabel;
    }

    public BigInteger getBalance() {
        return balance;
    }

    public String getAddressString() {
        return addressString;
    }
}
