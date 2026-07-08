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

import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.JsonFileManager;
import haveno.core.locale.CurrencyTuple;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
    private final ObservableList<TradeStatistics3> observableTradeStatisticsList = FXCollections.observableArrayList();
    private JsonFileManager jsonFileManager;
    private final AtomicBoolean dumpStatisticsScheduled = new AtomicBoolean();
    private final List<TradeStatistics3> pendingTradeStatistics = new ArrayList<>();
    // Early trades are accumulated raw and deduplicated deterministically; recent trades dedup by value so re-delivered payloads are dropped.
    private final Set<TradeStatistics3> earlyTradeStatistics = new HashSet<>();
    private final Set<TradeStatistics3> recentTradeStatistics = new LinkedHashSet<>();
    private final AtomicBoolean flushPendingScheduled = new AtomicBoolean();
    private volatile boolean shutDownRequested;
    public static final int PUBLISH_STATS_RANDOM_DELAY_HOURS = 24;
    private static final int DUMP_STATISTICS_DELAY_SEC = 60;
    private static final int ADD_STATISTICS_DELAY_SEC = 1;
    // Legacy publishing bugs duplicated early trades; stats before these dates are deduplicated.
    private static final Instant EARLY_DUPLICATE_DATE = Instant.parse("2024-09-30T00:00:00Z");
    private static final Instant EARLY_FUZZY_DUPLICATE_DATE = Instant.parse("2024-08-07T00:00:00Z");
    // Canonical order so the greedy dedup keeps the same representatives regardless of arrival order.
    private static final Comparator<TradeStatistics3> EARLY_TRADE_ORDER =
            Comparator.comparingLong(TradeStatistics3::getDateAsLong).thenComparing(TradeStatistics3::getHash, Arrays::compare);

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
        HavenoUtils.tradeStatisticsManager = this;
    }

    public void shutDown() {
        shutDownRequested = true;
        // flush pending statistics and a pending batched dump so the json includes the final statistics
        flushPendingTradeStatistics();
        if (dumpStatisticsScheduled.getAndSet(false)) {
            dumpStatistics();
        }
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
                synchronized (pendingTradeStatistics) {
                    pendingTradeStatistics.add(tradeStatistics); // add to batch which is flushed on intervals
                }
                maybeFlushPendingTradeStatistics();
            }
        });

        Set<TradeStatistics3> set = tradeStatistics3StorageService.getMapOfAllData().values().stream()
                .filter(e -> e instanceof TradeStatistics3)
                .map(e -> (TradeStatistics3) e)
                .filter(TradeStatistics3::isValid)
                .collect(Collectors.toSet());

        // add deduplicated, dropping early trade stats duplicated by legacy publishing bugs
        addTradeStatistics(set);
        maybeDumpStatistics();
    }

    // flush statistics in intervals to bound CPU and memory during rapid sync
    private void maybeFlushPendingTradeStatistics() {
        if (!flushPendingScheduled.compareAndSet(false, true)) {
            return;
        }
        UserThread.runAfter(() -> {
            flushPendingScheduled.set(false);
            if (shutDownRequested) return;
            flushPendingTradeStatistics();
            maybeDumpStatistics();
        }, ADD_STATISTICS_DELAY_SEC);
    }

    private void flushPendingTradeStatistics() {
        List<TradeStatistics3> pending;
        synchronized (pendingTradeStatistics) {
            if (pendingTradeStatistics.isEmpty()) return;
            pending = new ArrayList<>(pendingTradeStatistics);
            pendingTradeStatistics.clear();
        }
        addTradeStatistics(pending);
    }

    private void addTradeStatistics(Collection<TradeStatistics3> tradeStatistics) {
        List<TradeStatistics3> applied = new ArrayList<>();
        synchronized (observableTradeStatisticsList) {
            List<TradeStatistics3> addedRecent = new ArrayList<>();
            boolean earlyChanged = false;
            for (TradeStatistics3 tradeStatistic : tradeStatistics) {
                if (isEarlyTrade(tradeStatistic)) {
                    earlyChanged |= earlyTradeStatistics.add(tradeStatistic);
                } else if (recentTradeStatistics.add(tradeStatistic)) {
                    addedRecent.add(tradeStatistic);
                }
            }
            if (earlyChanged) {
                applied.addAll(deduplicateEarlyTradeStatistics());
                applied.addAll(recentTradeStatistics);
                observableTradeStatisticsList.setAll(applied);
            } else {
                observableTradeStatisticsList.addAll(addedRecent);
                applied.addAll(addedRecent);
            }
        }
        priceFeedService.applyLatestHavenoMarketPrice(applied);
    }

    private List<TradeStatistics3> deduplicateEarlyTradeStatistics() {
        List<TradeStatistics3> sorted = new ArrayList<>(earlyTradeStatistics);
        sorted.sort(EARLY_TRADE_ORDER);
        List<TradeStatistics3> deduplicated = new ArrayList<>();
        for (TradeStatistics3 candidate : sorted) {
            boolean checkFuzzy = isEarlyFuzzyTrade(candidate);
            boolean duplicate = false;
            for (TradeStatistics3 kept : deduplicated) {
                if (isDuplicate(candidate, kept) ||
                        (checkFuzzy && isEarlyFuzzyTrade(kept) && isFuzzyDuplicate(candidate, kept))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) deduplicated.add(candidate);
        }
        return deduplicated;
    }

    private boolean isEarlyTrade(TradeStatistics3 tradeStatistics) {
        return tradeStatistics.getDate().toInstant().isBefore(EARLY_DUPLICATE_DATE);
    }

    private boolean isEarlyFuzzyTrade(TradeStatistics3 tradeStatistics) {
        return tradeStatistics.getDate().toInstant().isBefore(EARLY_FUZZY_DUPLICATE_DATE);
    }

    // duplicated timestamp, currency, and payment method
    private boolean isDuplicate(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        if (!tradeStatistics1.getPaymentMethodId().equals(tradeStatistics2.getPaymentMethodId())) return false;
        if (!tradeStatistics1.getCurrency().equals(tradeStatistics2.getCurrency())) return false;
        return tradeStatistics1.getDateAsLong() == tradeStatistics2.getDateAsLong();
    }

    // duplicated payment method, currency, price, and a fuzzily matching date/amount
    private boolean isFuzzyDuplicate(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        if (!tradeStatistics1.getPaymentMethodId().equals(tradeStatistics2.getPaymentMethodId())) return false;
        if (!tradeStatistics1.getCurrency().equals(tradeStatistics2.getCurrency())) return false;
        if (tradeStatistics1.getNormalizedPrice() != tradeStatistics2.getNormalizedPrice()) return false;
        return isFuzzyDuplicateV1(tradeStatistics1, tradeStatistics2) || isFuzzyDuplicateV2(tradeStatistics1, tradeStatistics2);
    }

    // bug caused all peers to publish same trade with similar timestamps
    private boolean isFuzzyDuplicateV1(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        boolean isWithin2Minutes = Math.abs(tradeStatistics1.getDate().getTime() - tradeStatistics2.getDate().getTime()) <= TimeUnit.MINUTES.toMillis(2);
        return isWithin2Minutes;
    }

    // bug caused sellers to re-publish their trades with randomized amounts
    private static final double FUZZ_AMOUNT_PCT = 0.05;
    private static final int FUZZ_DATE_HOURS = 24;
    private boolean isFuzzyDuplicateV2(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        boolean isWithinFuzzedHours = Math.abs(tradeStatistics1.getDate().getTime() - tradeStatistics2.getDate().getTime()) <= TimeUnit.HOURS.toMillis(FUZZ_DATE_HOURS);
        boolean isWithinFuzzedAmount = Math.abs(tradeStatistics1.getAmount() - tradeStatistics2.getAmount()) <= FUZZ_AMOUNT_PCT * tradeStatistics1.getAmount();
        return isWithinFuzzedHours && isWithinFuzzedAmount;
    }

    public ObservableList<TradeStatistics3> getObservableTradeStatisticsList() {
        return observableTradeStatisticsList;
    }

    public List<TradeStatistics3> getTradeStatisticsListCopy() {
        synchronized (observableTradeStatisticsList) {
            return new ArrayList<>(observableTradeStatisticsList);
        }
    }

    private void maybeDumpStatistics() {
        if (!dumpStatistics) {
            return;
        }

        // Dumping serializes the whole statistics list, so we batch frequent updates into one dump per interval.
        if (!dumpStatisticsScheduled.compareAndSet(false, true)) {
            return;
        }
        UserThread.runAfter(() -> {
            dumpStatisticsScheduled.set(false);
            if (shutDownRequested) {
                return;
            }
            dumpStatistics();
        }, DUMP_STATISTICS_DELAY_SEC);
    }

    private void dumpStatistics() {
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
            Set<String> activeCurrencies;
            synchronized (observableTradeStatisticsList) {
                activeCurrencies = observableTradeStatisticsList.stream()
                        .filter(e -> e.getDate().toInstant().isAfter(yearAgo))
                        .map(p -> p.getCurrency())
                        .collect(Collectors.toSet());
            }

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

        List<TradeStatisticsForJson> list;
        synchronized (observableTradeStatisticsList) {
            list = observableTradeStatisticsList.stream()
                    .map(TradeStatisticsForJson::new)
                    .sorted((o1, o2) -> (Long.compare(o2.tradeDate, o1.tradeDate)))
                    .collect(Collectors.toList());
        }
        TradeStatisticsForJson[] array = new TradeStatisticsForJson[list.size()];
        list.toArray(array);
        jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(array), "trade_statistics");
    }

    public void maybePublishTradeStatistics(Trade trade, @Nullable String referralId, boolean isTorNetworkNode) {
        Set<Trade> trades = new HashSet<>();
        trades.add(trade);
        maybePublishTradeStatistics(trades, referralId, isTorNetworkNode);
    }

   public void maybePublishTradeStatistics(Set<Trade> trades,
                                              @Nullable String referralId,
                                              boolean isTorNetworkNode) {
        long ts = System.currentTimeMillis();
        Set<P2PDataStorage.ByteArray> hashes = tradeStatistics3StorageService.getMapOfAllData().keySet();
        trades.forEach(trade -> {
            if (!trade.shouldPublishTradeStatistics()) {
                log.debug("Trade: {} should not publish trade statistics", trade.getShortId());
                return;
            }

            TradeStatistics3 tradeStatistics3V0 = null;
            try {
                tradeStatistics3V0 = TradeStatistics3.fromV0(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            TradeStatistics3 tradeStatistics3V1 = null;
            try {
                tradeStatistics3V1 = TradeStatistics3.fromV1(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            TradeStatistics3 tradeStatistics3V2 = null;
            try {
                tradeStatistics3V2 = TradeStatistics3.fromV2(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            boolean hasTradeStatistics3V0 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V0.getHash()));
            boolean hasTradeStatistics3V1 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V1.getHash()));
            boolean hasTradeStatistics3V2 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V2.getHash()));
            if (hasTradeStatistics3V0 || hasTradeStatistics3V1 || hasTradeStatistics3V2) {
                log.debug("Trade: {}. We have already a tradeStatistics matching the hash of tradeStatistics3.",
                        trade.getShortId());
                return;
            }

            if (!tradeStatistics3V2.isValid()) {
                log.warn("Trade statistics are invalid for {} {}. We do not publish: {}", trade.getClass().getSimpleName(), trade.getShortId(), tradeStatistics3V1);
                return;
            }

            // publish after random delay within 12 hours
            log.info("Scheduling to publish trade statistics at random time for {} {}", trade.getClass().getSimpleName(), trade.getShortId());
            TradeStatistics3 tradeStatistics3V2Final = tradeStatistics3V2;
            UserThread.runAfterRandomDelay(() -> {
                p2PService.addPersistableNetworkPayload(tradeStatistics3V2Final, true);
            }, 0, PUBLISH_STATS_RANDOM_DELAY_HOURS / 2 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
        });
        log.info("maybeRepublishTradeStatistics took {} ms. Number of tradeStatistics: {}. Number of own trades: {}",
                System.currentTimeMillis() - ts, hashes.size(), trades.size());
    }
}
