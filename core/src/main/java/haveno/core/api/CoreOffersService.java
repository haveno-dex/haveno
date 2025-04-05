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

package haveno.core.api;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.crypto.KeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import static haveno.common.util.MathUtils.exactMultiply;
import static haveno.common.util.MathUtils.roundDoubleToLong;
import static haveno.common.util.MathUtils.scaleUpByPowerOf10;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import static haveno.core.offer.OfferDirection.BUY;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OfferFilterService.Result;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import static haveno.core.payment.PaymentAccountUtil.isPaymentAccountValidForOffer;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import static java.lang.String.format;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import static java.util.Comparator.comparing;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;

@Singleton
@Slf4j
public class CoreOffersService {

    private final Supplier<Comparator<Offer>> priceComparator = () -> comparing(Offer::getPrice);
    private final Supplier<Comparator<OpenOffer>> openOfferPriceComparator = () -> comparing(openOffer -> openOffer.getOffer().getPrice());
    private final Supplier<Comparator<Offer>> reversePriceComparator = () -> comparing(Offer::getPrice).reversed();

    private final CoreContext coreContext;
    private final KeyRing keyRing;
    // Dependencies on core api services in this package must be kept to an absolute
    // minimum, but some trading functions require an unlocked wallet's key, so an
    // exception is made in this case.
    private final CoreWalletsService coreWalletsService;
    private final CreateOfferService createOfferService;
    private final OfferBookService offerBookService;
    private final OfferFilterService offerFilter;
    private final OpenOfferManager openOfferManager;
    private final User user;

    @Inject
    public CoreOffersService(CoreContext coreContext,
                             KeyRing keyRing,
                             CoreWalletsService coreWalletsService,
                             CreateOfferService createOfferService,
                             OfferBookService offerBookService,
                             OfferFilterService offerFilter,
                             OpenOfferManager openOfferManager,
                             OfferUtil offerUtil,
                             User user) {
        this.coreContext = coreContext;
        this.keyRing = keyRing;
        this.coreWalletsService = coreWalletsService;
        this.createOfferService = createOfferService;
        this.offerBookService = offerBookService;
        this.offerFilter = offerFilter;
        this.openOfferManager = openOfferManager;
        this.user = user;
    }

    // excludes my offers
    List<Offer> getOffers() {
        List<Offer> offers = new ArrayList<>(offerBookService.getOffers()).stream()
                .filter(o -> !o.isMyOffer(keyRing))
                .filter(o -> {
                    Result result = offerFilter.canTakeOffer(o, coreContext.isApiUser());
                    return result.isValid() || result == Result.HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER;
                })
                .collect(Collectors.toList());
        return offers;
    }

    List<Offer> getOffers(String direction, String currencyCode) {
        return getOffers().stream()
                .filter(o -> offerMatchesDirectionAndCurrency(o, direction, currencyCode))
                .sorted(priceComparator(direction))
                .collect(Collectors.toList());
    }

    Offer getOffer(String id) {
        return getOffers().stream()
                .filter(o -> o.getId().equals(id))
                .findAny().orElseThrow(() ->
                        new IllegalStateException(format("offer with id '%s' not found", id)));
    }

    List<OpenOffer> getMyOffers() {
        return openOfferManager.getOpenOffers().stream()
                .filter(o -> o.getOffer().isMyOffer(keyRing))
                .collect(Collectors.toList());
    };

    List<OpenOffer> getMyOffers(String direction, String currencyCode) {
        return getMyOffers().stream()
                .filter(o -> offerMatchesDirectionAndCurrency(o.getOffer(), direction, currencyCode))
                .sorted(openOfferPriceComparator(direction, CurrencyUtil.isTraditionalCurrency(currencyCode)))
                .collect(Collectors.toList());
    }

    OpenOffer getMyOffer(String id) {
        return openOfferManager.getOpenOffer(id)
                .filter(open -> open.getOffer().isMyOffer(keyRing))
                .orElseThrow(() ->
                        new IllegalStateException(format("openoffer with id '%s' not found", id)));
    }

    void postOffer(String currencyCode,
                             String directionAsString,
                             String priceAsString,
                             boolean useMarketBasedPrice,
                             double marketPriceMargin,
                             long amountAsLong,
                             long minAmountAsLong,
                             double securityDepositPct,
                             String triggerPriceAsString,
                             boolean reserveExactAmount,
                             String paymentAccountId,
                             boolean isPrivateOffer,
                             boolean buyerAsTakerWithoutDeposit,
                             String extraInfo,
                             String sourceOfferId,
                             Consumer<Offer> resultHandler,
                             ErrorMessageHandler errorMessageHandler) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();

        PaymentAccount paymentAccount = user.getPaymentAccount(paymentAccountId);
        if (paymentAccount == null) throw new IllegalArgumentException(format("payment account with id %s not found", paymentAccountId));

        // clone offer if sourceOfferId given
        if (!sourceOfferId.isEmpty()) {
            cloneOffer(sourceOfferId,
                    currencyCode,
                    priceAsString,
                    useMarketBasedPrice,
                    marketPriceMargin,
                    triggerPriceAsString,
                    paymentAccountId,
                    extraInfo,
                    resultHandler,
                    errorMessageHandler);
            return;
        }

        // create new offer
        String upperCaseCurrencyCode = currencyCode.toUpperCase();
        String offerId = createOfferService.getRandomOfferId();
        OfferDirection direction = OfferDirection.valueOf(directionAsString.toUpperCase());
        Price price = priceAsString.isEmpty() ? null : Price.valueOf(upperCaseCurrencyCode, priceStringToLong(priceAsString, upperCaseCurrencyCode));
        BigInteger amount = BigInteger.valueOf(amountAsLong);
        BigInteger minAmount = BigInteger.valueOf(minAmountAsLong);
        Offer offer = createOfferService.createAndGetOffer(offerId,
                direction,
                upperCaseCurrencyCode,
                amount,
                minAmount,
                price,
                useMarketBasedPrice,
                exactMultiply(marketPriceMargin, 0.01),
                securityDepositPct,
                paymentAccount,
                isPrivateOffer,
                buyerAsTakerWithoutDeposit,
                extraInfo);

        verifyPaymentAccountIsValidForNewOffer(offer, paymentAccount);

        placeOffer(offer,
                triggerPriceAsString,
                true,
                reserveExactAmount,
                null,
                transaction -> resultHandler.accept(offer),
                errorMessageHandler);
    }

    private void cloneOffer(String sourceOfferId,
                    String currencyCode,
                    String priceAsString,
                    boolean useMarketBasedPrice,
                    double marketPriceMargin,
                    String triggerPriceAsString,
                    String paymentAccountId,
                    String extraInfo,
                    Consumer<Offer> resultHandler,
                    ErrorMessageHandler errorMessageHandler) {

        // get source offer
        OpenOffer sourceOpenOffer = getMyOffer(sourceOfferId);
        Offer sourceOffer = sourceOpenOffer.getOffer();

        // get trade currency (default source currency)
        if (currencyCode.isEmpty()) currencyCode = sourceOffer.getOfferPayload().getBaseCurrencyCode();
        if (currencyCode.equalsIgnoreCase(Res.getBaseCurrencyCode())) currencyCode = sourceOffer.getOfferPayload().getCounterCurrencyCode();
        String upperCaseCurrencyCode = currencyCode.toUpperCase();

        // get price (default source price)
        Price price = useMarketBasedPrice ? null : priceAsString.isEmpty() ? sourceOffer.isUseMarketBasedPrice() ? null : sourceOffer.getPrice() : Price.parse(upperCaseCurrencyCode, priceAsString);
        if (price == null) useMarketBasedPrice = true;

        // get payment account
        if (paymentAccountId.isEmpty()) paymentAccountId = sourceOffer.getOfferPayload().getMakerPaymentAccountId();
        PaymentAccount paymentAccount = user.getPaymentAccount(paymentAccountId);
        if (paymentAccount == null) throw new IllegalArgumentException(format("payment acRcount with id %s not found", paymentAccountId));

        // get extra info
        if (extraInfo.isEmpty()) extraInfo = sourceOffer.getOfferPayload().getExtraInfo();

        // create cloned offer
        Offer offer = createOfferService.createClonedOffer(sourceOffer,
                upperCaseCurrencyCode,
                price,
                useMarketBasedPrice,
                exactMultiply(marketPriceMargin, 0.01),
                paymentAccount,
                extraInfo);
        
        // verify cloned offer
        verifyPaymentAccountIsValidForNewOffer(offer, paymentAccount);

        // place offer
        placeOffer(offer,
                triggerPriceAsString,
                true,
                false, // ignored when cloning
                sourceOfferId,
                transaction -> resultHandler.accept(offer),
                errorMessageHandler);
    }

    // TODO: this implementation is missing; implement.
    Offer editOffer(String offerId,
                    String currencyCode,
                    OfferDirection direction,
                    Price price,
                    boolean useMarketBasedPrice,
                    double marketPriceMargin,
                    BigInteger amount,
                    BigInteger minAmount,
                    double securityDepositPct,
                    PaymentAccount paymentAccount,
                    boolean isPrivateOffer,
                    boolean buyerAsTakerWithoutDeposit,
                    String extraInfo) {
        return createOfferService.createAndGetOffer(offerId,
                direction,
                currencyCode.toUpperCase(),
                amount,
                minAmount,
                price,
                useMarketBasedPrice,
                exactMultiply(marketPriceMargin, 0.01),
                securityDepositPct,
                paymentAccount,
                isPrivateOffer,
                buyerAsTakerWithoutDeposit,
                extraInfo);
    }

    void cancelOffer(String id, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Offer offer = getMyOffer(id).getOffer();
        openOfferManager.removeOffer(offer, resultHandler, errorMessageHandler);
    }

    // -------------------------- PRIVATE HELPERS -----------------------------

    private void verifyPaymentAccountIsValidForNewOffer(Offer offer, PaymentAccount paymentAccount) {
        if (!isPaymentAccountValidForOffer(offer, paymentAccount)) {
            String error = format("cannot create %s offer with payment account %s",
                    offer.getOfferPayload().getCounterCurrencyCode(),
                    paymentAccount.getId());
            throw new IllegalStateException(error);
        }
    }

    private void placeOffer(Offer offer,
                            String triggerPriceAsString,
                            boolean useSavingsWallet,
                            boolean reserveExactAmount,
                            String sourceOfferId,
                            Consumer<Transaction> resultHandler,
                            ErrorMessageHandler errorMessageHandler) {
        long triggerPriceAsLong = PriceUtil.getMarketPriceAsLong(triggerPriceAsString, offer.getCurrencyCode());
        openOfferManager.placeOffer(offer,
                useSavingsWallet,
                triggerPriceAsLong,
                reserveExactAmount,
                true,
                sourceOfferId,
                resultHandler::accept,
                errorMessageHandler);
    }

    private boolean offerMatchesDirectionAndCurrency(Offer offer,
                                                     String direction,
                                                     String currencyCode) {
        if ("".equals(direction)) direction = null;
        if ("".equals(currencyCode)) currencyCode = null;
        var offerOfWantedDirection = direction == null || offer.getDirection().name().equalsIgnoreCase(direction);
        var counterAssetCode = CurrencyUtil.isCryptoCurrency(currencyCode) ? offer.getOfferPayload().getBaseCurrencyCode() : offer.getOfferPayload().getCounterCurrencyCode();
        var offerInWantedCurrency = currencyCode == null || counterAssetCode.equalsIgnoreCase(currencyCode);
        return offerOfWantedDirection && offerInWantedCurrency;
    }

    private Comparator<Offer> priceComparator(String direction) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        return direction.equalsIgnoreCase(BUY.name())
                ? reversePriceComparator.get()
                : priceComparator.get();
    }

    private Comparator<OpenOffer> openOfferPriceComparator(String direction, boolean isTraditional) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        if (isTraditional)
            return direction.equalsIgnoreCase(OfferDirection.BUY.name())
                    ? openOfferPriceComparator.get().reversed()
                    : openOfferPriceComparator.get();
        else
            return direction.equalsIgnoreCase(OfferDirection.SELL.name())
                    ? openOfferPriceComparator.get().reversed()
                    : openOfferPriceComparator.get();
    }

    private long priceStringToLong(String priceAsString, String currencyCode) {
        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ? TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
        double priceAsDouble = new BigDecimal(priceAsString).doubleValue();
        double scaled = scaleUpByPowerOf10(priceAsDouble, precision);
        return roundDoubleToLong(scaled);
    }
}
