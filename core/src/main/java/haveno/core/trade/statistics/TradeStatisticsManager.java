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

package haveno.core.trade.statistics;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.common.file.JsonFileManager;
import haveno.core.locale.CurrencyTuple;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.Trade;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TradeStatisticsManager {
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final TradeStatistics3StorageService tradeStatistics3StorageService;
    private final File storageDir;
    private final boolean dumpStatistics;
    private final ObservableSet<TradeStatistics3> observableTradeStatisticsSet = FXCollections.observableSet();
    private JsonFileManager jsonFileManager;

    @Inject
    public TradeStatisticsManager(P2PService p2PService,
                                  PriceFeedService priceFeedService,
                                  TradeStatistics3StorageService tradeStatistics3StorageService,
                                  AppendOnlyDataStoreService appendOnlyDataStoreService,
                                  @Named(Config.STORAGE_DIR) File storageDir,
                                  @Named(Config.DUMP_STATISTICS) boolean dumpStatistics) {
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.tradeStatistics3StorageService = tradeStatistics3StorageService;
        this.storageDir = storageDir;
        this.dumpStatistics = dumpStatistics;


        appendOnlyDataStoreService.addService(tradeStatistics3StorageService);
    }

    public void shutDown() {
        if (jsonFileManager != null) {
            jsonFileManager.shutDown();
        }
    }

    public void onAllServicesInitialized() {
        p2PService.getP2PDataStorage().addAppendOnlyDataStoreListener(payload -> {
            if (payload instanceof TradeStatistics3) {
                TradeStatistics3 tradeStatistics = (TradeStatistics3) payload;
                if (!tradeStatistics.isValid()) {
                    return;
                }
                synchronized (observableTradeStatisticsSet) {
                    observableTradeStatisticsSet.add(tradeStatistics);
                    priceFeedService.applyLatestHavenoMarketPrice(observableTradeStatisticsSet);
                }
                maybeDumpStatistics();
            }
        });

        Set<TradeStatistics3> set = tradeStatistics3StorageService.getMapOfAllData().values().stream()
                .filter(e -> e instanceof TradeStatistics3)
                .map(e -> (TradeStatistics3) e)
                .filter(TradeStatistics3::isValid)
                .collect(Collectors.toSet());
        

        // remove duplicates in early trades due to bug
        deduplicateEarlyTradeStatistics(set);

        synchronized (observableTradeStatisticsSet) {
            observableTradeStatisticsSet.addAll(set);
            priceFeedService.applyLatestHavenoMarketPrice(observableTradeStatisticsSet);
        }
        maybeDumpStatistics();
    }

    private void deduplicateEarlyTradeStatistics(Set<TradeStatistics3> set) {

        // collect trades before May 31, 2024
        Set<TradeStatistics3> tradesBeforeMay31_24 = set.stream()
                .filter(e -> e.getDate().toInstant().isBefore(Instant.parse("2024-05-31T00:00:00Z")))
                .collect(Collectors.toSet());

        // collect duplicated trades
        Set<TradeStatistics3> duplicated = new HashSet<TradeStatistics3>();
        Set<TradeStatistics3> deduplicated = new HashSet<TradeStatistics3>();
        for (TradeStatistics3 tradeStatistics : tradesBeforeMay31_24) {
            if (hasLenientDuplicate(tradeStatistics, deduplicated)) duplicated.add(tradeStatistics);
            else deduplicated.add(tradeStatistics);
        }

        // remove duplicated trades
        set.removeAll(duplicated);
    }

    private boolean hasLenientDuplicate(TradeStatistics3 tradeStatistics, Set<TradeStatistics3> set) {
        return set.stream().anyMatch(e -> isLenientDuplicate(tradeStatistics, e));
    }

    private boolean isLenientDuplicate(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        boolean isWithin2Minutes = Math.abs(tradeStatistics1.getDate().getTime() - tradeStatistics2.getDate().getTime()) < 120000;
        return isWithin2Minutes && 
                tradeStatistics1.getCurrency().equals(tradeStatistics2.getCurrency()) &&
                tradeStatistics1.getAmount() == tradeStatistics2.getAmount() && 
                tradeStatistics1.getPrice() == tradeStatistics2.getPrice();
    }

    public ObservableSet<TradeStatistics3> getObservableTradeStatisticsSet() {
        return observableTradeStatisticsSet;
    }

    private void maybeDumpStatistics() {
        if (!dumpStatistics) {
            return;
        }

        if (jsonFileManager == null) {
            jsonFileManager = new JsonFileManager(storageDir);

            // We only dump once the currencies as they do not change during runtime
            ArrayList<CurrencyTuple> traditionalCurrencyList = CurrencyUtil.getAllSortedTraditionalCurrencies().stream()
                    .map(e -> new CurrencyTuple(e.getCode(), e.getName(), 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(traditionalCurrencyList), "traditional_currency_list");

            ArrayList<CurrencyTuple> cryptoCurrencyList = CurrencyUtil.getAllSortedCryptoCurrencies().stream()
                    .map(e -> new CurrencyTuple(e.getCode(), e.getName(), 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            cryptoCurrencyList.add(0, new CurrencyTuple(Res.getBaseCurrencyCode(), Res.getBaseCurrencyName(), 8));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(cryptoCurrencyList), "crypto_currency_list");

            Instant yearAgo = Instant.ofEpochSecond(Instant.now().getEpochSecond() - TimeUnit.DAYS.toSeconds(365));
            Set<String> activeCurrencies = observableTradeStatisticsSet.stream()
                    .filter(e -> e.getDate().toInstant().isAfter(yearAgo))
                    .map(p -> p.getCurrency())
                    .collect(Collectors.toSet());

            ArrayList<CurrencyTuple> activeTraditionalCurrencyList = traditionalCurrencyList.stream()
                    .filter(e -> activeCurrencies.contains(e.code))
                    .map(e -> new CurrencyTuple(e.code, e.name, 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(activeTraditionalCurrencyList), "active_traditional_currency_list");

            ArrayList<CurrencyTuple> activeCryptoCurrencyList = cryptoCurrencyList.stream()
                    .filter(e -> activeCurrencies.contains(e.code))
                    .map(e -> new CurrencyTuple(e.code, e.name, 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(activeCryptoCurrencyList), "active_crypto_currency_list");
        }

        List<TradeStatisticsForJson> list = observableTradeStatisticsSet.stream()
                .map(TradeStatisticsForJson::new)
                .sorted((o1, o2) -> (Long.compare(o2.tradeDate, o1.tradeDate)))
                .collect(Collectors.toList());
        TradeStatisticsForJson[] array = new TradeStatisticsForJson[list.size()];
        list.toArray(array);
        jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(array), "trade_statistics");
    }

    public void maybeRepublishTradeStatistics(Set<Trade> trades,
                                              @Nullable String referralId,
                                              boolean isTorNetworkNode) {
        long ts = System.currentTimeMillis();
        Set<P2PDataStorage.ByteArray> hashes = tradeStatistics3StorageService.getMapOfAllData().keySet();
        trades.forEach(trade -> {
            if (!trade.shouldPublishTradeStatistics()) {
                log.debug("Trade: {} should not publish trade statistics", trade.getShortId());
                return;
            }

            TradeStatistics3 tradeStatistics3 = null;
            try {
                tradeStatistics3 = TradeStatistics3.from(trade, referralId, isTorNetworkNode, false);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            TradeStatistics3 tradeStatistics3Fuzzed = null;
            try {
                tradeStatistics3Fuzzed = TradeStatistics3.from(trade, referralId, isTorNetworkNode, true);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            boolean hasTradeStatistics3 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3.getHash()));
            boolean hasTradeStatistics3Fuzzed = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3Fuzzed.getHash()));
            if (hasTradeStatistics3 || hasTradeStatistics3Fuzzed) {
                log.debug("Trade: {}. We have already a tradeStatistics matching the hash of tradeStatistics3.",
                        trade.getShortId());
                return;
            }

            if (!tradeStatistics3.isValid()) {
                log.warn("Trade: {}. Trade statistics is invalid. We do not publish it.", tradeStatistics3);
                return;
            }

            log.info("Trade: {}. We republish tradeStatistics3 as we did not find it in the existing trade statistics. ",
                    trade.getShortId());
            p2PService.addPersistableNetworkPayload(tradeStatistics3, true);
        });
        log.info("maybeRepublishTradeStatistics took {} ms. Number of tradeStatistics: {}. Number of own trades: {}",
                System.currentTimeMillis() - ts, hashes.size(), trades.size());
    }
}
