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
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.network.p2p.P2PService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

@Slf4j
@Singleton
public class OfferFilterService {
    private final User user;
    private final P2PService p2PService;
    private final Preferences preferences;
    private final FilterManager filterManager;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Map<String, Boolean> insufficientCounterpartyTradeLimitCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> myInsufficientTradeLimitCache = new HashMap<>();
    private Set<PaymentAccount> myInsufficientTradeLimitCacheAccounts; // accounts which the cached results depend on

    @Inject
    public OfferFilterService(User user,
                       P2PService p2PService,
                       Preferences preferences,
                       FilterManager filterManager,
                       AccountAgeWitnessService accountAgeWitnessService) {
        this.user = user;
        this.p2PService = p2PService;
        this.preferences = preferences;
        this.filterManager = filterManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
    }

    public enum Result {
        VALID(true),
        API_DISABLED,
        HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER,
        HAS_NOT_SAME_PROTOCOL_VERSION,
        IS_IGNORED,
        IS_OFFER_BANNED,
        IS_CURRENCY_BANNED,
        IS_PAYMENT_METHOD_BANNED,
        IS_NODE_ADDRESS_BANNED,
        REQUIRE_UPDATE_TO_NEW_VERSION,
        IS_INSUFFICIENT_COUNTERPARTY_TRADE_LIMIT,
        IS_MY_INSUFFICIENT_TRADE_LIMIT,
        ARBITRATOR_NOT_VALIDATED,
        SIGNATURE_NOT_VALIDATED,
        RESERVE_FUNDS_SPENT,
        HAS_NO_SIGNED_PAYMENT_ACCOUNT;

        @Getter
        private final boolean isValid;

        Result(boolean isValid) {
            this.isValid = isValid;
        }

        Result() {
            this(false);
        }
    }

    public Result canTakeOffer(Offer offer, boolean isTakerApiUser) {
        if (isTakerApiUser && filterManager.getFilter() != null && filterManager.getFilter().isDisableApi()) {
            return Result.API_DISABLED;
        }
        if (!hasSameProtocolVersion(offer)) {
            return Result.HAS_NOT_SAME_PROTOCOL_VERSION;
        }
        if (isIgnored(offer)) {
            return Result.IS_IGNORED;
        }
        if (isOfferBanned(offer)) {
            return Result.IS_OFFER_BANNED;
        }
        if (isCurrencyBanned(offer)) {
            return Result.IS_CURRENCY_BANNED;
        }
        if (isPaymentMethodBanned(offer)) {
            return Result.IS_PAYMENT_METHOD_BANNED;
        }
        if (isNodeAddressBanned(offer)) {
            return Result.IS_NODE_ADDRESS_BANNED;
        }
        if (requireUpdateToNewVersion()) {
            return Result.REQUIRE_UPDATE_TO_NEW_VERSION;
        }
        if (isInsufficientCounterpartyTradeLimit(offer)) {
            return Result.IS_INSUFFICIENT_COUNTERPARTY_TRADE_LIMIT;
        }
        if (isMyInsufficientTradeLimit(offer)) {
            return Result.IS_MY_INSUFFICIENT_TRADE_LIMIT;
        }
        if (!hasValidArbitrator(offer)) {
            return Result.ARBITRATOR_NOT_VALIDATED;
        }
        if (!hasValidSignature(offer)) {
            return Result.SIGNATURE_NOT_VALIDATED;
        }
        if (isReservedFundsSpent(offer)) {
            return Result.RESERVE_FUNDS_SPENT;
        }
        if (!isAnyPaymentAccountValidForOffer(offer)) {
            return Result.HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER;
        }
        if (!isSignedTakerRequirementMet(offer)) {
            return Result.HAS_NO_SIGNED_PAYMENT_ACCOUNT;
        }

        return Result.VALID;
    }

    public boolean isAnyPaymentAccountValidForOffer(Offer offer) {
        return user.getPaymentAccounts() != null &&
                PaymentAccountUtil.isAnyPaymentAccountValidForOffer(offer, user.getPaymentAccounts());
    }

    // signed taker offers can only be taken with a signed payment account that also meets the offer's limits
    public boolean isSignedTakerRequirementMet(Offer offer) {
        return !offer.requiresSignedTaker() ||
                !PaymentAccountUtil.getPossiblePaymentAccounts(offer, user.getPaymentAccounts(), accountAgeWitnessService).isEmpty();
    }

    public boolean hasSameProtocolVersion(Offer offer) {
        return offer.getProtocolVersion() == Version.TRADE_PROTOCOL_VERSION;
    }

    public boolean isIgnored(Offer offer) {
        return preferences.getIgnoreTradersList().stream()
                .anyMatch(i -> i.equals(offer.getMakerNodeAddress().getFullAddress()));
    }

    public boolean isOfferBanned(Offer offer) {
        return filterManager.isOfferIdBanned(offer.getId());
    }

    public boolean isCurrencyBanned(Offer offer) {
        return filterManager.isCurrencyBanned(offer.getCounterCurrencyCode());
    }

    public boolean isPaymentMethodBanned(Offer offer) {
        return filterManager.isPaymentMethodBanned(offer.getPaymentMethod());
    }

    public boolean isNodeAddressBanned(Offer offer) {
        return filterManager.isNodeAddressBanned(offer.getMakerNodeAddress());
    }

    public boolean requireUpdateToNewVersion() {
        return filterManager.requireUpdateToNewVersionForTrading();
    }

    // This call is a bit expensive so we cache results
    public boolean isInsufficientCounterpartyTradeLimit(Offer offer) {
        String cacheKey = getCacheKey(offer);
        Boolean cached = insufficientCounterpartyTradeLimitCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        boolean result = offer.isTraditionalOffer() &&
                !accountAgeWitnessService.verifyPeersTradeAmount(offer, offer.getAmount(),
                        errorMessage -> {
                        });
        insufficientCounterpartyTradeLimitCache.put(cacheKey, result);
        return result;
    }

    // This call is a bit expensive so we cache results
    public boolean isMyInsufficientTradeLimit(Offer offer) {
        String cacheKey = getCacheKey(offer);
        Set<PaymentAccount> accounts = user.getPaymentAccounts();
        synchronized (myInsufficientTradeLimitCache) {

            // the user replaces its account set on every change, which resets the cache
            if (accounts != myInsufficientTradeLimitCacheAccounts) {
                myInsufficientTradeLimitCache.clear();
                myInsufficientTradeLimitCacheAccounts = accounts;
            }
            Boolean cached = myInsufficientTradeLimitCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        Optional<PaymentAccount> accountOptional = PaymentAccountUtil.getMostMaturePaymentAccountForOffer(offer,
                accounts,
                accountAgeWitnessService);
        long myTradeLimit = accountOptional
                .map(paymentAccount -> accountAgeWitnessService.getMyTradeLimit(paymentAccount,
                        offer.getCounterCurrencyCode(), offer.getMirroredDirection(), offer.hasBuyerAsTakerWithoutDeposit()))
                .orElse(0L);
        long offerMinAmount = offer.getMinAmount().longValueExact();
        log.debug("isInsufficientTradeLimit accountOptional={}, myTradeLimit={}, offerMinAmount={}, ",
                accountOptional.isPresent() ? accountOptional.get().getAccountName() : "null",
                Coin.valueOf(myTradeLimit).toFriendlyString(),
                Coin.valueOf(offerMinAmount).toFriendlyString());
        boolean result = offer.isTraditionalOffer() &&
                accountOptional.isPresent() &&
                myTradeLimit < offerMinAmount;
        synchronized (myInsufficientTradeLimitCache) {
            if (accounts == myInsufficientTradeLimitCacheAccounts) myInsufficientTradeLimitCache.put(cacheKey, result);
        }
        return result;
    }

    // an edited offer keeps its id, so results are cached by the hash of the payload
    private static String getCacheKey(Offer offer) {
        return Utilities.encodeToHex(offer.getOfferPayload().getHash());
    }

    private boolean hasValidArbitrator(Offer offer) {
        Arbitrator arbitrator = getArbitrator(offer);
        return arbitrator != null;
    }

    private Arbitrator getArbitrator(Offer offer) {
        
        // get arbitrator by address
        Arbitrator arbitrator = user.getAcceptedArbitratorByAddress(offer.getOfferPayload().getArbitratorSigner());
        if (arbitrator != null) return arbitrator;

        // check if we are the signing arbitrator
        Arbitrator thisArbitrator = user.getRegisteredArbitrator();
        if (thisArbitrator != null && thisArbitrator.getNodeAddress().equals(offer.getOfferPayload().getArbitratorSigner())) return thisArbitrator;

        // cannot get arbitrator
        return null;
    }

    private boolean hasValidSignature(Offer offer) {
        Arbitrator arbitrator = getArbitrator(offer);
        if (arbitrator == null) return false;
        return HavenoUtils.isArbitratorSignatureValid(offer.getOfferPayload(), arbitrator);
    }

    public boolean isReservedFundsSpent(Offer offer) {
        return offer.isReservedFundsSpent();
    }
}
