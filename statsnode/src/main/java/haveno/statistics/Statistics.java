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

package haveno.statistics;

import com.google.inject.Injector;
import haveno.core.app.misc.AppSetup;
import haveno.core.app.misc.AppSetupWithP2P;
import haveno.core.offer.OfferBookService;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Statistics {
    @Setter
    private Injector injector;

    private OfferBookService offerBookService; // pin to not get GC'ed
    private PriceFeedService priceFeedService;
    private TradeStatisticsManager tradeStatisticsManager;
    private P2PService p2pService;
    private AppSetup appSetup;

    public Statistics() {
    }

    public void startApplication() {
        p2pService = injector.getInstance(P2PService.class);
        offerBookService = injector.getInstance(OfferBookService.class);
        priceFeedService = injector.getInstance(PriceFeedService.class);
        tradeStatisticsManager = injector.getInstance(TradeStatisticsManager.class);

        // We need the price feed for market based offers
        priceFeedService.setCurrencyCode("USD");
        p2pService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onUpdatedDataReceived() {
                // we need to have tor ready
                log.info("onBootstrapComplete: we start requestPriceFeed");
                priceFeedService.startRequestingPrices(price -> log.info("requestPriceFeed. price=" + price),
                        (errorMessage, throwable) -> log.warn("Exception at requestPriceFeed: " + throwable.getMessage()));

                tradeStatisticsManager.onAllServicesInitialized();
            }
        });

        appSetup = injector.getInstance(AppSetupWithP2P.class);
        appSetup.start();
    }
}
