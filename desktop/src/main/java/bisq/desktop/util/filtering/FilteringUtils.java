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

package bisq.desktop.util.filtering;

import bisq.core.offer.Offer;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;

import org.apache.commons.lang3.StringUtils;

public class FilteringUtils {
    public static boolean match(Offer offer, String filterString) {
        if (StringUtils.containsIgnoreCase(offer.getId(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(offer.getPaymentMethod().getDisplayString(), filterString)) {
            return true;
        }
        return offer.getOfferFeePaymentTxId() != null && StringUtils.containsIgnoreCase(offer.getOfferFeePaymentTxId(), filterString);
    }

    public static boolean match(Trade trade, String filterString) {
        if (trade == null) {
            return false;
        }
        if (trade.getMaker().getDepositTxHash() != null && StringUtils.containsIgnoreCase(trade.getMaker().getDepositTxHash(), filterString)) {
            return true;
        }
        if (trade.getTaker().getDepositTxHash() != null && StringUtils.containsIgnoreCase(trade.getTaker().getDepositTxHash(), filterString)) {
            return true;
        }
        if (trade.getPayoutTxId() != null && StringUtils.containsIgnoreCase(trade.getPayoutTxId(), filterString)) {
            return true;
        }

        // match contract
        boolean isBuyerOnion = false;
        boolean isSellerOnion = false;
        boolean matchesBuyersPaymentAccountData = false;
        boolean matchesSellersPaymentAccountData = false;
        if (trade.getContract() != null) {
            isBuyerOnion = StringUtils.containsIgnoreCase(trade.getContract().getBuyerNodeAddress().getFullAddress(), filterString);
            isSellerOnion = StringUtils.containsIgnoreCase(trade.getContract().getSellerNodeAddress().getFullAddress(), filterString);
            matchesBuyersPaymentAccountData = trade.getBuyer().getPaymentAccountPayload() != null &&
                    StringUtils.containsIgnoreCase(trade.getBuyer().getPaymentAccountPayload().getPaymentDetails(), filterString);
            matchesSellersPaymentAccountData = trade.getSeller().getPaymentAccountPayload() != null &&
                    StringUtils.containsIgnoreCase(trade.getSeller().getPaymentAccountPayload().getPaymentDetails(), filterString);
        }
        return isBuyerOnion || isSellerOnion ||
                matchesBuyersPaymentAccountData || matchesSellersPaymentAccountData;
    }
}
