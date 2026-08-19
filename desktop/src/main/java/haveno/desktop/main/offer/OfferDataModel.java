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

package haveno.desktop.main.offer;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.model.ActivatableDataModel;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.function.Consumer;

/**
 * Domain for that UI element.
 * Note that the create offer domain has a deeper scope in the application domain
 * (TradeManager).  That model is just responsible for the domain specific parts displayed
 * needed in that UI element.
 */
public abstract class OfferDataModel extends ActivatableDataModel {
    @Getter
    protected final XmrWalletService xmrWalletService;
    protected final OpenOfferManager openOfferManager;
    protected final OfferUtil offerUtil;

    @Getter
    protected final BooleanProperty isXmrWalletFunded = new SimpleBooleanProperty();
    @Getter
    protected final ObjectProperty<BigInteger> totalToPay = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> unallocatedBalance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> availableBalance = new SimpleObjectProperty<>();
    @Getter
    protected final ObjectProperty<BigInteger> missingCoin = new SimpleObjectProperty<>(BigInteger.ZERO);
    @Getter
    protected final BooleanProperty showWalletFundedNotification = new SimpleBooleanProperty();
    @Getter
    protected BigInteger totalUnallocatedBalance;
    @Getter
    protected BigInteger totalAvailableBalance;
    protected XmrAddressEntry addressEntry;
    protected XmrBalanceListener balanceListener;
    protected boolean useSavingsWallet;
    protected boolean activated;
    private boolean fundedStateInitialized;

    public OfferDataModel(XmrWalletService xmrWalletService, OpenOfferManager openOfferManager, OfferUtil offerUtil) {
        this.xmrWalletService = xmrWalletService;
        this.openOfferManager = openOfferManager;
        this.offerUtil = offerUtil;
    }

    // fetch the funding address entry off thread, since creating a subaddress can block on a busy wallet
    protected void requestAddressEntry(String offerId, @Nullable Consumer<XmrAddressEntry> resultHandler) {
        ThreadUtils.submitToPool(() -> {
            try {
                XmrAddressEntry entry = xmrWalletService.getOrCreateAddressEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
                UserThread.execute(() -> {
                    if (addressEntry == null) {
                        addressEntry = entry;
                        balanceListener = new XmrBalanceListener(entry.getSubaddressIndex()) {
                            @Override
                            public void onBalanceChanged(BigInteger balance) {
                                updateBalances();
                            }
                        };
                        // register only while active, else an abandoned view would leak the listener
                        if (activated) {
                            xmrWalletService.addBalanceListener(balanceListener);
                            updateBalances();
                        }
                    }
                    if (resultHandler != null) resultHandler.accept(addressEntry);
                });
            } catch (Exception e) {
                log.warn("Error getting address entry for offer {}: {}\n", offerId, e.getMessage(), e);
                if (resultHandler != null) UserThread.execute(() -> resultHandler.accept(null));
            }
        });
    }

    protected void updateBalances() {
        BigInteger tradeWalletBalance = xmrWalletService.getBalanceForSubaddress(addressEntry.getSubaddressIndex());
        BigInteger tradeWalletAvailableBalance = xmrWalletService.getAvailableBalanceForSubaddress(addressEntry.getSubaddressIndex());
        BigInteger walletUnallocatedBalance = openOfferManager.getUnallocatedBalance();
        BigInteger walletAvailableBalance = xmrWalletService.getAvailableBalance();
        UserThread.await(() -> {
            if (useSavingsWallet) {
                totalUnallocatedBalance = walletUnallocatedBalance;
                totalAvailableBalance = walletAvailableBalance;
                if (totalToPay.get() != null) {
                    unallocatedBalance.set(totalToPay.get().min(totalUnallocatedBalance));
                    availableBalance.set(totalToPay.get().min(totalAvailableBalance));
                }
            } else {
                totalUnallocatedBalance = tradeWalletBalance;
                totalAvailableBalance = tradeWalletAvailableBalance;
                unallocatedBalance.set(tradeWalletBalance);
                availableBalance.set(tradeWalletAvailableBalance);
            }
        });

    }

    // update funded state, notifying when the wallet becomes funded except on the initial evaluation
    protected void updateFundedState(BigInteger balance) {
        boolean wasFunded = isXmrWalletFunded.get();
        isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPay.get(), balance));
        if (fundedStateInitialized && !wasFunded && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) showWalletFundedNotification.set(true);
        fundedStateInitialized = true;
    }

    public boolean hasTotalToPay() {
        return totalToPay.get() != null && totalToPay.get().compareTo(BigInteger.ZERO) > 0;
    }
}
