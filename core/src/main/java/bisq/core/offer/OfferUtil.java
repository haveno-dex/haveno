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

package bisq.core.offer;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.filter.FilterManager;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.payment.CashByMailAccount;
import bisq.core.payment.F2FAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.provider.price.MarketPrice;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.statistics.ReferralIdService;
import bisq.core.user.AutoConfirmSettings;
import bisq.core.user.Preferences;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.coin.CoinUtil;

import bisq.network.p2p.P2PService;

import bisq.common.app.Capabilities;
import bisq.common.app.Version;
import bisq.common.util.MathUtils;
import bisq.common.util.Utilities;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.utils.Fiat;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static bisq.common.util.MathUtils.roundDoubleToLong;
import static bisq.common.util.MathUtils.scaleUpByPowerOf10;
import static bisq.core.btc.wallet.Restrictions.getMaxBuyerSecurityDepositAsPercent;
import static bisq.core.btc.wallet.Restrictions.getMinBuyerSecurityDepositAsPercent;
import static bisq.core.offer.OfferPayload.*;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class holds utility methods for creating, editing and taking an Offer.
 */
@Slf4j
@Singleton
public class OfferUtil {

	private final AccountAgeWitnessService accountAgeWitnessService;
	private final FilterManager filterManager;
	private final Preferences preferences;
	private final PriceFeedService priceFeedService;
	private final P2PService p2PService;
	private final ReferralIdService referralIdService;

    @Inject
    public OfferUtil(AccountAgeWitnessService accountAgeWitnessService,
                     FilterManager filterManager,
                     Preferences preferences,
                     PriceFeedService priceFeedService,
                     P2PService p2PService,
                     ReferralIdService referralIdService) {
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.filterManager = filterManager;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
        this.p2PService = p2PService;
        this.referralIdService = referralIdService;
    }
    
    public static String getRandomOfferId() {
        return Utilities.getRandomPrefix(5, 8) + "-" +
                UUID.randomUUID() + "-" +
                getStrippedVersion();
    }

    public static String getStrippedVersion() {
        return Version.VERSION.replace(".", "");
    }

    /**
     * Given the direction, is this a BUY?
     *
     * @param direction the offer direction
     * @return {@code true} for an offer to buy BTC from the taker, {@code false} for an
     * offer to sell BTC to the taker
     */
    public boolean isBuyOffer(OfferDirection direction) {
        return direction == OfferDirection.BUY;
    }

    public long getMaxTradeLimit(PaymentAccount paymentAccount,
                                 String currencyCode,
                                 OfferDirection direction) {
        return paymentAccount != null
                ? accountAgeWitnessService.getMyTradeLimit(paymentAccount, currencyCode, direction)
                : 0;
    }

    /**
     * Return true if a balance can cover a cost.
     *
     * @param cost the cost of a trade
     * @param balance a wallet balance
     * @return true if balance >= cost
     */
    public boolean isBalanceSufficient(Coin cost, Coin balance) {
        return cost != null && balance.compareTo(cost) >= 0;
    }

    /**
     * Return the wallet balance shortage for a given trade cost, or zero if there is
     * no shortage.
     *
     * @param cost the cost of a trade
     * @param balance a wallet balance
     * @return the wallet balance shortage for the given cost, else zero.
     */
    public Coin getBalanceShortage(Coin cost, Coin balance) {
        if (cost != null) {
            Coin shortage = cost.subtract(balance);
            return shortage.isNegative() ? Coin.ZERO : shortage;
        } else {
            return Coin.ZERO;
        }
    }


    public double calculateManualPrice(double volumeAsDouble, double amountAsDouble) {
        return volumeAsDouble / amountAsDouble;
    }

    public double calculateMarketPriceMarginPct(double manualPrice, double marketPrice) {
        return MathUtils.roundDouble(manualPrice / marketPrice, 4);
    }

    /**
     * Returns the makerFee as Coin, this can be priced in BTC.
     *
     * @param amount           the amount of BTC to trade
     * @return the maker fee for the given trade amount, or {@code null} if the amount
     * is {@code null}
     */
    @Nullable
    public Coin getMakerFee(@Nullable Coin amount) {
        return CoinUtil.getMakerFee(amount);
    }

    public Coin getTxFeeByVsize(Coin txFeePerVbyteFromFeeService, int vsizeInVbytes) {
        return txFeePerVbyteFromFeeService.multiply(getAverageTakerFeeTxVsize(vsizeInVbytes));
    }

    // We use the sum of the size of the trade fee and the deposit tx to get an average.
    // Miners will take the trade fee tx if the total fee of both dependent txs are good
    // enough.  With that we avoid that we overpay in case that the trade fee has many
    // inputs and we would apply that fee for the other 2 txs as well. We still might
    // overpay a bit for the payout tx.
    public int getAverageTakerFeeTxVsize(int txVsize) {
        return (txVsize + 233) / 2;
    }

    @Nullable
    public Coin getTakerFee(@Nullable Coin amount) {
        if (amount != null) {
            Coin feePerBtc = CoinUtil.getFeePerBtc(HavenoUtils.getTakerFeePerBtc(), amount);
            return CoinUtil.maxCoin(feePerBtc, HavenoUtils.getMinTakerFee());
        } else {
            return null;
        }
    }

    public boolean isBlockChainPaymentMethod(Offer offer) {
        return offer != null && offer.getPaymentMethod().isBlockchain();
    }

    public Optional<Volume> getFeeInUserFiatCurrency(Coin makerFee,
                                                     CoinFormatter formatter) {
        String userCurrencyCode = preferences.getPreferredTradeCurrency().getCode();
        if (CurrencyUtil.isCryptoCurrency(userCurrencyCode)) {
            // In case the user has selected a altcoin as preferredTradeCurrency
            // we derive the fiat currency from the user country
            String countryCode = preferences.getUserCountry().code;
            userCurrencyCode = CurrencyUtil.getCurrencyByCountryCode(countryCode).getCode();
        }

        return getFeeInUserFiatCurrency(makerFee,
                userCurrencyCode,
                formatter);
    }

    public Map<String, String> getExtraDataMap(PaymentAccount paymentAccount,
                                               String currencyCode,
                                               OfferDirection direction) {
        Map<String, String> extraDataMap = new HashMap<>();
        if (CurrencyUtil.isFiatCurrency(currencyCode)) {
            String myWitnessHashAsHex = accountAgeWitnessService
                    .getMyWitnessHashAsHex(paymentAccount.getPaymentAccountPayload());
            extraDataMap.put(ACCOUNT_AGE_WITNESS_HASH, myWitnessHashAsHex);
        }

        if (referralIdService.getOptionalReferralId().isPresent()) {
            extraDataMap.put(REFERRAL_ID, referralIdService.getOptionalReferralId().get());
        }

        if (paymentAccount instanceof F2FAccount) {
            extraDataMap.put(F2F_CITY, ((F2FAccount) paymentAccount).getCity());
            extraDataMap.put(F2F_EXTRA_INFO, ((F2FAccount) paymentAccount).getExtraInfo());
        }

        if (paymentAccount instanceof CashByMailAccount) {
            extraDataMap.put(CASH_BY_MAIL_EXTRA_INFO, ((CashByMailAccount) paymentAccount).getExtraInfo());
        }

        extraDataMap.put(CAPABILITIES, Capabilities.app.toStringList());

        if (currencyCode.equals("XMR") && direction == OfferDirection.SELL) {
            preferences.getAutoConfirmSettingsList().stream()
                    .filter(e -> e.getCurrencyCode().equals("XMR"))
                    .filter(AutoConfirmSettings::isEnabled)
                    .forEach(e -> extraDataMap.put(XMR_AUTO_CONF, XMR_AUTO_CONF_ENABLED_VALUE));
        }

        return extraDataMap.isEmpty() ? null : extraDataMap;
    }

    public void validateOfferData(double buyerSecurityDeposit,
                                  PaymentAccount paymentAccount,
                                  String currencyCode,
                                  Coin makerFeeAsCoin) {
        checkNotNull(makerFeeAsCoin, "makerFee must not be null");
        checkNotNull(p2PService.getAddress(), "Address must not be null");
        checkArgument(buyerSecurityDeposit <= getMaxBuyerSecurityDepositAsPercent(),
                "securityDeposit must not exceed " +
                        getMaxBuyerSecurityDepositAsPercent());
        checkArgument(buyerSecurityDeposit >= getMinBuyerSecurityDepositAsPercent(),
                "securityDeposit must not be less than " +
                        getMinBuyerSecurityDepositAsPercent());
        checkArgument(!filterManager.isCurrencyBanned(currencyCode),
                Res.get("offerbook.warning.currencyBanned"));
        checkArgument(!filterManager.isPaymentMethodBanned(paymentAccount.getPaymentMethod()),
                Res.get("offerbook.warning.paymentMethodBanned"));
    }

    private Optional<Volume> getFeeInUserFiatCurrency(Coin makerFee, String userCurrencyCode, CoinFormatter formatter) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(userCurrencyCode);
        if (marketPrice != null && makerFee != null) {
            long marketPriceAsLong = roundDoubleToLong(scaleUpByPowerOf10(marketPrice.getPrice(), Fiat.SMALLEST_UNIT_EXPONENT));
            Price userCurrencyPrice = Price.valueOf(userCurrencyCode, marketPriceAsLong);
            return Optional.of(userCurrencyPrice.getVolumeByAmount(makerFee));
        } else {
            return Optional.empty();
        }
    }

    public static boolean isFiatOffer(Offer offer) {
        return offer.getBaseCurrencyCode().equals("XMR");
    }

    public static boolean isAltcoinOffer(Offer offer) {
        return offer.getCounterCurrencyCode().equals("XMR");
    }

    public static Optional<String> getInvalidMakerFeeTxErrorMessage(Offer offer, BtcWalletService btcWalletService) {
        String offerFeePaymentTxId = offer.getOfferFeePaymentTxId();
        if (offerFeePaymentTxId == null) {
            return Optional.empty();
        }

        Transaction makerFeeTx = btcWalletService.getTransaction(offerFeePaymentTxId);
        if (makerFeeTx == null) {
            return Optional.empty();
        }

        String errorMsg = null;
        String header = "The offer with offer ID '" + offer.getShortId() +
                "' has an invalid maker fee transaction.\n\n";
        String spendingTransaction = null;
        String extraString = "\nYou have to remove that offer to avoid failed trades.\n" +
                "If this happened because of a bug please contact the Bisq developers " +
                "and you can request reimbursement for the lost maker fee.";
        if (makerFeeTx.getOutputs().size() > 1) {
            // Our output to fund the deposit tx is at index 1
            TransactionOutput output = makerFeeTx.getOutput(1);
            TransactionInput spentByTransactionInput = output.getSpentBy();
            if (spentByTransactionInput != null) {
                spendingTransaction = spentByTransactionInput.getConnectedTransaction() != null ?
                        spentByTransactionInput.getConnectedTransaction().toString() :
                        "null";
                // We this is an exceptional case we do not translate that error msg.
                errorMsg = "The output of the maker fee tx is already spent.\n" +
                        extraString +
                        "\n\nTransaction input which spent the reserved funds for that offer: '" +
                        spentByTransactionInput.getConnectedTransaction().getTxId().toString() + ":" +
                        (spentByTransactionInput.getConnectedOutput() != null ?
                                spentByTransactionInput.getConnectedOutput().getIndex() + "'" :
                                "null'");
                log.error("spentByTransactionInput {}", spentByTransactionInput);
            }
        } else {
            errorMsg = "The maker fee tx is invalid as it does not has at least 2 outputs." + extraString +
                    "\nMakerFeeTx=" + makerFeeTx.toString();
        }

        if (errorMsg == null) {
            return Optional.empty();
        }

        errorMsg = header + errorMsg;
        log.error(errorMsg);
        if (spendingTransaction != null) {
            log.error("Spending transaction: {}", spendingTransaction);
        }

        return Optional.of(errorMsg);
    }
}
