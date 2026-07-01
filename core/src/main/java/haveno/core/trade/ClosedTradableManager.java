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

package haveno.core.trade;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.common.crypto.KeyRing;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.network.p2p.NodeAddress;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static haveno.core.offer.OpenOffer.State.CANCELED;
import static haveno.core.trade.ClosedTradableUtil.castToTradeModel;
import static haveno.core.trade.ClosedTradableUtil.isOpenOffer;

/**
 * Manages closed trades or offers.
 * BsqSwap trades are once confirmed moved in the closed trades domain as well.
 * We do not manage the persistence of BsqSwap trades here but in BsqSwapTradeManager.
 */
@Slf4j
public class ClosedTradableManager implements PersistedDataHost {
    private final KeyRing keyRing;
    private final PriceFeedService priceFeedService;
    private final Preferences preferences;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final ClosedTradesStore store;
    private final CleanupMailboxMessagesService cleanupMailboxMessagesService;

    private final TradableList<Tradable> closedTradables = new TradableList<>();

    @Inject
    public ClosedTradableManager(KeyRing keyRing,
                                 PriceFeedService priceFeedService,
                                 Preferences preferences,
                                 TradeStatisticsManager tradeStatisticsManager,
                                 ClosedTradesStore store,
                                 CleanupMailboxMessagesService cleanupMailboxMessagesService) {
        this.keyRing = keyRing;
        this.priceFeedService = priceFeedService;
        this.preferences = preferences;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.cleanupMailboxMessagesService = cleanupMailboxMessagesService;
        this.store = store;
    }

    @Override
    public void readPersisted(Runnable completeHandler) {
        // Replay the append-only log off the user thread (it can be large), then publish to the
        // in-memory ObservableList on the user thread, mirroring PersistenceManager.readPersisted.
        new Thread(() -> {
            List<Tradable> loaded;
            try {
                loaded = store.load();
            } catch (OutOfMemoryError e) {
                // Do not continue with a partial/empty list that a later append could overwrite;
                // preserve the data and halt this path, mirroring the OOM handling in #2353.
                throw e;
            } catch (Throwable t) {
                // A decode/parse fault must not hang startup (completeHandler would never run). The
                // on-disk log is left untouched so a fixed build can still recover it; proceed empty.
                log.error("Could not load closed trades; continuing with an empty list. The log on disk is left intact for recovery.", t);
                loaded = List.of();
            }
            List<Tradable> result = loaded;
            UserThread.execute(() -> {
                synchronized (closedTradables.getList()) {
                    closedTradables.setAll(result);
                    closedTradables.stream()
                            .filter(tradable -> tradable.getOffer() != null)
                            .forEach(tradable -> tradable.getOffer().setPriceFeedService(priceFeedService));
                }
                completeHandler.run();
            });
        }, "ClosedTradesStore-read").start();
    }

    public void onAllServicesInitialized() {
        cleanupMailboxMessagesService.handleTrades(getClosedTrades());
        maybeClearSensitiveData();
    }

    public void add(Tradable tradable) {
        synchronized (closedTradables.getList()) {
            if (closedTradables.add(tradable)) {
                maybeClearSensitiveData();
                store.appendUpsert(tradable);
            }
        }
    }

    public void remove(Tradable tradable) {
        synchronized (closedTradables.getList()) {
            if (closedTradables.remove(tradable)) {
                store.appendDelete(tradable.getId());
            }
        }
    }

    /**
     * Re-persists an already-closed trade whose in-place mutable state changed (e.g. process data
     * cleared on shut down). No-op if the trade is not in the closed list, so callers can invoke it
     * unconditionally; pending trades keep their own whole-list flush. This restores what the old
     * "serialize the whole list on flush" model captured implicitly for such mutations.
     */
    public void persistClosedTrade(Trade trade) {
        synchronized (closedTradables.getList()) {
            if (closedTradables.stream().anyMatch(t -> t.getId().equals(trade.getId()))) {
                store.appendUpsert(trade);
            }
        }
    }

    public boolean wasMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<Tradable> getObservableList() {
        return closedTradables.getObservableList();
    }

    public List<Tradable> getTradableList() {
        synchronized (closedTradables.getList()) {
            return ImmutableList.copyOf(new ArrayList<>(getObservableList()));
        }
    }

    public List<Trade> getClosedTrades() {
        synchronized (closedTradables.getList()) {
            return ImmutableList.copyOf(getObservableList().stream()
                    .filter(e -> e instanceof Trade)
                    .map(e -> (Trade) e)
                    .collect(Collectors.toList()));
        }
    }

    public List<OpenOffer> getCanceledOpenOffers() {
        synchronized (closedTradables.getList()) {
            return ImmutableList.copyOf(getObservableList().stream()
                    .filter(e -> (e instanceof OpenOffer) && ((OpenOffer) e).getState().equals(CANCELED))
                    .map(e -> (OpenOffer) e)
                    .collect(Collectors.toList()));
        }
    }

    public Optional<Tradable> getTradableById(String id) {
        synchronized (closedTradables.getList()) {
            return closedTradables.stream().filter(e -> e.getId().equals(id)).findFirst();
        }
    }

    public Optional<Trade> getTradeById(String id) {
        synchronized (closedTradables.getList()) {
            return getClosedTrades().stream().filter(e -> e.getId().equals(id)).findFirst();
        }
    }

    public void maybeClearSensitiveData() {
        synchronized (closedTradables.getList()) {
            log.info("checking closed trades eligibility for having sensitive data cleared");
            List<Trade> cleared = new ArrayList<>();
            closedTradables.stream()
                .filter(e -> e instanceof Trade)
                .map(e -> (Trade) e)
                .filter(e -> canTradeHaveSensitiveDataCleared(e.getId()))
                .forEach(trade -> {
                    if (trade.maybeClearSensitiveData()) cleared.add(trade);
                });
            // Persist only the trades that actually changed, so this stays cheap on every add().
            cleared.forEach(store::appendUpsert);
        }
    }

    public boolean canTradeHaveSensitiveDataCleared(String tradeId) {
        Instant safeDate = getSafeDateForSensitiveDataClearing();
        synchronized (closedTradables.getList()) {
            return closedTradables.stream()
                    .filter(e -> e.getId().equals(tradeId))
                    .filter(e -> e.getDate().toInstant().isBefore(safeDate))
                    .count() > 0;
        }
    }

    public Instant getSafeDateForSensitiveDataClearing() {
        return Instant.ofEpochSecond(Instant.now().getEpochSecond()
                - TimeUnit.DAYS.toSeconds(preferences.getClearDataAfterDays()));
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        return getClosedTrades().stream()
                .filter(Trade::isFundsLockedIn);
    }

    public Stream<Trade> getTradeModelStream() {
        return getClosedTrades().stream();
    }

    public int getNumPastTrades(Tradable tradable) {
        if (isOpenOffer(tradable)) {
            return 0;
        }
        NodeAddress addressInTrade = castToTradeModel(tradable).getTradePeerNodeAddress();
        return (int) getTradeModelStream()
                .map(Trade::getTradePeerNodeAddress)
                .filter(Objects::nonNull)
                .filter(address -> address.equals(addressInTrade))
                .count();
    }

    public BigInteger getTotalTradeFee(List<Tradable> tradableList) {
        synchronized (tradableList) {
            return BigInteger.valueOf(tradableList.stream()
                    .mapToLong(tradable -> getTradeFee(tradable).longValueExact())
                    .sum());
        }
    }

    private BigInteger getTradeFee(Tradable tradable) {
        return getXmrTradeFee(tradable);
    }

    public BigInteger getXmrTradeFee(Tradable tradable) {
        return isMaker(tradable) ?
                tradable.getOptionalMakerFee().orElse(BigInteger.ZERO) :
                tradable.getOptionalTakerFee().orElse(BigInteger.ZERO);
    }

    public boolean isMaker(Tradable tradable) {
        return tradable instanceof MakerTrade || tradable.getOffer().isMyOffer(keyRing);
    }

    public void removeTrade(Trade trade) {
        synchronized (closedTradables.getList()) {
            if (closedTradables.remove(trade)) {
                store.appendDelete(trade.getId());
            }
        }
    }
}
