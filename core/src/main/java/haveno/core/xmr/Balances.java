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
import haveno.common.UserThread;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.wallet.XmrWalletService;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
    private final ObjectProperty<BigInteger> availableBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<BigInteger> pendingBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<BigInteger> reservedOfferBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<BigInteger> reservedTradeBalance = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<BigInteger> reservedBalance = new SimpleObjectProperty<>(); // TODO (woodser): this balance is sum of reserved funds for offers and trade multisigs; remove?

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
        updateBalances();
    }

    private void updateBalances() {
        ThreadUtils.submitToPool(() -> doUpdateBalances());
    }

    private void doUpdateBalances() {

        // get wallet balances
        BigInteger balance = xmrWalletService.getWallet() == null ? BigInteger.ZERO : xmrWalletService.getWallet().getBalance(0);
        BigInteger unlockedBalance = xmrWalletService.getWallet() == null ? BigInteger.ZERO : xmrWalletService.getWallet().getUnlockedBalance(0);

        // calculate pending balance by adding frozen trade balances - reserved amounts
        BigInteger pendingBalance = balance.subtract(unlockedBalance);
        List<Trade> trades = tradeManager.getTradesStreamWithFundsLockedIn().collect(Collectors.toList());
        for (Trade trade : trades) {
            if (trade.getFrozenAmount().equals(new BigInteger("0"))) continue;
            BigInteger tradeFee = trade instanceof MakerTrade ? trade.getMakerFee() : trade.getTakerFee();
            pendingBalance = pendingBalance.add(trade.getFrozenAmount()).subtract(trade.getReservedAmount()).subtract(tradeFee).subtract(trade.getSelf().getDepositTxFee());
        }

        // calculate reserved offer balance
        BigInteger reservedOfferBalance = BigInteger.ZERO;
        if (xmrWalletService.getWallet() != null) {
            List<MoneroOutputWallet> frozenOutputs = xmrWalletService.getWallet().getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false));
            for (MoneroOutputWallet frozenOutput : frozenOutputs) reservedOfferBalance = reservedOfferBalance.add(frozenOutput.getAmount());
        }
        for (Trade trade : trades) {
            reservedOfferBalance = reservedOfferBalance.subtract(trade.getFrozenAmount()); // subtract frozen trade balances
        }

        // calculate reserved trade balance
        BigInteger reservedTradeBalance = BigInteger.ZERO;
        for (Trade trade : trades) {
            reservedTradeBalance = reservedTradeBalance.add(trade.getReservedAmount());
        }

        // set balances
        setBalances(balance, unlockedBalance, pendingBalance, reservedOfferBalance, reservedTradeBalance);
    }

    private void setBalances(BigInteger balance, BigInteger unlockedBalance, BigInteger pendingBalance, BigInteger reservedOfferBalance, BigInteger reservedTradeBalance) {
        UserThread.execute(() -> {
            this.availableBalance.set(unlockedBalance);
            this.pendingBalance.set(pendingBalance);
            this.reservedOfferBalance.set(reservedOfferBalance);
            this.reservedTradeBalance.set(reservedTradeBalance);
            this.reservedBalance.set(reservedOfferBalance.add(reservedTradeBalance));
        });
    }
}
