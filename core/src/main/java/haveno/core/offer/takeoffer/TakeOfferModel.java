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

package haveno.core.offer.takeoffer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import haveno.common.taskrunner.Model;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import static haveno.core.offer.OfferDirection.SELL;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.xmr.model.XmrAddressEntry;
import static haveno.core.xmr.model.XmrAddressEntry.Context.OFFER_FUNDING;
import haveno.core.xmr.wallet.XmrWalletService;
import java.math.BigInteger;
import java.util.Objects;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

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
    private BigInteger amount;
    private Offer offer;
    private PaymentAccount paymentAccount;
    @Getter
    private BigInteger securityDeposit;
    private boolean useSavingsWallet;

    @Getter
    private BigInteger takerFee;
    @Getter
    private BigInteger totalToPay;
    @Getter
    private BigInteger missingCoin = BigInteger.ZERO;
    @Getter
    private BigInteger totalAvailableBalance;
    @Getter
    private BigInteger availableBalance;
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
                          BigInteger tradeAmount,
                          boolean useSavingsWallet) {
        this.clearModel();
        this.offer = offer;
        this.paymentAccount = paymentAccount;
        this.addressEntry = xmrWalletService.getOrCreateAddressEntry(offer.getId(), OFFER_FUNDING);
        validateModelInputs();

        this.useSavingsWallet = useSavingsWallet;
        this.amount = tradeAmount.min(BigInteger.valueOf(getMaxTradeLimit()));
        this.securityDeposit = offer.getDirection() == SELL
                ? offer.getOfferPayload().getBuyerSecurityDepositForTradeAmount(amount)
                : offer.getOfferPayload().getSellerSecurityDepositForTradeAmount(amount);
        this.takerFee = HavenoUtils.getTakerFee(amount);

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
        BigInteger feeAndSecDeposit = securityDeposit.add(takerFee);

        totalToPay = offer.isBuyOffer()
                ? feeAndSecDeposit.add(amount)
                : feeAndSecDeposit;

        updateBalance();
    }

    private void calculateVolume() {
        Price tradePrice = offer.getPrice();
        Volume volumeByAmount = Objects.requireNonNull(tradePrice).getVolumeByAmount(amount);
        volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, offer.getPaymentMethod().getId());

        volume = volumeByAmount;

        updateBalance();
    }

    private void updateBalance() {
        totalAvailableBalance = xmrWalletService.getAvailableBalance();
        if (totalToPay != null) availableBalance = totalToPay.min(totalAvailableBalance);
        missingCoin = offerUtil.getBalanceShortage(totalToPay, availableBalance);
        isXmrWalletFunded = offerUtil.isBalanceSufficient(totalToPay, availableBalance);
    }

    private long getMaxTradeLimit() {
        return accountAgeWitnessService.getMyTradeLimit(paymentAccount,
                offer.getCurrencyCode(),
                offer.getMirroredDirection());
    }

    @NotNull
    public BigInteger getFundsNeededForTrade() {
        // If taking a buy offer, taker needs to reserve the offer.amt too.
        return securityDeposit.add(offer.isBuyOffer() ? amount : BigInteger.ZERO);
    }

    private void validateModelInputs() {
        checkNotNull(offer, "offer must not be null");
        checkNotNull(offer.getAmount(), "offer amount must not be null");
        checkArgument(offer.getAmount().longValueExact() > 0, "offer amount must not be zero");
        checkNotNull(offer.getPrice(), "offer price must not be null");
        checkNotNull(paymentAccount, "payment account must not be null");
        checkNotNull(addressEntry, "address entry must not be null");
    }

    private void clearModel() {
        this.addressEntry = null;
        this.amount = null;
        this.availableBalance = null;
        this.isXmrWalletFunded = false;
        this.missingCoin = BigInteger.ZERO;
        this.offer = null;
        this.paymentAccount = null;
        this.securityDeposit = null;
        this.takerFee = null;
        this.totalAvailableBalance = null;
        this.totalToPay = null;
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
                ", totalToPay=" + totalToPay + "\n" +
                ", missingCoin=" + missingCoin + "\n" +
                ", totalAvailableBalance=" + totalAvailableBalance + "\n" +
                ", availableBalance=" + availableBalance + "\n" +
                ", volume=" + volume + "\n" +
                ", fundsNeededForTrade=" + getFundsNeededForTrade() + "\n" +
                ", isXmrWalletFunded=" + isXmrWalletFunded + "\n" +
                '}';
    }
}
