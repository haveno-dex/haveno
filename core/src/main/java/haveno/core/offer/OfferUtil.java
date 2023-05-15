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

package haveno.core.offer;

import haveno.common.app.Capabilities;
import haveno.common.app.Version;
import haveno.common.util.MathUtils;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import haveno.core.payment.CashByMailAccount;
import haveno.core.payment.F2FAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.user.AutoConfirmSettings;
import haveno.core.user.Preferences;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.common.util.MathUtils.roundDoubleToLong;
import static haveno.common.util.MathUtils.scaleUpByPowerOf10;
import static haveno.core.offer.OfferPayload.ACCOUNT_AGE_WITNESS_HASH;
import static haveno.core.offer.OfferPayload.CAPABILITIES;
import static haveno.core.offer.OfferPayload.CASH_BY_MAIL_EXTRA_INFO;
import static haveno.core.offer.OfferPayload.F2F_CITY;
import static haveno.core.offer.OfferPayload.F2F_EXTRA_INFO;
import static haveno.core.offer.OfferPayload.REFERRAL_ID;
import static haveno.core.offer.OfferPayload.XMR_AUTO_CONF;
import static haveno.core.offer.OfferPayload.XMR_AUTO_CONF_ENABLED_VALUE;
import static haveno.core.xmr.wallet.Restrictions.getMaxBuyerSecurityDepositAsPercent;
import static haveno.core.xmr.wallet.Restrictions.getMinBuyerSecurityDepositAsPercent;

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
    public boolean isBalanceSufficient(BigInteger cost, BigInteger balance) {
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
    public BigInteger getBalanceShortage(BigInteger cost, BigInteger balance) {
        if (cost != null) {
            BigInteger shortage = cost.subtract(balance);
            return shortage.compareTo(BigInteger.valueOf(0)) < 0 ? BigInteger.valueOf(0) : shortage;
        } else {
            return BigInteger.valueOf(0);
        }
    }

    public double calculateManualPrice(double volumeAsDouble, double amountAsDouble) {
        return volumeAsDouble / amountAsDouble;
    }

    public double calculateMarketPriceMarginPct(double manualPrice, double marketPrice) {
        return MathUtils.roundDouble(manualPrice / marketPrice, 4);
    }

    public boolean isBlockChainPaymentMethod(Offer offer) {
        return offer != null && offer.getPaymentMethod().isBlockchain();
    }

    public Optional<Volume> getFeeInUserFiatCurrency(BigInteger makerFee,
                                                     CoinFormatter formatter) {
        String userCurrencyCode = preferences.getPreferredTradeCurrency().getCode();
        if (CurrencyUtil.isCryptoCurrency(userCurrencyCode)) {
            // In case the user has selected a crypto as preferredTradeCurrency
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
        if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
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
                                  BigInteger makerFee) {
        checkNotNull(makerFee, "makerFee must not be null");
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

    private Optional<Volume> getFeeInUserFiatCurrency(BigInteger makerFee, String userCurrencyCode, CoinFormatter formatter) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(userCurrencyCode);
        if (marketPrice != null && makerFee != null) {
            long marketPriceAsLong = roundDoubleToLong(scaleUpByPowerOf10(marketPrice.getPrice(), TraditionalMoney.SMALLEST_UNIT_EXPONENT));
            Price userCurrencyPrice = Price.valueOf(userCurrencyCode, marketPriceAsLong);
            return Optional.of(userCurrencyPrice.getVolumeByAmount(makerFee));
        } else {
            return Optional.empty();
        }
    }

    public static boolean isTraditionalOffer(Offer offer) {
        return offer.getBaseCurrencyCode().equals("XMR");
    }

    public static boolean isCryptoOffer(Offer offer) {
        return offer.getCounterCurrencyCode().equals("XMR");
    }

    public static Optional<String> getInvalidMakerFeeTxErrorMessage(Offer offer, BtcWalletService btcWalletService) {
        String offerFeeTxId = offer.getOfferFeeTxId();
        if (offerFeeTxId == null) {
            return Optional.empty();
        }

        Transaction makerFeeTx = btcWalletService.getTransaction(offerFeeTxId);
        if (makerFeeTx == null) {
            return Optional.empty();
        }

        String errorMsg = null;
        String header = "The offer with offer ID '" + offer.getShortId() +
                "' has an invalid maker fee transaction.\n\n";
        String spendingTransaction = null;
        String extraString = "\nYou have to remove that offer to avoid failed trades.\n" +
                "If this happened because of a bug please contact the Haveno developers " +
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
