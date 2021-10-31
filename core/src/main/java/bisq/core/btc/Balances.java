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

package bisq.core.btc;

import bisq.common.UserThread;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.refund.RefundManager;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.trade.failed.FailedTradesManager;
import bisq.core.util.ParsingUtils;
import bisq.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;
import org.bitcoinj.core.Coin;

@Slf4j
public class Balances {
    private final TradeManager tradeManager;
    private final XmrWalletService xmrWalletService;
    private final OpenOfferManager openOfferManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final RefundManager refundManager;

    @Getter
    private final ObjectProperty<Coin> availableBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<Coin> lockedBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<Coin> reservedOfferBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<Coin> reservedTradeBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<Coin> reservedBalance = new SimpleObjectProperty<>(); // TODO (woodser): this balance is sum of reserved funds for offers and trade multisigs; remove?

    @Inject
    public Balances(TradeManager tradeManager,
                    XmrWalletService xmrWalletService,
                    OpenOfferManager openOfferManager,
                    ClosedTradableManager closedTradableManager,
                    FailedTradesManager failedTradesManager,
                    RefundManager refundManager) {
        this.tradeManager = tradeManager;
        this.xmrWalletService = xmrWalletService;
        this.openOfferManager = openOfferManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.refundManager = refundManager;
    }

    public void onAllServicesInitialized() {
        openOfferManager.getObservableList().addListener((ListChangeListener<OpenOffer>) c -> updateBalance());
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) change -> updateBalance());
        refundManager.getDisputesAsObservableList().addListener((ListChangeListener<Dispute>) c -> updateBalance());
        xmrWalletService.getWallet().addListener(new MoneroWalletListener() {
          @Override public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) { updateBalance(); }
          @Override public void onOutputReceived(MoneroOutputWallet output) { updateBalance(); }
          @Override public void onOutputSpent(MoneroOutputWallet output) { updateBalance(); }
        });
        updateBalance();
    }

    private void updateBalance() {
        // Need to delay a bit to get the balances correct
        UserThread.execute(() -> {
            updateAvailableBalance();
            updateLockedBalance();
            updateReservedOfferBalance();
            updateReservedTradeBalance();
            updateReservedBalance();
        });
    }

    // TODO (woodser): balances being set as Coin from BigInteger.longValue(), which can lose precision. should be in centineros for consistency with the rest of the application

    private void updateAvailableBalance() {
        availableBalance.set(Coin.valueOf(xmrWalletService.getWallet().getUnlockedBalance(0).longValueExact()));
    }
    
    private void updateLockedBalance() {
        BigInteger balance = xmrWalletService.getWallet().getBalance(0);
        BigInteger unlockedBalance = xmrWalletService.getWallet().getUnlockedBalance(0);
        lockedBalance.set(Coin.valueOf(balance.subtract(unlockedBalance).longValueExact()));
    }
    
    private void updateReservedOfferBalance() {
        Coin sum = Coin.valueOf(0);
        List<MoneroOutputWallet> frozenOutputs = xmrWalletService.getWallet().getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false));
        for (MoneroOutputWallet frozenOutput : frozenOutputs) sum = sum.add(Coin.valueOf(frozenOutput.getAmount().longValueExact()));
        reservedOfferBalance.set(sum);
    }
    
    private void updateReservedTradeBalance() {
        Coin sum = Coin.valueOf(0);
        List<Trade> openTrades = tradeManager.getTradesStreamWithFundsLockedIn().collect(Collectors.toList());
        for (Trade trade : openTrades) {
            if (trade.getContract() == null) continue;
            Long reservedAmt;
            OfferPayload offerPayload = trade.getContract().getOfferPayload();
            if (trade.getArbitratorNodeAddress().equals(P2PService.getMyNodeAddress())) { // TODO (woodser): this only works if node address does not change
                reservedAmt = offerPayload.getAmount() + offerPayload.getBuyerSecurityDeposit() + offerPayload.getSellerSecurityDeposit(); // arbitrator reserved balance is sum of amounts sent to multisig
            } else {
                reservedAmt = trade.getContract().isMyRoleBuyer(tradeManager.getKeyRing().getPubKeyRing()) ? offerPayload.getBuyerSecurityDeposit() : offerPayload.getAmount() + offerPayload.getSellerSecurityDeposit();
            }
            sum = sum.add(Coin.valueOf(ParsingUtils.centinerosToAtomicUnits(reservedAmt).longValueExact()));
        }
        reservedTradeBalance.set(sum);
    }

    private void updateReservedBalance() {
        reservedBalance.set(reservedOfferBalance.get().add(reservedTradeBalance.get()));
    }
}
