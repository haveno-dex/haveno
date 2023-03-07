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

package haveno.desktop.main.offer;

import haveno.core.btc.model.XmrAddressEntry;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.offer.OfferUtil;
import haveno.desktop.common.model.ActivatableDataModel;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import lombok.Getter;

import java.math.BigInteger;

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
    protected final ObjectProperty<BigInteger> totalToPay = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> balance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> availableBalance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> missingCoin = new SimpleObjectProperty<>(BigInteger.valueOf(0));
    @Getter
    protected final BooleanProperty showWalletFundedNotification = new SimpleBooleanProperty();
    @Getter
    protected BigInteger totalBalance;
    @Getter
    protected BigInteger totalAvailableBalance;
    protected XmrAddressEntry addressEntry;
    protected boolean useSavingsWallet;

    public OfferDataModel(XmrWalletService xmrWalletService, OfferUtil offerUtil) {
        this.xmrWalletService = xmrWalletService;
        this.offerUtil = offerUtil;
    }

    protected void updateBalance() {
        BigInteger tradeWalletBalance = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        if (useSavingsWallet) {
            BigInteger walletBalance = xmrWalletService.getBalance();
            totalBalance = walletBalance.add(tradeWalletBalance);
            if (totalToPay.get() != null) {
                balance.set(totalToPay.get().min(totalBalance));
            }
        } else {
            balance.set(tradeWalletBalance);
        }
        missingCoin.set(offerUtil.getBalanceShortage(totalToPay.get(), balance.get()));
        isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPay.get(), balance.get()));
        if (totalToPay.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
            showWalletFundedNotification.set(true);
        }
    }

    protected void updateAvailableBalance() {
        BigInteger tradeWalletBalance = xmrWalletService.getAvailableBalanceForSubaddress(addressEntry.getSubaddressIndex());
        if (useSavingsWallet) {
            BigInteger walletAvailableBalance = xmrWalletService.getAvailableBalance();
            totalAvailableBalance = walletAvailableBalance.add(tradeWalletBalance);
            if (totalToPay.get() != null) {
                availableBalance.set(totalToPay.get().min(totalAvailableBalance));
            }
        } else {
            availableBalance.set(tradeWalletBalance);
        }
        missingCoin.set(offerUtil.getBalanceShortage(totalToPay.get(), availableBalance.get()));
        isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPay.get(), availableBalance.get()));
        if (totalToPay.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
            showWalletFundedNotification.set(true);
        }
    }
}
