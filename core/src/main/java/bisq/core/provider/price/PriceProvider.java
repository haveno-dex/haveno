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

package bisq.core.provider.price;

import bisq.core.locale.CurrencyUtil;
import bisq.core.provider.HttpClientProvider;

import bisq.network.http.HttpClient;
import bisq.network.p2p.P2PService;

import bisq.common.app.Version;
import bisq.common.util.MathUtils;
import bisq.common.util.Tuple2;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;

import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PriceProvider extends HttpClientProvider {

    private boolean shutDownRequested;

    // Do not use Guice here as we might create multiple instances
    public PriceProvider(HttpClient httpClient, String baseUrl) {
        super(httpClient, baseUrl, false);
    }

    public synchronized Tuple2<Map<String, Long>, Map<String, MarketPrice>> getAll() throws IOException {
        if (shutDownRequested) {
            return new Tuple2<>(new HashMap<>(), new HashMap<>());
        }

        Map<String, MarketPrice> marketPriceMap = new HashMap<>();
        String hsVersion = "";
        if (P2PService.getMyNodeAddress() != null)
            hsVersion = P2PService.getMyNodeAddress().getHostName().length() > 22 ? ", HSv3" : ", HSv2";

        String json = httpClient.get("getAllMarketPrices", "User-Agent", "bisq/"
                + Version.VERSION + hsVersion);

        LinkedTreeMap<?, ?> map = new Gson().fromJson(json, LinkedTreeMap.class);
        Map<String, Long> tsMap = new HashMap<>();
        transfer("btcAverageTs", map, tsMap);
        transfer("poloniexTs", map, tsMap);
        transfer("coinmarketcapTs", map, tsMap);

        // get btc per xmr price to convert all prices to xmr
        // TODO (woodser): currently using bisq price feed, switch?
        List<?> list = (ArrayList<?>) map.get("data");
        double btcPerXmr = findBtcPerXmr(list);
        for (Object obj : list) {
            try {
                LinkedTreeMap<?, ?> treeMap = (LinkedTreeMap<?, ?>) obj;
                String currencyCode = (String) treeMap.get("currencyCode");
                double price = (Double) treeMap.get("price");
                // json uses double for our timestampSec long value...
                long timestampSec = MathUtils.doubleToLong((Double) treeMap.get("timestampSec"));

                // convert price from btc to xmr
                boolean isFiat = CurrencyUtil.isFiatCurrency(currencyCode);
                if (isFiat) price = price * btcPerXmr;
                else price = price / btcPerXmr;

                // add currency price to map
                marketPriceMap.put(currencyCode, new MarketPrice(currencyCode, price, timestampSec, true));
            } catch (Throwable t) {
                log.error(t.toString());
                t.printStackTrace();
            }
        }

        // add btc to price map, remove xmr since base currency
        marketPriceMap.put("BTC", new MarketPrice("BTC", 1 / btcPerXmr, marketPriceMap.get("XMR").getTimestampSec(), true));
        marketPriceMap.remove("XMR");
        return new Tuple2<>(tsMap, marketPriceMap);
    }

    private void transfer(String key, LinkedTreeMap<?, ?> map, Map<String, Long> tsMap) {
        if (map.containsKey(key)) tsMap.put(key, ((Double) map.get(key)).longValue());
        else log.warn("No prices returned from provider " + key);
    }


    /**
     * @return price of 1 XMR in BTC
     */
    private static double findBtcPerXmr(List<?> list) {
        for (Object obj : list) {
            LinkedTreeMap<?, ?> treeMap = (LinkedTreeMap<?, ?>) obj;
            String currencyCode = (String) treeMap.get("currencyCode");
            if ("XMR".equalsIgnoreCase(currencyCode)) {
                return (double) treeMap.get("price");
            }
        }
        throw new IllegalStateException("BTC per XMR price not found");
    }

    public String getBaseUrl() {
        return httpClient.getBaseUrl();
    }

    public void shutDown() {
        shutDownRequested = true;
        httpClient.shutDown();
    }
}
