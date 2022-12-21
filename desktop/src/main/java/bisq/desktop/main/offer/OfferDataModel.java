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
    @Getter
    protected final XmrWalletService xmrWalletService;
    protected final OfferUtil offerUtil;

    @Getter
    protected final BooleanProperty isXmrWalletFunded = new SimpleBooleanProperty();
    @Getter
    protected final ObjectProperty<Coin> totalToPayAsCoin = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<Coin> balance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<Coin> availableBalance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<Coin> missingCoin = new SimpleObjectProperty<>(Coin.ZERO);
    @Getter
    protected final BooleanProperty showWalletFundedNotification = new SimpleBooleanProperty();
    @Getter
    protected Coin totalBalance;
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
            Coin walletBalance = xmrWalletService.getBalance();
            totalBalance = walletBalance.add(tradeWalletBalance);
            if (totalToPayAsCoin.get() != null) {
                balance.set(minCoin(totalToPayAsCoin.get(), totalBalance));
            }
        } else {
            balance.set(tradeWalletBalance);
        }
        missingCoin.set(offerUtil.getBalanceShortage(totalToPayAsCoin.get(), balance.get()));
        isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPayAsCoin.get(), balance.get()));
        if (totalToPayAsCoin.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
            showWalletFundedNotification.set(true);
        }
    }

    protected void updateAvailableBalance() {
        Coin tradeWalletBalance = xmrWalletService.getAvailableBalanceForSubaddress(addressEntry.getSubaddressIndex());
        if (useSavingsWallet) {
            Coin walletAvailableBalance = xmrWalletService.getAvailableBalance();
            totalAvailableBalance = walletAvailableBalance.add(tradeWalletBalance);
            if (totalToPayAsCoin.get() != null) {
                availableBalance.set(minCoin(totalToPayAsCoin.get(), totalAvailableBalance));
            }
        } else {
            availableBalance.set(tradeWalletBalance);
        }
        missingCoin.set(offerUtil.getBalanceShortage(totalToPayAsCoin.get(), availableBalance.get()));
        isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPayAsCoin.get(), availableBalance.get()));
        if (totalToPayAsCoin.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
            showWalletFundedNotification.set(true);
        }
    }
}
