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

package bisq.core.offer.takeoffer;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.CurrencyUtil;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferUtil;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.provider.price.PriceFeedService;

import bisq.common.taskrunner.Model;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;

import java.util.Objects;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import static bisq.core.btc.model.XmrAddressEntry.Context.OFFER_FUNDING;
import static bisq.core.offer.OfferDirection.SELL;
import static bisq.core.util.VolumeUtil.getAdjustedVolumeForHalCash;
import static bisq.core.util.VolumeUtil.getRoundedFiatVolume;
import static bisq.core.util.coin.CoinUtil.minCoin;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.bitcoinj.core.Coin.ZERO;
import static org.bitcoinj.core.Coin.valueOf;

@Slf4j
public class TakeOfferModel implements Model {
    // Immutable
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final XmrWalletService xmrWalletService;
    private final OfferUtil offerUtil;
    private final PriceFeedService priceFeedService;

    // Mutable
    @Getter
    private XmrAddressEntry addressEntry;
    @Getter
    private Coin amount;
    private Offer offer;
    private PaymentAccount paymentAccount;
    @Getter
    private Coin securityDeposit;
    private boolean useSavingsWallet;

    @Getter
    private Coin takerFee;
    @Getter
    private Coin totalToPayAsCoin;
    @Getter
    private Coin missingCoin = ZERO;
    @Getter
    private Coin totalAvailableBalance;
    @Getter
    private Coin balance;
    @Getter
    private boolean isXmrWalletFunded;
    @Getter
    private Volume volume;

    @Inject
    public TakeOfferModel(AccountAgeWitnessService accountAgeWitnessService,
                          XmrWalletService xmrWalletService,
                          OfferUtil offerUtil,
                          PriceFeedService priceFeedService) {
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.xmrWalletService = xmrWalletService;
        this.offerUtil = offerUtil;
        this.priceFeedService = priceFeedService;
    }

    public void initModel(Offer offer,
                          PaymentAccount paymentAccount,
                          boolean useSavingsWallet) {
        this.clearModel();
        this.offer = offer;
        this.paymentAccount = paymentAccount;
        this.addressEntry = xmrWalletService.getOrCreateAddressEntry(offer.getId(), OFFER_FUNDING); // TODO (woodser): replace with xmr or remove
        validateModelInputs();

        this.useSavingsWallet = useSavingsWallet;
        this.amount = valueOf(Math.min(offer.getAmount().value, getMaxTradeLimit()));
        this.securityDeposit = offer.getDirection() == SELL
                ? offer.getBuyerSecurityDeposit()
                : offer.getSellerSecurityDeposit();
        this.takerFee = offerUtil.getTakerFee(amount);

        calculateVolume();
        calculateTotalToPay();
        offer.resetState();

        priceFeedService.setCurrencyCode(offer.getCurrencyCode());
    }

    @Override
    public void onComplete() {
        // empty
    }

    private void calculateTotalToPay() {
        // Taker pays 2 times the tx fee because the mining fee might be different when
        // maker created the offer and reserved his funds, so that would not work well
        // with dynamic fees.  The mining fee for the takeOfferFee tx is deducted from
        // the createOfferFee and not visible to the trader.
        Coin feeAndSecDeposit = securityDeposit.add(takerFee);

        totalToPayAsCoin = offer.isBuyOffer()
                ? feeAndSecDeposit.add(amount)
                : feeAndSecDeposit;

        updateBalance();
    }

    private void calculateVolume() {
        Price tradePrice = offer.getPrice();
        Volume volumeByAmount = Objects.requireNonNull(tradePrice).getVolumeByAmount(amount);

        if (offer.getPaymentMethod().getId().equals(PaymentMethod.HAL_CASH_ID))
            volumeByAmount = getAdjustedVolumeForHalCash(volumeByAmount);
        else if (CurrencyUtil.isFiatCurrency(offer.getCurrencyCode()))
            volumeByAmount = getRoundedFiatVolume(volumeByAmount);

        volume = volumeByAmount;

        updateBalance();
    }

    private void updateBalance() {
        totalAvailableBalance = xmrWalletService.getSavingWalletBalance();
        if (totalToPayAsCoin != null) balance = minCoin(totalToPayAsCoin, totalAvailableBalance);
        missingCoin = offerUtil.getBalanceShortage(totalToPayAsCoin, balance);
        isXmrWalletFunded = offerUtil.isBalanceSufficient(totalToPayAsCoin, balance);
    }

    private long getMaxTradeLimit() {
        return accountAgeWitnessService.getMyTradeLimit(paymentAccount,
                offer.getCurrencyCode(),
                offer.getMirroredDirection());
    }

    @NotNull
    public Coin getFundsNeededForTrade() {
        // If taking a buy offer, taker needs to reserve the offer.amt too.
        return securityDeposit.add(offer.isBuyOffer() ? amount : ZERO);
    }

    private void validateModelInputs() {
        checkNotNull(offer, "offer must not be null");
        checkNotNull(offer.getAmount(), "offer amount must not be null");
        checkArgument(offer.getAmount().value > 0, "offer amount must not be zero");
        checkNotNull(offer.getPrice(), "offer price must not be null");
        checkNotNull(paymentAccount, "payment account must not be null");
        checkNotNull(addressEntry, "address entry must not be null");
    }

    private void clearModel() {
        this.addressEntry = null;
        this.amount = null;
        this.balance = null;
        this.isXmrWalletFunded = false;
        this.missingCoin = ZERO;
        this.offer = null;
        this.paymentAccount = null;
        this.securityDeposit = null;
        this.takerFee = null;
        this.totalAvailableBalance = null;
        this.totalToPayAsCoin = null;
        this.useSavingsWallet = true;
        this.volume = null;
    }

    @Override
    public String toString() {
        return "TakeOfferModel{" +
                "  offer.id=" + offer.getId() + "\n" +
                "  offer.state=" + offer.getState() + "\n" +
                ", paymentAccount.id=" + paymentAccount.getId() + "\n" +
                ", paymentAccount.method.id=" + paymentAccount.getPaymentMethod().getId() + "\n" +
                ", useSavingsWallet=" + useSavingsWallet + "\n" +
                ", addressEntry=" + addressEntry + "\n" +
                ", amount=" + amount + "\n" +
                ", securityDeposit=" + securityDeposit + "\n" +
                ", takerFee=" + takerFee + "\n" +
                ", totalToPayAsCoin=" + totalToPayAsCoin + "\n" +
                ", missingCoin=" + missingCoin + "\n" +
                ", totalAvailableBalance=" + totalAvailableBalance + "\n" +
                ", balance=" + balance + "\n" +
                ", volume=" + volume + "\n" +
                ", fundsNeededForTrade=" + getFundsNeededForTrade() + "\n" +
                ", isXmrWalletFunded=" + isXmrWalletFunded + "\n" +
                '}';
    }
}
