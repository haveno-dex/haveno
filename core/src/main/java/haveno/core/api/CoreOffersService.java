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
import static haveno.common.util.MathUtils.roundDoubleToLong;
import static haveno.common.util.MathUtils.scaleUpByPowerOf10;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
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
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;

import static haveno.core.payment.PaymentAccountUtil.isPaymentAccountValidForOffer;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import static java.lang.String.format;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;

@Singleton
@Slf4j
public class CoreOffersService {

    private static final long WAIT_FOR_EDIT_REMOVAL_MS = 5000;

    private final Supplier<Comparator<Offer>> priceComparator =
        () -> Comparator.comparing(
                Offer::getPrice,
                Comparator.nullsLast(Comparator.naturalOrder())
        );
    private final Supplier<Comparator<OpenOffer>> openOfferPriceComparator =
        () -> Comparator.comparing(
                openOffer -> openOffer.getOffer().getPrice(),
                Comparator.nullsLast(Comparator.naturalOrder())
        );
    private final Supplier<Comparator<Offer>> reversePriceComparator =
        () -> Comparator.comparing(
                Offer::getPrice,
                Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed();

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
    private final PriceFeedService priceFeedService;
    private final CorePersistenceProtoResolver corePersistenceProtoResolver;

    @Inject
    public CoreOffersService(CoreContext coreContext,
                             KeyRing keyRing,
                             CoreWalletsService coreWalletsService,
                             CreateOfferService createOfferService,
                             OfferBookService offerBookService,
                             OfferFilterService offerFilter,
                             OpenOfferManager openOfferManager,
                             OfferUtil offerUtil,
                             User user,
                             PriceFeedService priceFeedService,
                             CorePersistenceProtoResolver corePersistenceProtoResolver) {
        this.coreContext = coreContext;
        this.keyRing = keyRing;
        this.coreWalletsService = coreWalletsService;
        this.createOfferService = createOfferService;
        this.offerBookService = offerBookService;
        this.offerFilter = offerFilter;
        this.openOfferManager = openOfferManager;
        this.user = user;
        this.priceFeedService = priceFeedService;
        this.corePersistenceProtoResolver = corePersistenceProtoResolver;
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
                .sorted(openOfferPriceComparator(direction))
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
                             double marketPriceMarginPct,
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
                    marketPriceMarginPct,
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
                marketPriceMarginPct,
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
                    double marketPriceMarginPct,
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
        if (paymentAccount == null) throw new IllegalArgumentException(format("payment account with id %s not found", paymentAccountId));

        // get extra info
        if (extraInfo.isEmpty()) extraInfo = sourceOffer.getOfferPayload().getExtraInfo();

        // create cloned offer
        Offer offer = createOfferService.createClonedOffer(sourceOffer,
                upperCaseCurrencyCode,
                price,
                useMarketBasedPrice,
                marketPriceMarginPct,
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

    void editOffer(String offerId,
                          String currencyCode,
                          String priceAsString,
                          boolean useMarketBasedPrice,
                          double marketPriceMarginPct,
                          String triggerPriceAsString,
                          String paymentAccountId,
                          String extraInfo,
                          Consumer<Offer> resultHandler, 
                          ErrorMessageHandler errorMessageHandler) {

        try {

            // collect offer info
            final OpenOffer openOffer = getMyOffer(offerId); 
            final Offer offer = openOffer.getOffer();
            final OfferPayload offerPayload = openOffer.getOffer().getOfferPayload();

            // cannot edit reserved offer
            if (openOffer.isReserved()) {
                throw new IllegalStateException("Cannot edit offer " + offer.getId() + " because it's reserved");
            }

            // get currency code
            if (currencyCode.isEmpty()) currencyCode = offer.getCounterCurrencyCode();
            String upperCaseCurrencyCode = currencyCode.toUpperCase();

            // get payment account
            if (paymentAccountId.isEmpty()) paymentAccountId = offer.getOfferPayload().getMakerPaymentAccountId();
            PaymentAccount paymentAccount = user.getPaymentAccount(paymentAccountId);
            if (paymentAccount == null) throw new IllegalArgumentException(format("payment account with id %s not found", paymentAccountId)); // TODO: invoke error handler for this and other offer methods

            // get preselected payment account
            PaymentAccount preselectedPaymentAccount = getPreselectedPaymentAccount(paymentAccount, currencyCode);

            // start edit offer
            OpenOffer.State initialState = openOffer.getState();
            openOfferManager.editOpenOfferStart(openOffer, () -> {
                try {

                    // wait for remove offer to propagate
                    // TODO: if offer edit is published too quickly, the remove message can be received after the add message, in which case the offer will be offline until the next offer refresh
                    HavenoUtils.waitFor(WAIT_FOR_EDIT_REMOVAL_MS);

                    // create edited offer
                    Price price = priceAsString.isEmpty() ? null : Price.valueOf(upperCaseCurrencyCode, priceStringToLong(priceAsString, upperCaseCurrencyCode));
                    final OfferPayload newOfferPayload = createOfferService.createAndGetOffer(offerId,
                            offer.getDirection(),
                            upperCaseCurrencyCode,
                            offer.getAmount(),
                            offer.getMinAmount(),
                            price,
                            useMarketBasedPrice,
                            marketPriceMarginPct,
                            offerPayload.getBuyerSecurityDepositPct(),
                            preselectedPaymentAccount,
                            offerPayload.isPrivateOffer(),
                            offer.hasBuyerAsTakerWithoutDeposit(),
                            extraInfo).getOfferPayload();
                    Offer editedOffer = getEditedOffer(openOffer, newOfferPayload);

                    // publish edited offer
                    long triggerPriceAsLong = PriceUtil.getMarketPriceAsLong(triggerPriceAsString, upperCaseCurrencyCode);
                    openOfferManager.editOpenOfferPublish(editedOffer, triggerPriceAsLong, initialState, () -> {
                        Offer updatedEditedOffer = openOfferManager.getOpenOffer(offerId).get().getOffer(); // get latest offer
                        resultHandler.accept(updatedEditedOffer);
                    }, (errorMsg) -> {
                        errorMessageHandler.handleErrorMessage(errorMsg);
                    });
                } catch (Exception e) {
                    errorMessageHandler.handleErrorMessage(format("Error editing offer %s: %s", offerId, e.getMessage()));
                    return;
                }
            }, errorMessageHandler);
        } catch (Exception e) {
            errorMessageHandler.handleErrorMessage(format("Error editing offer %s: %s", offerId, e.getMessage()));
            return;
        }
    }

    private PaymentAccount getPreselectedPaymentAccount(PaymentAccount paymentAccount, String currencyCode) {
        if (paymentAccount == null) throw new IllegalArgumentException("payment account cannot be null");
        if (currencyCode == null || currencyCode.isEmpty()) throw new IllegalArgumentException("currency code cannot be null or empty");
        Optional<TradeCurrency> optionalTradeCurrency = CurrencyUtil.getTradeCurrency(currencyCode);
        if (!optionalTradeCurrency.isPresent()) throw new IllegalArgumentException(format("cannot get trade currency for currency code %s", currencyCode));
        TradeCurrency selectedTradeCurrency = optionalTradeCurrency.get();
        PaymentAccount preselectedPaymentAccount = PaymentAccount.fromProto(paymentAccount.toProtoMessage(), corePersistenceProtoResolver);
        if (paymentAccount.getSingleTradeCurrency() != null)
            preselectedPaymentAccount.setSingleTradeCurrency(selectedTradeCurrency);
        else
            preselectedPaymentAccount.setSelectedTradeCurrency(selectedTradeCurrency);
        return preselectedPaymentAccount;
    }

    public Offer getEditedOffer(OpenOffer openOffer, OfferPayload newOfferPayload) {
        // editedPayload is a merge of the original offerPayload and newOfferPayload
        // fields which are editable are merged in from newOfferPayload (such as payment account details)
        // fields which cannot change (most importantly XMR amount) are sourced from the original offerPayload
        final OfferPayload offerPayload = openOffer.getOffer().getOfferPayload();
        final OfferPayload editedPayload = new OfferPayload(offerPayload.getId(),
                offerPayload.getDate(),
                offerPayload.getOwnerNodeAddress(),
                offerPayload.getPubKeyRing(),
                offerPayload.getDirection(),
                newOfferPayload.getPrice(),
                newOfferPayload.getMarketPriceMarginPct(),
                newOfferPayload.isUseMarketBasedPrice(),
                offerPayload.getAmount(),
                offerPayload.getMinAmount(),
                offerPayload.getMakerFeePct(),
                offerPayload.getTakerFeePct(),
                offerPayload.getPenaltyFeePct(),
                offerPayload.getBuyerSecurityDepositPct(),
                offerPayload.getSellerSecurityDepositPct(),
                newOfferPayload.getBaseCurrencyCode(),
                newOfferPayload.getCounterCurrencyCode(),
                newOfferPayload.getPaymentMethodId(),
                newOfferPayload.getMakerPaymentAccountId(),
                newOfferPayload.getCountryCode(),
                newOfferPayload.getAcceptedCountryCodes(),
                newOfferPayload.getBankId(),
                newOfferPayload.getAcceptedBankIds(),
                offerPayload.getVersionNr(),
                offerPayload.getBlockHeightAtOfferCreation(),
                offerPayload.getMaxTradeLimit(),
                offerPayload.getMaxTradePeriod(),
                offerPayload.isUseAutoClose(),
                offerPayload.isUseReOpenAfterAutoClose(),
                offerPayload.getLowerClosePrice(),
                offerPayload.getUpperClosePrice(),
                offerPayload.isPrivateOffer(),
                offerPayload.getChallengeHash(),
                offerPayload.getExtraDataMap(),
                offerPayload.getProtocolVersion(),
                offerPayload.getArbitratorSigner(),
                offerPayload.getArbitratorSignature(),
                offerPayload.getReserveTxKeyImages(),
                newOfferPayload.getExtraInfo());

        Offer editedOffer = new Offer(editedPayload);
        editedOffer.setPriceFeedService(priceFeedService);
        editedOffer.setState(Offer.State.AVAILABLE);
        return editedOffer;
    }

    void deactivateOffer(String offerId, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.deactivateOpenOffer(getMyOffer(offerId), false, resultHandler, errorMessageHandler);
    }

    void activateOffer(String offerId, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.activateOpenOffer(getMyOffer(offerId), resultHandler, errorMessageHandler);
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
        long triggerPriceAsLong = PriceUtil.getMarketPriceAsLong(triggerPriceAsString, offer.getCounterCurrencyCode());
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
        var offerInWantedCurrency = currencyCode == null || offer.getCounterCurrencyCode().equalsIgnoreCase(currencyCode);
        return offerOfWantedDirection && offerInWantedCurrency;
    }

    private Comparator<Offer> priceComparator(String direction) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        return direction.equalsIgnoreCase(BUY.name())
                ? reversePriceComparator.get()
                : priceComparator.get();
    }

    private Comparator<OpenOffer> openOfferPriceComparator(String direction) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        return direction.equalsIgnoreCase(OfferDirection.BUY.name())
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
