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

package haveno.core.offer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.common.util.Utilities;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.coin.CoinUtil;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class CreateOfferService {
    private final OfferUtil offerUtil;
    private final PriceFeedService priceFeedService;
    private final P2PService p2PService;
    private final PubKeyRingProvider pubKeyRingProvider;
    private final User user;
    private final XmrWalletService xmrWalletService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final ArbitratorManager arbitratorManager;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public CreateOfferService(OfferUtil offerUtil,
                              PriceFeedService priceFeedService,
                              P2PService p2PService,
                              PubKeyRingProvider pubKeyRingProvider,
                              User user,
                              XmrWalletService xmrWalletService,
                              TradeStatisticsManager tradeStatisticsManager,
                              ArbitratorManager arbitratorManager) {
        this.offerUtil = offerUtil;
        this.priceFeedService = priceFeedService;
        this.p2PService = p2PService;
        this.pubKeyRingProvider = pubKeyRingProvider;
        this.user = user;
        this.xmrWalletService = xmrWalletService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.arbitratorManager = arbitratorManager;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getRandomOfferId() {
        return Utilities.getRandomPrefix(5, 8) + "-" +
                UUID.randomUUID().toString() + "-" +
                Version.VERSION.replace(".", "");
    }

    public Offer createAndGetOffer(String offerId,
                                   OfferDirection direction,
                                   String currencyCode,
                                   BigInteger amount,
                                   BigInteger minAmount,
                                   Price fixedPrice,
                                   boolean useMarketBasedPrice,
                                   double marketPriceMargin,
                                   double securityDepositAsDouble,
                                   PaymentAccount paymentAccount) {

        log.info("create and get offer with offerId={}, " +
                        "currencyCode={}, " +
                        "direction={}, " +
                        "fixedPrice={}, " +
                        "useMarketBasedPrice={}, " +
                        "marketPriceMargin={}, " +
                        "amount={}, " +
                        "minAmount={}, " +
                        "securityDeposit={}",
                offerId,
                currencyCode,
                direction,
                fixedPrice == null ? null : fixedPrice.getValue(),
                useMarketBasedPrice,
                marketPriceMargin,
                amount,
                minAmount,
                securityDepositAsDouble);

        // verify fixed price xor market price with margin
        if (fixedPrice != null) {
            if (useMarketBasedPrice) throw new IllegalArgumentException("Can create offer with fixed price or floating market price but not both");
            if (marketPriceMargin != 0) throw new IllegalArgumentException("Cannot set market price margin with fixed price");
        }

        long creationTime = new Date().getTime();
        NodeAddress makerAddress = p2PService.getAddress();
        boolean useMarketBasedPriceValue = fixedPrice == null &&
                useMarketBasedPrice &&
                isMarketPriceAvailable(currencyCode) &&
                !PaymentMethod.isFixedPriceOnly(paymentAccount.getPaymentMethod().getId());

        // verify price
        if (fixedPrice == null && !useMarketBasedPriceValue) {
            throw new IllegalArgumentException("Must provide fixed price");
        }

        // adjust amount and min amount for fixed-price offer
        long maxTradeLimit = offerUtil.getMaxTradeLimit(paymentAccount, currencyCode, direction);
        if (fixedPrice != null) {
            amount = CoinUtil.getRoundedAmount(amount, fixedPrice, maxTradeLimit, currencyCode, paymentAccount.getPaymentMethod().getId());
            minAmount = CoinUtil.getRoundedAmount(minAmount, fixedPrice, maxTradeLimit, currencyCode, paymentAccount.getPaymentMethod().getId());
        }

        long priceAsLong = fixedPrice != null ? fixedPrice.getValue() : 0L;
        double marketPriceMarginParam = useMarketBasedPriceValue ? marketPriceMargin : 0;
        long amountAsLong = amount != null ? amount.longValueExact() : 0L;
        long minAmountAsLong = minAmount != null ? minAmount.longValueExact() : 0L;
        boolean isCryptoCurrency = CurrencyUtil.isCryptoCurrency(currencyCode);
        String baseCurrencyCode = isCryptoCurrency ? currencyCode : Res.getBaseCurrencyCode();
        String counterCurrencyCode = isCryptoCurrency ? Res.getBaseCurrencyCode() : currencyCode;
        String countryCode = PaymentAccountUtil.getCountryCode(paymentAccount);
        List<String> acceptedCountryCodes = PaymentAccountUtil.getAcceptedCountryCodes(paymentAccount);
        String bankId = PaymentAccountUtil.getBankId(paymentAccount);
        List<String> acceptedBanks = PaymentAccountUtil.getAcceptedBanks(paymentAccount);
        BigInteger makerFee = HavenoUtils.getMakerFee(amount);
        long maxTradePeriod = paymentAccount.getMaxTradePeriod();

        // reserved for future use cases
        // Use null values if not set
        boolean isPrivateOffer = false;
        boolean useAutoClose = false;
        boolean useReOpenAfterAutoClose = false;
        long lowerClosePrice = 0;
        long upperClosePrice = 0;
        String hashOfChallenge = null;
        Map<String, String> extraDataMap = offerUtil.getExtraDataMap(paymentAccount,
                currencyCode,
                direction);

        offerUtil.validateOfferData(
                securityDepositAsDouble,
                paymentAccount,
                currencyCode,
                makerFee);

        OfferPayload offerPayload = new OfferPayload(offerId,
                creationTime,
                makerAddress,
                pubKeyRingProvider.get(),
                OfferDirection.valueOf(direction.name()),
                priceAsLong,
                marketPriceMarginParam,
                useMarketBasedPriceValue,
                amountAsLong,
                minAmountAsLong,
                baseCurrencyCode,
                counterCurrencyCode,
                paymentAccount.getPaymentMethod().getId(),
                paymentAccount.getId(),
                countryCode,
                acceptedCountryCodes,
                bankId,
                acceptedBanks,
                Version.VERSION,
                xmrWalletService.getWallet().getHeight(),
                makerFee.longValueExact(),
                securityDepositAsDouble,
                securityDepositAsDouble,
                maxTradeLimit,
                maxTradePeriod,
                useAutoClose,
                useReOpenAfterAutoClose,
                upperClosePrice,
                lowerClosePrice,
                isPrivateOffer,
                hashOfChallenge,
                extraDataMap,
                Version.TRADE_PROTOCOL_VERSION,
                null,
                null,
                null);
        Offer offer = new Offer(offerPayload);
        offer.setPriceFeedService(priceFeedService);
        return offer;
    }

    public BigInteger getReservedFundsForOffer(OfferDirection direction,
                                         BigInteger amount,
                                         double buyerSecurityDeposit,
                                         double sellerSecurityDeposit) {

        BigInteger reservedFundsForOffer = getSecurityDeposit(direction,
                amount,
                buyerSecurityDeposit,
                sellerSecurityDeposit);
        if (!offerUtil.isBuyOffer(direction))
            reservedFundsForOffer = reservedFundsForOffer.add(amount);

        return reservedFundsForOffer;
    }

    public BigInteger getSecurityDeposit(OfferDirection direction,
                                   BigInteger amount,
                                   double buyerSecurityDeposit,
                                   double sellerSecurityDeposit) {
        return offerUtil.isBuyOffer(direction) ?
                getBuyerSecurityDeposit(amount, buyerSecurityDeposit) :
                getSellerSecurityDeposit(amount, sellerSecurityDeposit);
    }

    public double getSellerSecurityDepositAsDouble(double buyerSecurityDeposit) {
        return Preferences.USE_SYMMETRIC_SECURITY_DEPOSIT ? buyerSecurityDeposit :
                Restrictions.getSellerSecurityDepositAsPercent();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean isMarketPriceAvailable(String currencyCode) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        return marketPrice != null && marketPrice.isExternallyProvidedPrice();
    }

    private BigInteger getBuyerSecurityDeposit(BigInteger amount, double buyerSecurityDeposit) {
        BigInteger percentOfAmount = CoinUtil.getPercentOfAmount(buyerSecurityDeposit, amount);
        return getBoundedBuyerSecurityDeposit(percentOfAmount);
    }

    private BigInteger getSellerSecurityDeposit(BigInteger amount, double sellerSecurityDeposit) {
        BigInteger percentOfAmount = CoinUtil.getPercentOfAmount(sellerSecurityDeposit, amount);
        return getBoundedSellerSecurityDeposit(percentOfAmount);
    }

    private BigInteger getBoundedBuyerSecurityDeposit(BigInteger value) {
        // We need to ensure that for small amount values we don't get a too low BTC amount. We limit it with using the
        // MinBuyerSecurityDeposit from Restrictions.
        return Restrictions.getMinBuyerSecurityDeposit().max(value);
    }

    private BigInteger getBoundedSellerSecurityDeposit(BigInteger value) {
        // We need to ensure that for small amount values we don't get a too low BTC amount. We limit it with using the
        // MinSellerSecurityDeposit from Restrictions.
        return Restrictions.getMinSellerSecurityDeposit().max(value);
    }
}
