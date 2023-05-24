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

package haveno.core.provider.price;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import haveno.common.app.Version;
import haveno.common.util.MathUtils;
import haveno.core.provider.HttpClientProvider;
import haveno.network.http.HttpClient;
import haveno.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class PriceProvider extends HttpClientProvider {

    private boolean shutDownRequested;

    // Do not use Guice here as we might create multiple instances
    public PriceProvider(HttpClient httpClient, String baseUrl) {
        super(httpClient, baseUrl, false);
    }

    public synchronized Map<String, MarketPrice> getAll() throws IOException {
        if (shutDownRequested) {
            return new HashMap<>();
        }

        Map<String, MarketPrice> marketPriceMap = new HashMap<>();
        String hsVersion = "";
        if (P2PService.getMyNodeAddress() != null)
            hsVersion = P2PService.getMyNodeAddress().getHostName().length() > 22 ? ", HSv3" : ", HSv2";

        String json = httpClient.get("getAllMarketPrices", "User-Agent", "haveno/"
                + Version.VERSION + hsVersion);
        LinkedTreeMap<?, ?> map = new Gson().fromJson(json, LinkedTreeMap.class);

        List<?> list = (ArrayList<?>) map.get("data");
        list.forEach(obj -> {
            try {
                LinkedTreeMap<?, ?> treeMap = (LinkedTreeMap<?, ?>) obj;
                String baseCurrencyCode = (String) treeMap.get("baseCurrencyCode");
                String counterCurrencyCode = (String) treeMap.get("counterCurrencyCode");
                String currencyCode = baseCurrencyCode.equals("XMR") ? counterCurrencyCode : baseCurrencyCode;
                double price = (Double) treeMap.get("price");
                // json uses double for our timestampSec long value...
                long timestampSec = MathUtils.doubleToLong((Double) treeMap.get("timestampSec"));
                marketPriceMap.put(currencyCode, new MarketPrice(currencyCode, price, timestampSec, true));
            } catch (Throwable t) {
                log.error(t.toString());
                t.printStackTrace();
            }

        });
        return marketPriceMap;
    }

    public String getBaseUrl() {
        return httpClient.getBaseUrl();
    }

    public void shutDown() {
        shutDownRequested = true;
        httpClient.shutDown();
    }
}
