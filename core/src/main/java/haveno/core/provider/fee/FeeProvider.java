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

package haveno.core.provider.fee;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.inject.Inject;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.util.Tuple2;
import haveno.core.provider.FeeHttpClient;
import haveno.core.provider.HttpClientProvider;
import haveno.core.provider.ProvidersRepository;
import haveno.network.http.HttpClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FeeProvider extends HttpClientProvider {

    @Inject
    public FeeProvider(FeeHttpClient httpClient, ProvidersRepository providersRepository) {
        super(httpClient, providersRepository.getBaseUrl(), false);
    }

    public Tuple2<Map<String, Long>, Map<String, Long>> getFees() throws IOException {
        String json = httpClient.get("getFees", "User-Agent", "haveno/" + Version.VERSION);

        LinkedTreeMap<?, ?> linkedTreeMap = new Gson().fromJson(json, LinkedTreeMap.class);
        Map<String, Long> tsMap = new HashMap<>();
        tsMap.put(Config.BTC_FEES_TS, ((Double) linkedTreeMap.get(Config.BTC_FEES_TS)).longValue());

        Map<String, Long> map = new HashMap<>();

        try {
            LinkedTreeMap<?, ?> dataMap = (LinkedTreeMap<?, ?>) linkedTreeMap.get("dataMap");
            Long btcTxFee = ((Double) dataMap.get(Config.BTC_TX_FEE)).longValue();
            Long btcMinTxFee = dataMap.get(Config.BTC_MIN_TX_FEE) != null ?
                    ((Double) dataMap.get(Config.BTC_MIN_TX_FEE)).longValue() : Config.baseCurrencyNetwork().getDefaultMinFeePerVbyte();

            map.put(Config.BTC_TX_FEE, btcTxFee);
            map.put(Config.BTC_MIN_TX_FEE, btcMinTxFee);
        } catch (Throwable t) {
            log.error("Error getting fees: {}\n", t.getMessage(), t);
        }
        return new Tuple2<>(tsMap, map);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
