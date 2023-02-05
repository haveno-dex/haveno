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

package bisq.core.trade;

import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OpenOffer;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.Preferences;

import bisq.network.p2p.NodeAddress;

import bisq.common.crypto.KeyRing;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.util.Tuple2;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

import com.google.inject.Inject;

import com.google.common.collect.ImmutableList;

import javafx.collections.ObservableList;

import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.offer.OpenOffer.State.CANCELED;
import static bisq.core.trade.ClosedTradableUtil.castToTradeModel;
import static bisq.core.trade.ClosedTradableUtil.isOpenOffer;
import static bisq.core.util.AveragePriceUtil.getAveragePriceTuple;

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
        if (closedTradables.add(tradable)) {
            maybeClearSensitiveData();
            requestPersistence();
        }
    }

    public void remove(Tradable tradable) {
        if (closedTradables.remove(tradable)) {
            requestPersistence();
        }
    }

    public boolean wasMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<Tradable> getObservableList() {
        return closedTradables.getObservableList();
    }

    public List<Tradable> getTradableList() {
        return ImmutableList.copyOf(new ArrayList<>(getObservableList()));
    }

    public List<Trade> getClosedTrades() {
        return ImmutableList.copyOf(getObservableList().stream()
                .filter(e -> e instanceof Trade)
                .map(e -> (Trade) e)
                .collect(Collectors.toList()));
    }

    public List<OpenOffer> getCanceledOpenOffers() {
        return ImmutableList.copyOf(getObservableList().stream()
                .filter(e -> (e instanceof OpenOffer) && ((OpenOffer) e).getState().equals(CANCELED))
                .map(e -> (OpenOffer) e)
                .collect(Collectors.toList()));
    }

    public Optional<Tradable> getTradableById(String id) {
        return closedTradables.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    public Optional<Tradable> getTradeById(String id) {
        return closedTradables.stream().filter(e -> e instanceof Trade && e.getId().equals(id)).findFirst();
    }

    public void maybeClearSensitiveData() {
        log.info("checking closed trades eligibility for having sensitive data cleared");
        closedTradables.stream()
                .filter(e -> e instanceof Trade)
                .map(e -> (Trade) e)
                .filter(e -> canTradeHaveSensitiveDataCleared(e.getId()))
                .forEach(Trade::maybeClearSensitiveData);
        requestPersistence();
    }

    public boolean canTradeHaveSensitiveDataCleared(String tradeId) {
        Instant safeDate = getSafeDateForSensitiveDataClearing();
        return closedTradables.stream()
                .filter(e -> e.getId().equals(tradeId))
                .filter(e -> e.getDate().toInstant().isBefore(safeDate))
                .count() > 0;
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

    public Coin getTotalTradeFee(List<Tradable> tradableList) {
        return Coin.valueOf(tradableList.stream()
                .mapToLong(tradable -> getTradeFee(tradable))
                .sum());
    }

    private long getTradeFee(Tradable tradable) {
        return getBtcTradeFee(tradable);
    }

    public long getBtcTradeFee(Tradable tradable) {
        return isMaker(tradable) ?
                tradable.getOptionalMakerFee().orElse(Coin.ZERO).value :
                tradable.getOptionalTakerFee().orElse(Coin.ZERO).value;
    }

    public boolean isMaker(Tradable tradable) {
        return tradable instanceof MakerTrade || tradable.getOffer().isMyOffer(keyRing);
    }

    public Volume getBsqVolumeInUsdWithAveragePrice(Coin amount) {
        Tuple2<Price, Price> tuple = getAveragePriceTuple(preferences, tradeStatisticsManager, 30);
        Price usdPrice = tuple.first;
        long value = Math.round(amount.value * usdPrice.getValue() / 100d);
        return new Volume(Fiat.valueOf("USD", value));
    }

    private void requestPersistence() {
        persistenceManager.requestPersistence();
    }
}
