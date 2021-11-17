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

package bisq.core.api;

import bisq.core.api.model.MarketPrice;
import bisq.core.locale.CurrencyUtil;
import bisq.core.provider.price.PriceFeedService;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
class CorePriceService {

    private final PriceFeedService priceFeedService;

    @Inject
    public CorePriceService(PriceFeedService priceFeedService) {
        this.priceFeedService = priceFeedService;
    }

    /**
     * @return Price per 1 XMR in the given currency (fiat or crypto)
     */
    public double getMarketPrice(String currencyCode) throws ExecutionException, InterruptedException, TimeoutException, IllegalArgumentException {
        var marketPrice = priceFeedService.requestAllPrices().get(currencyCode);
        if (marketPrice == null) {
            throw new IllegalArgumentException("Currency not found: " + currencyCode); // message sent to client
        }
        return mapPriceFeedServicePrice(marketPrice.getPrice(), marketPrice.getCurrencyCode());
    }

    /**
     * @return Price per 1 XMR in all supported currencies (fiat & crypto)
     */
    public List<MarketPrice> getMarketPrices() throws ExecutionException, InterruptedException, TimeoutException {
        return priceFeedService.requestAllPrices().values().stream()
                .map(marketPrice -> {
                    double mappedPrice = mapPriceFeedServicePrice(marketPrice.getPrice(), marketPrice.getCurrencyCode());
                    return new MarketPrice(marketPrice.getCurrencyCode(), mappedPrice);
                })
                .collect(Collectors.toList());
    }

    /**
     * PriceProvider returns different values for crypto and fiat,
     * e.g. 1 XMR = X USD
     * but 1 DOGE = X XMR
     * Here we convert all to:
     * 1 XMR = X (FIAT or CRYPTO)
     *
     */
    private double mapPriceFeedServicePrice(double price, String currencyCode) {
        if (CurrencyUtil.isFiatCurrency(currencyCode)) {
            return price;
        }
        return price == 0 ? 0 : 1 / price;
        // TODO PriceProvider.getAll() could provide these values directly when the original values are not needed for the 'desktop' UI anymore
    }
}
