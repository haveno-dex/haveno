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

package haveno.core.xmr;

import com.google.inject.Inject;

import haveno.common.ThreadUtils;
import haveno.core.api.model.XmrBalanceInfo;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.wallet.XmrWalletService;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;

@Slf4j
public class Balances {
    private final TradeManager tradeManager;
    private final XmrWalletService xmrWalletService;
    private final OpenOfferManager openOfferManager;
    private final RefundManager refundManager;

    @Getter
    private BigInteger availableBalance;
    @Getter
    private BigInteger pendingBalance;
    @Getter
    private BigInteger reservedOfferBalance;
    @Getter
    private BigInteger reservedTradeBalance;
    @Getter
    private BigInteger reservedBalance; // TODO (woodser): this balance is sum of reserved funds for offers and trade multisigs; remove?

    @Getter
    private final IntegerProperty updateCounter = new SimpleIntegerProperty(0);

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
        this.refundManager = refundManager;
    }

    public void onAllServicesInitialized() {
        openOfferManager.getObservableList().addListener((ListChangeListener<OpenOffer>) c -> updateBalances());
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) change -> updateBalances());
        refundManager.getDisputesAsObservableList().addListener((ListChangeListener<Dispute>) c -> updateBalances());
        xmrWalletService.addBalanceListener(new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateBalances();
            }
        });
        doUpdateBalances();
    }

    public XmrBalanceInfo getBalances() {
        synchronized (this) {
            if (availableBalance == null) return null;
            return new XmrBalanceInfo(availableBalance.longValue() + pendingBalance.longValue(),
                availableBalance.longValue(),
                pendingBalance.longValue(),
                reservedOfferBalance.longValue(),
                reservedTradeBalance.longValue());
        }
    }

    private void updateBalances() {
        ThreadUtils.submitToPool(() -> doUpdateBalances());
    }

    private void doUpdateBalances() {
        synchronized (this) {
            synchronized (HavenoUtils.xmrWalletService.getWalletLock()) {

                // get non-trade balance before
                BigInteger balanceSumBefore = getNonTradeBalanceSum();

                // get wallet balances
                BigInteger balance = xmrWalletService.getWallet() == null ? BigInteger.ZERO : xmrWalletService.getBalance();
                availableBalance = xmrWalletService.getWallet() == null ? BigInteger.ZERO : xmrWalletService.getAvailableBalance();

                // calculate pending balance by adding frozen trade balances - reserved amounts
                pendingBalance = balance.subtract(availableBalance);
                List<Trade> trades = tradeManager.getTradesStreamWithFundsLockedIn().collect(Collectors.toList());
                for (Trade trade : trades) {
                    if (trade.getFrozenAmount().equals(new BigInteger("0"))) continue;
                    BigInteger tradeFee = trade instanceof MakerTrade ? trade.getMakerFee() : trade.getTakerFee();
                    pendingBalance = pendingBalance.add(trade.getFrozenAmount()).subtract(trade.getReservedAmount()).subtract(tradeFee).subtract(trade.getSelf().getDepositTxFee());
                }

                // calculate reserved offer balance
                reservedOfferBalance = BigInteger.ZERO;
                if (xmrWalletService.getWallet() != null) {
                    List<MoneroOutputWallet> frozenOutputs = xmrWalletService.getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false));
                    for (MoneroOutputWallet frozenOutput : frozenOutputs) reservedOfferBalance = reservedOfferBalance.add(frozenOutput.getAmount());
                }
                for (Trade trade : trades) {
                    reservedOfferBalance = reservedOfferBalance.subtract(trade.getFrozenAmount()); // subtract frozen trade balances
                }

                // calculate reserved trade balance
                reservedTradeBalance = BigInteger.ZERO;
                for (Trade trade : trades) {
                    reservedTradeBalance = reservedTradeBalance.add(trade.getReservedAmount());
                }

                // calculate reserved balance
                reservedBalance = reservedOfferBalance.add(reservedTradeBalance);

                // play sound if funds received
                boolean fundsReceived = balanceSumBefore != null && getNonTradeBalanceSum().compareTo(balanceSumBefore) > 0;
                if (fundsReceived) HavenoUtils.playCashRegisterSound();

                // notify balance update
                updateCounter.set(updateCounter.get() + 1);
            }
        }
    }

    private BigInteger getNonTradeBalanceSum() {
        synchronized (this) {
            if (availableBalance == null) return null;
            return availableBalance.add(pendingBalance).add(reservedOfferBalance);
        }
    }
}
