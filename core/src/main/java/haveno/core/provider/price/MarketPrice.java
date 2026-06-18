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

package haveno.core.provider.price;

import lombok.Getter;
import lombok.Value;

import java.time.Instant;

@Value
public class MarketPrice {
    public static final long MARKET_PRICE_MAX_AGE_MS = 30 * 60 * 1000L;  // 30 min

    private final String currencyCode;
    private final double price;
    private final long timestampMs;
    @Getter
    private final boolean isExternallyProvidedPrice;

    public MarketPrice(String currencyCode, double price, long timestampMs, boolean isExternallyProvidedPrice) {
        this.currencyCode = currencyCode;
        this.price = price;
        this.timestampMs = timestampMs;
        this.isExternallyProvidedPrice = isExternallyProvidedPrice;
    }

    public boolean isPriceAvailable() {
        return price > 0;
    }

    public boolean isRecentPriceAvailable() {
        return isPriceAvailable() && timestampMs > (Instant.now().toEpochMilli() - MARKET_PRICE_MAX_AGE_MS);
    }

    public boolean isRecentExternalPriceAvailable() {
        return isExternallyProvidedPrice && isRecentPriceAvailable();
    }
}
