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

package haveno.core.trade;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import haveno.common.crypto.KeyRing;
import haveno.common.persistence.PersistenceManager;
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
    private final PersistenceManager<TradableList<Tradable>> persistenceManager;
    private final CleanupMailboxMessagesService cleanupMailboxMessagesService;

    private final TradableList<Tradable> closedTradables = new TradableList<>();

    @Inject
    public ClosedTradableManager(KeyRing keyRing,
                                 PriceFeedService priceFeedService,
                                 Preferences preferences,
                                 TradeStatisticsManager tradeStatisticsManager,
                                 PersistenceManager<TradableList<Tradable>> persistenceManager,
                                 CleanupMailboxMessagesService cleanupMailboxMessagesService) {
        this.keyRing = keyRing;
        this.priceFeedService = priceFeedService;
        this.preferences = preferences;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.cleanupMailboxMessagesService = cleanupMailboxMessagesService;
        this.persistenceManager = persistenceManager;

        this.persistenceManager.initialize(closedTradables, "ClosedTrades", PersistenceManager.Source.PRIVATE);
    }

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
                    closedTradables.setAll(persisted.getList());
                    closedTradables.stream()
                            .filter(tradable -> tradable.getOffer() != null)
                            .forEach(tradable -> tradable.getOffer().setPriceFeedService(priceFeedService));
                    completeHandler.run();
                },
                completeHandler);
    }

    public void onAllServicesInitialized() {
        cleanupMailboxMessagesService.handleTrades(getClosedTrades());
        maybeClearSensitiveData();
    }

    public void add(Tradable tradable) {
        synchronized (closedTradables) {
            if (closedTradables.add(tradable)) {
                maybeClearSensitiveData();
                requestPersistence();
            }
        }
    }

    public void remove(Tradable tradable) {
        synchronized (closedTradables) {
            if (closedTradables.remove(tradable)) {
                requestPersistence();
            }
        }
    }

    public boolean wasMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<Tradable> getObservableList() {
        synchronized (closedTradables) {
            return closedTradables.getObservableList();
        }
    }

    public List<Tradable> getTradableList() {
        return ImmutableList.copyOf(new ArrayList<>(getObservableList()));
    }

    public List<Trade> getClosedTrades() {
        synchronized (closedTradables) {
            return ImmutableList.copyOf(getObservableList().stream()
                    .filter(e -> e instanceof Trade)
                    .map(e -> (Trade) e)
                    .collect(Collectors.toList()));
        }
    }

    public List<OpenOffer> getCanceledOpenOffers() {
        synchronized (closedTradables) {
            return ImmutableList.copyOf(getObservableList().stream()
                    .filter(e -> (e instanceof OpenOffer) && ((OpenOffer) e).getState().equals(CANCELED))
                    .map(e -> (OpenOffer) e)
                    .collect(Collectors.toList()));
        }
    }

    public Optional<Tradable> getTradableById(String id) {
        synchronized (closedTradables) {
            return closedTradables.stream().filter(e -> e.getId().equals(id)).findFirst();
        }
    }

    public Optional<Tradable> getTradeById(String id) {
        synchronized (closedTradables) {
            return closedTradables.stream().filter(e -> e instanceof Trade && e.getId().equals(id)).findFirst();
        }
    }

    public void maybeClearSensitiveData() {
        synchronized (closedTradables) {
            log.info("checking closed trades eligibility for having sensitive data cleared");
            closedTradables.stream()
                .filter(e -> e instanceof Trade)
                .map(e -> (Trade) e)
                .filter(e -> canTradeHaveSensitiveDataCleared(e.getId()))
                .forEach(Trade::maybeClearSensitiveData);
            requestPersistence();
        }
    }

    public boolean canTradeHaveSensitiveDataCleared(String tradeId) {
        Instant safeDate = getSafeDateForSensitiveDataClearing();
        synchronized (closedTradables) {
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
        return BigInteger.valueOf(tradableList.stream()
                .mapToLong(tradable -> getTradeFee(tradable).longValueExact())
                .sum());
    }

    private BigInteger getTradeFee(Tradable tradable) {
        return getXmrTradeFee(tradable);
    }

    public BigInteger getXmrTradeFee(Tradable tradable) {
        return isMaker(tradable) ?
                tradable.getOptionalMakerFee().orElse(BigInteger.valueOf(0)) :
                tradable.getOptionalTakerFee().orElse(BigInteger.valueOf(0));
    }

    public boolean isMaker(Tradable tradable) {
        return tradable instanceof MakerTrade || tradable.getOffer().isMyOffer(keyRing);
    }

    private void requestPersistence() {
        persistenceManager.requestPersistence();
    }
}
