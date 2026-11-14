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

package haveno.desktop.main.offer.quicktrade;

import com.google.inject.Inject;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import haveno.core.util.coin.CoinUtil;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

// The answers given in the quick trade wizard and the currencies, payment methods and offers which fit them.
class QuickTradeModel {
    private final User user;
    private final OfferBook offerBook;
    private final OfferFilterService offerFilterService;
    private final OpenOfferManager openOfferManager;
    private final PriceUtil priceUtil;
    private final AccountAgeWitnessService accountAgeWitnessService;

    OfferDirection direction;
    TradeCurrency currency;
    // null for a crypto currency, which is always paid on its blockchain
    @Nullable
    PaymentMethod paymentMethod;

    // offers of the direction which the user can take, with an existing or a new payment account
    private List<Offer> offers = List.of();

    @Inject
    QuickTradeModel(User user,
                    OfferBook offerBook,
                    OfferFilterService offerFilterService,
                    OpenOfferManager openOfferManager,
                    PriceUtil priceUtil,
                    AccountAgeWitnessService accountAgeWitnessService) {
        this.user = user;
        this.offerBook = offerBook;
        this.offerFilterService = offerFilterService;
        this.openOfferManager = openOfferManager;
        this.priceUtil = priceUtil;
        this.accountAgeWitnessService = accountAgeWitnessService;
    }

    void refreshOffers() {
        offers = new ArrayList<>(offerBook.getOfferBookListItems()).stream()
                .map(OfferBookListItem::getOffer)
                .filter(offer -> offer.getDirection() != direction && !offer.isPrivateOffer() && offer.getPrice() != null)
                .filter(offer -> !openOfferManager.isMyOffer(offer) && isTakeable(offer))
                .collect(Collectors.toList());
    }

    // an offer missing only a payment account still fits if a new account could take it
    private boolean isTakeable(Offer offer) {
        OfferFilterService.Result result = offerFilterService.canTakeOffer(offer, false);
        return result.isValid() || result == OfferFilterService.Result.HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER &&
                !offer.requiresSignedTaker() && getNewAccountLimit(offer) >= offer.getMinAmount().longValueExact();
    }

    private long getNewAccountLimit(Offer offer) {
        return accountAgeWitnessService.getUnsignedTradeLimit(offer.getPaymentMethod(), offer.getCounterCurrencyCode(), offer.getMirroredDirection());
    }

    // every currency, the ones with the most offers first
    List<TradeCurrency> getCurrencies() {
        List<TradeCurrency> currencies = new ArrayList<>(CurrencyUtil.getAllSortedTraditionalCurrencies());
        currencies.addAll(CurrencyUtil.getAllSortedCryptoCurrencies());
        Map<String, Integer> counts = countOffers(offers, Offer::getCounterCurrencyCode);
        currencies.sort(Comparator.comparing(currency -> -counts.getOrDefault(currency.getCode(), 0)));
        return currencies;
    }

    int getNumOffers(TradeCurrency currency) {
        return (int) offers.stream().filter(offer -> offer.getCounterCurrencyCode().equals(currency.getCode())).count();
    }

    // the ways to pay in the currency, the ones with the most offers first
    List<PaymentMethod> getPaymentMethods() {
        if (CurrencyUtil.isCryptoCurrency(currency.getCode())) return List.of();
        Map<String, Integer> counts = countOffers(getCurrencyOffers(), Offer::getPaymentMethodId);
        return PaymentMethod.paymentMethods.stream()
                .filter(method -> method.isTraditional() && PaymentAccountUtil.supportsCurrency(method, currency))
                .sorted(Comparator.comparing((PaymentMethod method) -> -counts.getOrDefault(method.getId(), 0)).thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.toList());
    }

    int getNumOffers(PaymentMethod paymentMethod) {
        return (int) getCurrencyOffers().stream().filter(offer -> offer.getPaymentMethod().equals(paymentMethod)).count();
    }

    // the offers for the answers, the best price for the user first
    List<Offer> getOffers() {
        Comparator<Offer> byPrice = Comparator.comparing(Offer::getPrice);
        return getCurrencyOffers().stream()
                .filter(offer -> paymentMethod == null || offer.getPaymentMethod().equals(paymentMethod))
                .sorted(direction == OfferDirection.BUY ? byPrice : byPrice.reversed())
                .collect(Collectors.toList());
    }

    private List<Offer> getCurrencyOffers() {
        return offers.stream().filter(offer -> offer.getCounterCurrencyCode().equals(currency.getCode())).collect(Collectors.toList());
    }

    private static Map<String, Integer> countOffers(List<Offer> offers, Function<Offer, String> key) {
        Map<String, Integer> counts = new HashMap<>();
        offers.forEach(offer -> counts.merge(key.apply(offer), 1, Integer::sum));
        return counts;
    }

    // how far the offer's price is above the market price, negative if below
    Optional<Double> getPriceAboveMarket(Offer offer) {
        return priceUtil.getMarketBasedPrice(offer, direction).map(margin -> direction == OfferDirection.BUY ? margin : -margin);
    }

    // the most the user can trade on the offer, with the best fitting account or else a new one
    BigInteger getMaxAmount(Offer offer) {
        long limit = PaymentAccountUtil.getMostMaturePaymentAccountForOffer(offer, user.getPaymentAccounts(), accountAgeWitnessService)
                .map(account -> accountAgeWitnessService.getMyTradeLimit(account, offer.getCounterCurrencyCode(), offer.getMirroredDirection(), offer.hasBuyerAsTakerWithoutDeposit()))
                .orElseGet(() -> getNewAccountLimit(offer));
        return offer.getAmount().min(BigInteger.valueOf(limit));
    }

    // whether the offer can be traded for the volume, within the amounts the user can trade on it
    boolean isInRange(Offer offer, Volume volume) {
        BigInteger amount = offer.getPrice().getAmountByVolume(volume);
        return amount.compareTo(offer.getMinAmount()) >= 0 && amount.compareTo(getMaxAmount(offer)) <= 0;
    }

    // the amount of XMR to trade on the offer for a volume in its range, rounded as the take offer view rounds it
    BigInteger getAmount(Offer offer, Volume volume) {
        return CoinUtil.getRoundedAmount(offer.getPrice().getAmountByVolume(volume), offer.getPrice(), offer.getMinAmount(),
                getMaxAmount(offer), offer.getCounterCurrencyCode(), offer.getPaymentMethodId());
    }

    boolean canTakeOffer(Offer offer) {
        return offerFilterService.canTakeOffer(offer, false).isValid();
    }

    // the method a new account needs to take the offer, or to create one if no offer is given
    PaymentMethod getAccountPaymentMethod(@Nullable Offer offer) {
        return offer != null ? offer.getPaymentMethod() : paymentMethod != null ? paymentMethod : PaymentMethod.BLOCK_CHAINS;
    }

    // an account of the user to create an offer with for the answers, if any
    @Nullable
    PaymentAccount getAccountForNewOffer() {
        return user.getPaymentAccounts() == null ? null : user.getPaymentAccounts().stream()
                .filter(account -> paymentMethod == null ? account.getPaymentMethod().isBlockchain() : account.getPaymentMethod().equals(paymentMethod))
                .filter(account -> account.getTradeCurrencies().contains(currency))
                .findFirst()
                .orElse(null);
    }

    boolean hasAccount(PaymentMethod paymentMethod) {
        return user.getPaymentAccounts() != null && user.getPaymentAccounts().stream()
                .anyMatch(account -> account.getPaymentMethod().equals(paymentMethod) && account.getTradeCurrencies().contains(currency));
    }
}
