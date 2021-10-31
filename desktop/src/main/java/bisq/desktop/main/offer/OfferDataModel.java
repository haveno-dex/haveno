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

package bisq.desktop.main.offer;

import bisq.desktop.common.model.ActivatableDataModel;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OfferUtil;

import org.bitcoinj.core.Coin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import lombok.Getter;

import static bisq.core.util.coin.CoinUtil.minCoin;

/**
 * Domain for that UI element.
 * Note that the create offer domain has a deeper scope in the application domain
 * (TradeManager).  That model is just responsible for the domain specific parts displayed
 * needed in that UI element.
 */
public abstract class OfferDataModel extends ActivatableDataModel {
    protected final XmrWalletService xmrWalletService;
    protected final OfferUtil offerUtil;

    @Getter
    protected final BooleanProperty isBtcWalletFunded = new SimpleBooleanProperty();
    @Getter
    protected final ObjectProperty<Coin> totalToPayAsCoin = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<Coin> balance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<Coin> missingCoin = new SimpleObjectProperty<>(Coin.ZERO);
    @Getter
    protected final BooleanProperty showWalletFundedNotification = new SimpleBooleanProperty();
    @Getter
    protected Coin totalAvailableBalance;
    protected XmrAddressEntry addressEntry;
    protected boolean useSavingsWallet;

    public OfferDataModel(XmrWalletService xmrWalletService, OfferUtil offerUtil) {
        this.xmrWalletService = xmrWalletService;
        this.offerUtil = offerUtil;
    }

    protected void updateBalance() {
        Coin tradeWalletBalance = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        if (useSavingsWallet) {
            Coin savingWalletBalance = xmrWalletService.getSavingWalletBalance();
            totalAvailableBalance = savingWalletBalance.add(tradeWalletBalance);
            if (totalToPayAsCoin.get() != null) {
                balance.set(minCoin(totalToPayAsCoin.get(), totalAvailableBalance));
            }
        } else {
            balance.set(tradeWalletBalance);
        }
        missingCoin.set(offerUtil.getBalanceShortage(totalToPayAsCoin.get(), balance.get()));
        isBtcWalletFunded.set(offerUtil.isBalanceSufficient(totalToPayAsCoin.get(), balance.get()));
        if (totalToPayAsCoin.get() != null && isBtcWalletFunded.get() && !showWalletFundedNotification.get()) {
            showWalletFundedNotification.set(true);
        }
    }
}
