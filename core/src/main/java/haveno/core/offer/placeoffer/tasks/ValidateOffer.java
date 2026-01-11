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

package haveno.core.offer.placeoffer.tasks;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.payment.PaymentAccount;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.user.User;
import org.bitcoinj.core.Coin;

import java.math.BigInteger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class ValidateOffer extends Task<PlaceOfferModel> {
    public ValidateOffer(TaskRunner<PlaceOfferModel> taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOpenOffer().getOffer();
        try {
            runInterceptHook();

            validateOffer(offer, model.getAccountAgeWitnessService(), model.getUser());

            complete();
        } catch (Exception e) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + e.getMessage());
            failed(e);
        }
    }

    public static void validateOffer(Offer offer, AccountAgeWitnessService accountAgeWitnessService, User user) {

        // Coins
        checkBINotNullOrZero(offer.getAmount(), "Amount");
        checkBINotNullOrZero(offer.getMinAmount(), "MinAmount");
        //checkCoinNotNullOrZero(offer.getTxFee(), "txFee"); // TODO: remove from data model
        checkBINotNullOrZero(offer.getMaxTradeLimit(), "MaxTradeLimit");
        if (offer.getMakerFeePct() < 0) throw new IllegalArgumentException("Maker fee must be >= 0% but was " + offer.getMakerFeePct());
        if (offer.getTakerFeePct() < 0) throw new IllegalArgumentException("Taker fee must be >= 0% but was " + offer.getTakerFeePct());
        offer.isPrivateOffer();
        if (offer.isPrivateOffer()) {
            boolean isBuyerMaker = offer.getDirection() == OfferDirection.BUY;
            if (isBuyerMaker) {
                if (offer.getBuyerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Buyer security deposit percent must be positive but was " + offer.getBuyerSecurityDepositPct());
                if (offer.getSellerSecurityDepositPct() < 0) throw new IllegalArgumentException("Seller security deposit percent must be >= 0% but was " + offer.getSellerSecurityDepositPct());
            } else {
                if (offer.getBuyerSecurityDepositPct() < 0) throw new IllegalArgumentException("Buyer security deposit percent must be >= 0% but was " + offer.getBuyerSecurityDepositPct());
                if (offer.getSellerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Seller security deposit percent must be positive but was " + offer.getSellerSecurityDepositPct());
            }
        } else {
            if (offer.getBuyerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Buyer security deposit percent must be positive but was " + offer.getBuyerSecurityDepositPct());
            if (offer.getSellerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Seller security deposit percent must be positive but was " + offer.getSellerSecurityDepositPct());
        }


        // We remove those checks to be more flexible with future changes.
        /*checkArgument(offer.getMakerFee().value >= FeeService.getMinMakerFee(offer.isCurrencyForMakerFeeBtc()).value,
            "createOfferFee must not be less than FeeService.MIN_CREATE_OFFER_FEE_IN_BTC. " +
                "MakerFee=" + offer.getMakerFee().toFriendlyString());*/
        /*checkArgument(offer.getBuyerSecurityDeposit().value >= ProposalConsensus.getMinBuyerSecurityDeposit().value,
            "buyerSecurityDeposit must not be less than ProposalConsensus.MIN_BUYER_SECURITY_DEPOSIT. " +
                "buyerSecurityDeposit=" + offer.getBuyerSecurityDeposit().toFriendlyString());
        checkArgument(offer.getBuyerSecurityDeposit().value <= ProposalConsensus.getMaxBuyerSecurityDeposit().value,
            "buyerSecurityDeposit must not be larger than ProposalConsensus.MAX_BUYER_SECURITY_DEPOSIT. " +
                "buyerSecurityDeposit=" + offer.getBuyerSecurityDeposit().toFriendlyString());
        checkArgument(offer.getSellerSecurityDeposit().value == ProposalConsensus.getSellerSecurityDeposit().value,
            "sellerSecurityDeposit must be equal to ProposalConsensus.SELLER_SECURITY_DEPOSIT. " +
                "sellerSecurityDeposit=" + offer.getSellerSecurityDeposit().toFriendlyString());*/
        /*checkArgument(offer.getMinAmount().compareTo(ProposalConsensus.getMinTradeAmount()) >= 0,
            "MinAmount is less than " + ProposalConsensus.getMinTradeAmount().toFriendlyString());*/

        PaymentAccount paymentAccount = user.getPaymentAccount(offer.getMakerPaymentAccountId());
        checkArgument(paymentAccount != null, "Payment account is null. makerPaymentAccountId=" + offer.getMakerPaymentAccountId());

        long maxAmount = accountAgeWitnessService.getMyTradeLimit(user.getPaymentAccount(offer.getMakerPaymentAccountId()), offer.getCounterCurrencyCode(), offer.getDirection(), offer.hasBuyerAsTakerWithoutDeposit());
        checkArgument(offer.getAmount().longValueExact() <= maxAmount,
                "Amount is larger than " + HavenoUtils.atomicUnitsToXmr(maxAmount) + " XMR");
        checkArgument(offer.getAmount().compareTo(offer.getMinAmount()) >= 0, "MinAmount is larger than Amount");

        checkNotNull(offer.getPrice(), "Price is null");
        if (!offer.isUseMarketBasedPrice()) checkArgument(offer.getPrice().isPositive(),
                "Price must be positive unless using market based price. price=" + offer.getPrice().toFriendlyString());

        checkArgument(offer.getOfferPayload().getMarketPriceMarginPct() > -1 && offer.getOfferPayload().getMarketPriceMarginPct() < 1,
                "Market price margin must be greater than -100% and less than 100% but was " + (offer.getOfferPayload().getMarketPriceMarginPct() * 100) + "%");

        checkArgument(offer.getDate().getTime() > 0,
                "Date must not be 0. date=" + offer.getDate().toString());

        checkNotNull(offer.getCounterCurrencyCode(), "Currency is null");
        checkNotNull(offer.getDirection(), "Direction is null");
        checkNotNull(offer.getId(), "Id is null");
        checkNotNull(offer.getPubKeyRing(), "pubKeyRing is null");
        checkNotNull(offer.getMinAmount(), "MinAmount is null");
        checkNotNull(offer.getPrice(), "Price is null");
        checkNotNull(offer.getVersionNr(), "VersionNr is null");
        checkArgument(offer.getMaxTradePeriod() > 0,
                "maxTradePeriod must be positive. maxTradePeriod=" + offer.getMaxTradePeriod());
        // TODO check upper and lower bounds for fiat
        // TODO check rest of new parameters
    }

    private static void checkBINotNullOrZero(BigInteger value, String name) {
        checkNotNull(value, name + " is null");
        checkArgument(value.compareTo(BigInteger.ZERO) > 0,
                name + " must be positive. " + name + "=" + value);
    }

    private static void checkCoinNotNullOrZero(Coin value, String name) {
        checkNotNull(value, name + " is null");
        checkArgument(value.isPositive(),
                name + " must be positive. " + name + "=" + value.toFriendlyString());
    }

    private static String nonEmptyStringOf(String value) {
        checkNotNull(value);
        checkArgument(value.length() > 0);
        return value;
    }

    private static long nonNegativeLongOf(long value) {
        checkArgument(value >= 0);
        return value;
    }

    private static Coin nonZeroCoinOf(Coin value) {
        checkNotNull(value);
        checkArgument(!value.isZero());
        return value;
    }

    private static Coin positiveCoinOf(Coin value) {
        checkNotNull(value);
        checkArgument(value.isPositive());
        return value;
    }

    private static void checkTradeId(String tradeId, TradeMessage tradeMessage) {
        checkArgument(tradeId.equals(tradeMessage.getOfferId()));
    }
}
