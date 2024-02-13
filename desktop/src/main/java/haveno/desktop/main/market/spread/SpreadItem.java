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

package haveno.desktop.main.market.spread;

import haveno.core.monetary.Price;

import javax.annotation.Nullable;
import java.math.BigInteger;

public class SpreadItem {
    public final String currencyCode;
    public final int numberOfBuyOffers;
    public final int numberOfSellOffers;
    public final int numberOfOffers;
    @Nullable
    public final Price priceSpread;
    public final String percentage;
    public final double percentageValue;
    public final BigInteger totalAmount;

    public SpreadItem(String currencyCode, int numberOfBuyOffers, int numberOfSellOffers, int numberOfOffers,
                      @Nullable Price priceSpread, String percentage, double percentageValue, BigInteger totalAmount) {
        this.currencyCode = currencyCode;
        this.numberOfBuyOffers = numberOfBuyOffers;
        this.numberOfSellOffers = numberOfSellOffers;
        this.numberOfOffers = numberOfOffers;
        this.priceSpread = priceSpread;
        this.percentage = percentage;
        this.percentageValue = percentageValue;
        this.totalAmount = totalAmount;
    }
}
