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

package haveno.core.offer.placeoffer.tasks;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.messages.TradeMessage;
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

            // Coins
            checkBINotNullOrZero(offer.getAmount(), "Amount");
            checkBINotNullOrZero(offer.getMinAmount(), "MinAmount");
            checkBINotNullOrZero(offer.getMakerFee(), "MakerFee");
            //checkCoinNotNullOrZero(offer.getTxFee(), "txFee"); // TODO: remove from data model
            checkBINotNullOrZero(offer.getMaxTradeLimit(), "MaxTradeLimit");
            if (offer.getBuyerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Buyer security deposit must be positive but was " + offer.getBuyerSecurityDepositPct());
            if (offer.getSellerSecurityDepositPct() <= 0) throw new IllegalArgumentException("Seller security deposit must be positive but was " + offer.getSellerSecurityDepositPct());

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

            long maxAmount = model.getAccountAgeWitnessService().getMyTradeLimit(model.getUser().getPaymentAccount(offer.getMakerPaymentAccountId()), offer.getCurrencyCode(), offer.getDirection());
            checkArgument(offer.getAmount().longValueExact() <= maxAmount,
                    "Amount is larger than " + HavenoUtils.atomicUnitsToXmr(offer.getPaymentMethod().getMaxTradeLimit(offer.getCurrencyCode())) + " XMR");
            checkArgument(offer.getAmount().compareTo(offer.getMinAmount()) >= 0, "MinAmount is larger than Amount");

            checkNotNull(offer.getPrice(), "Price is null");
            if (!offer.isUseMarketBasedPrice()) checkArgument(offer.getPrice().isPositive(),
                    "Price must be positive unless using market based price. price=" + offer.getPrice().toFriendlyString());

            checkArgument(offer.getDate().getTime() > 0,
                    "Date must not be 0. date=" + offer.getDate().toString());

            checkNotNull(offer.getCurrencyCode(), "Currency is null");
            checkNotNull(offer.getDirection(), "Direction is null");
            checkNotNull(offer.getId(), "Id is null");
            checkNotNull(offer.getPubKeyRing(), "pubKeyRing is null");
            checkNotNull(offer.getMinAmount(), "MinAmount is null");
            checkNotNull(offer.getPrice(), "Price is null");
            checkNotNull(offer.getMakerFee(), "MakerFee is null");
            checkNotNull(offer.getVersionNr(), "VersionNr is null");
            checkArgument(offer.getMaxTradePeriod() > 0,
                    "maxTradePeriod must be positive. maxTradePeriod=" + offer.getMaxTradePeriod());
            // TODO check upper and lower bounds for fiat
            // TODO check rest of new parameters

            complete();
        } catch (Exception e) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + e.getMessage());
            failed(e);
        }
    }

    public static void checkBINotNullOrZero(BigInteger value, String name) {
        checkNotNull(value, name + " is null");
        checkArgument(value.compareTo(BigInteger.valueOf(0)) > 0,
                name + " must be positive. " + name + "=" + value);
    }

    public static void checkCoinNotNullOrZero(Coin value, String name) {
        checkNotNull(value, name + " is null");
        checkArgument(value.isPositive(),
                name + " must be positive. " + name + "=" + value.toFriendlyString());
    }

    public static String nonEmptyStringOf(String value) {
        checkNotNull(value);
        checkArgument(value.length() > 0);
        return value;
    }

    public static long nonNegativeLongOf(long value) {
        checkArgument(value >= 0);
        return value;
    }

    public static Coin nonZeroCoinOf(Coin value) {
        checkNotNull(value);
        checkArgument(!value.isZero());
        return value;
    }

    public static Coin positiveCoinOf(Coin value) {
        checkNotNull(value);
        checkArgument(value.isPositive());
        return value;
    }

    public static void checkTradeId(String tradeId, TradeMessage tradeMessage) {
        checkArgument(tradeId.equals(tradeMessage.getTradeId()));
    }
}
