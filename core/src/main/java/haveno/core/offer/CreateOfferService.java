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
import haveno.core.user.User;
import haveno.core.util.coin.CoinUtil;
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

    // TODO: add trigger price?
    public Offer createAndGetOffer(String offerId,
                                   OfferDirection direction,
                                   String currencyCode,
                                   BigInteger amount,
                                   BigInteger minAmount,
                                   Price fixedPrice,
                                   boolean useMarketBasedPrice,
                                   double marketPriceMargin,
                                   double securityDepositPct,
                                   PaymentAccount paymentAccount,
                                   boolean isPrivateOffer,
                                   boolean buyerAsTakerWithoutDeposit,
                                   String extraInfo) {
        log.info("Create and get offer with offerId={}, " +
                        "currencyCode={}, " +
                        "direction={}, " +
                        "fixedPrice={}, " +
                        "useMarketBasedPrice={}, " +
                        "marketPriceMargin={}, " +
                        "amount={}, " +
                        "minAmount={}, " +
                        "securityDepositPct={}, " +
                        "isPrivateOffer={}, " +
                        "buyerAsTakerWithoutDeposit={}, " + 
                        "extraInfo={}",
                offerId,
                currencyCode,
                direction,
                fixedPrice == null ? null : fixedPrice.getValue(),
                useMarketBasedPrice,
                marketPriceMargin,
                amount,
                minAmount,
                securityDepositPct,
                isPrivateOffer,
                buyerAsTakerWithoutDeposit,
                extraInfo);

        // must nullify empty string so contracts match
        if ("".equals(extraInfo)) extraInfo = null;

        // verify buyer as taker security deposit
        boolean isBuyerMaker = offerUtil.isBuyOffer(direction);
        if (!isBuyerMaker && !isPrivateOffer && buyerAsTakerWithoutDeposit) {
            throw new IllegalArgumentException("Buyer as taker deposit is required for public offers");
        }

        // verify fixed price xor market price with margin
        if (fixedPrice != null) {
            if (useMarketBasedPrice) throw new IllegalArgumentException("Can create offer with fixed price or floating market price but not both");
            if (marketPriceMargin != 0) throw new IllegalArgumentException("Cannot set market price margin with fixed price");
        }

        // verify price
        boolean useMarketBasedPriceValue = fixedPrice == null &&
                useMarketBasedPrice &&
                isMarketPriceAvailable(currencyCode) &&
                !PaymentMethod.isFixedPriceOnly(paymentAccount.getPaymentMethod().getId());
        if (fixedPrice == null && !useMarketBasedPriceValue) {
            throw new IllegalArgumentException("Must provide fixed price");
        }

        // adjust amount and min amount
        amount = CoinUtil.getRoundedAmount(amount, fixedPrice, null, currencyCode, paymentAccount.getPaymentMethod().getId());
        minAmount = CoinUtil.getRoundedAmount(minAmount, fixedPrice, null, currencyCode, paymentAccount.getPaymentMethod().getId());

        // generate one-time challenge for private offer
        String challenge = null;
        String challengeHash = null;
        if (isPrivateOffer) {
            challenge = HavenoUtils.generateChallenge();
            challengeHash = HavenoUtils.getChallengeHash(challenge);
        }

        long creationTime = new Date().getTime();
        NodeAddress makerAddress = p2PService.getAddress();
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
        long maxTradePeriod = paymentAccount.getMaxTradePeriod();
        boolean hasBuyerAsTakerWithoutDeposit = !isBuyerMaker && isPrivateOffer && buyerAsTakerWithoutDeposit;
        long maxTradeLimit = offerUtil.getMaxTradeLimit(paymentAccount, currencyCode, direction, hasBuyerAsTakerWithoutDeposit);
        boolean useAutoClose = false;
        boolean useReOpenAfterAutoClose = false;
        long lowerClosePrice = 0;
        long upperClosePrice = 0;
        Map<String, String> extraDataMap = offerUtil.getExtraDataMap(paymentAccount, currencyCode, direction);

        offerUtil.validateOfferData(
                securityDepositPct,
                paymentAccount,
                currencyCode);

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
                hasBuyerAsTakerWithoutDeposit ? HavenoUtils.MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT : HavenoUtils.MAKER_FEE_PCT,
                hasBuyerAsTakerWithoutDeposit ? 0d : HavenoUtils.TAKER_FEE_PCT,
                HavenoUtils.PENALTY_FEE_PCT,
                hasBuyerAsTakerWithoutDeposit ? 0d : securityDepositPct, // buyer as taker security deposit is optional for private offers
                securityDepositPct,
                baseCurrencyCode,
                counterCurrencyCode,
                paymentAccount.getPaymentMethod().getId(),
                paymentAccount.getId(),
                countryCode,
                acceptedCountryCodes,
                bankId,
                acceptedBanks,
                Version.VERSION,
                xmrWalletService.getHeight(),
                maxTradeLimit,
                maxTradePeriod,
                useAutoClose,
                useReOpenAfterAutoClose,
                upperClosePrice,
                lowerClosePrice,
                isPrivateOffer,
                challengeHash,
                extraDataMap,
                Version.TRADE_PROTOCOL_VERSION,
                null,
                null,
                null,
                extraInfo);
        Offer offer = new Offer(offerPayload);
        offer.setPriceFeedService(priceFeedService);
        offer.setChallenge(challenge);
        return offer;
    }

    // TODO: add trigger price?
    public Offer createClonedOffer(Offer sourceOffer,
                            String currencyCode,
                            Price fixedPrice,
                            boolean useMarketBasedPrice,
                            double marketPriceMargin,
                            PaymentAccount paymentAccount,
                            String extraInfo) {
        log.info("Cloning offer with sourceId={}, " +
                        "currencyCode={}, " +
                        "fixedPrice={}, " +
                        "useMarketBasedPrice={}, " +
                        "marketPriceMargin={}, " +
                        "extraInfo={}",
                sourceOffer.getId(),
                currencyCode,
                fixedPrice == null ? null : fixedPrice.getValue(),
                useMarketBasedPrice,
                marketPriceMargin,
                extraInfo);

        OfferPayload sourceOfferPayload = sourceOffer.getOfferPayload();
        String newOfferId = OfferUtil.getRandomOfferId();
        Offer editedOffer = createAndGetOffer(newOfferId,
                sourceOfferPayload.getDirection(),
                currencyCode,
                BigInteger.valueOf(sourceOfferPayload.getAmount()),
                BigInteger.valueOf(sourceOfferPayload.getMinAmount()),
                fixedPrice,
                useMarketBasedPrice,
                marketPriceMargin,
                sourceOfferPayload.getSellerSecurityDepositPct(),
                paymentAccount,
                sourceOfferPayload.isPrivateOffer(),
                sourceOfferPayload.isBuyerAsTakerWithoutDeposit(),
                extraInfo);

        // generate one-time challenge for private offer
        String challenge = null;
        String challengeHash = null;
        if (sourceOfferPayload.isPrivateOffer()) {
            challenge = HavenoUtils.generateChallenge();
            challengeHash = HavenoUtils.getChallengeHash(challenge);
        }
        
        OfferPayload editedOfferPayload = editedOffer.getOfferPayload();
        long date = new Date().getTime();
        OfferPayload clonedOfferPayload = new OfferPayload(newOfferId,
                date,
                sourceOfferPayload.getOwnerNodeAddress(),
                sourceOfferPayload.getPubKeyRing(),
                sourceOfferPayload.getDirection(),
                editedOfferPayload.getPrice(),
                editedOfferPayload.getMarketPriceMarginPct(),
                editedOfferPayload.isUseMarketBasedPrice(),
                sourceOfferPayload.getAmount(),
                sourceOfferPayload.getMinAmount(),
                sourceOfferPayload.getMakerFeePct(),
                sourceOfferPayload.getTakerFeePct(),
                sourceOfferPayload.getPenaltyFeePct(),
                sourceOfferPayload.getBuyerSecurityDepositPct(),
                sourceOfferPayload.getSellerSecurityDepositPct(),
                editedOfferPayload.getBaseCurrencyCode(),
                editedOfferPayload.getCounterCurrencyCode(),
                editedOfferPayload.getPaymentMethodId(),
                editedOfferPayload.getMakerPaymentAccountId(),
                editedOfferPayload.getCountryCode(),
                editedOfferPayload.getAcceptedCountryCodes(),
                editedOfferPayload.getBankId(),
                editedOfferPayload.getAcceptedBankIds(),
                editedOfferPayload.getVersionNr(),
                sourceOfferPayload.getBlockHeightAtOfferCreation(),
                editedOfferPayload.getMaxTradeLimit(),
                editedOfferPayload.getMaxTradePeriod(),
                sourceOfferPayload.isUseAutoClose(),
                sourceOfferPayload.isUseReOpenAfterAutoClose(),
                sourceOfferPayload.getLowerClosePrice(),
                sourceOfferPayload.getUpperClosePrice(),
                sourceOfferPayload.isPrivateOffer(),
                challengeHash,
                editedOfferPayload.getExtraDataMap(),
                sourceOfferPayload.getProtocolVersion(),
                null,
                null,
                sourceOfferPayload.getReserveTxKeyImages(),
                editedOfferPayload.getExtraInfo());
        Offer clonedOffer = new Offer(clonedOfferPayload);
        clonedOffer.setPriceFeedService(priceFeedService);
        clonedOffer.setChallenge(challenge);
        clonedOffer.setState(Offer.State.AVAILABLE);
        return clonedOffer;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean isMarketPriceAvailable(String currencyCode) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        return marketPrice != null && marketPrice.isExternallyProvidedPrice();
    }
}
