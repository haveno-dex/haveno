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

package haveno.core.trade.failed;

import com.google.inject.Inject;
import haveno.common.crypto.KeyRing;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.offer.Offer;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.CleanupMailboxMessages;
import haveno.core.trade.TradableList;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeUtil;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.ObservableList;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class FailedTradesManager implements PersistedDataHost {
    private static final Logger log = LoggerFactory.getLogger(FailedTradesManager.class);
    private final TradableList<Trade> failedTrades = new TradableList<>();
    private final KeyRing keyRing;
    private final PriceFeedService priceFeedService;
    private final XmrWalletService xmrWalletService;
    private final CleanupMailboxMessages cleanupMailboxMessages;
    private final PersistenceManager<TradableList<Trade>> persistenceManager;
    private final TradeUtil tradeUtil;
    @Setter
    private Function<Trade, Boolean> unFailTradeCallback;

    @Inject
    public FailedTradesManager(KeyRing keyRing,
                               PriceFeedService priceFeedService,
                               XmrWalletService xmrWalletService,
                               PersistenceManager<TradableList<Trade>> persistenceManager,
                               TradeUtil tradeUtil,
                               CleanupMailboxMessages cleanupMailboxMessages) {
        this.keyRing = keyRing;
        this.priceFeedService = priceFeedService;
        this.xmrWalletService = xmrWalletService;
        this.cleanupMailboxMessages = cleanupMailboxMessages;
        this.persistenceManager = persistenceManager;
        this.tradeUtil = tradeUtil;

        this.persistenceManager.initialize(failedTrades, "FailedTrades", PersistenceManager.Source.PRIVATE);
    }

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
            synchronized (persisted.getList()) {
                failedTrades.setAll(persisted.getList());
                failedTrades.stream()
                        .filter(trade -> trade.getOffer() != null)
                        .forEach(trade -> trade.getOffer().setPriceFeedService(priceFeedService));
            }
            completeHandler.run();
        },
        completeHandler);
    }

    public void onAllServicesInitialized() {
        cleanupMailboxMessages.handleTrades(failedTrades.getList());
    }

    public void add(Trade trade) {
        synchronized (failedTrades.getList()) {
            if (failedTrades.add(trade)) {
                requestPersistence();
            }
        }
    }

    public void removeTrade(Trade trade) {
        synchronized (failedTrades.getList()) {
            if (failedTrades.remove(trade)) {
                requestPersistence();
            }
        }
    }

    public boolean wasMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<Trade> getObservableList() {
        synchronized (failedTrades.getList()) {
            return failedTrades.getObservableList();
        }
    }

    public Optional<Trade> getTradeById(String id) {
        synchronized (failedTrades.getList()) {
            return failedTrades.stream().filter(e -> e.getId().equals(id)).findFirst();
        }
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        synchronized (failedTrades.getList()) {
            return failedTrades.stream()
                    .filter(Trade::isFundsLockedIn);
        }
    }

    public void unFailTrade(Trade trade) {
        synchronized (failedTrades.getList()) {
            if (unFailTradeCallback == null)
                return;

            if (unFailTradeCallback.apply(trade)) {
                log.info("Unfailing trade {}", trade.getId());
                if (failedTrades.remove(trade)) {
                    requestPersistence();
                }
            }
        }
    }

    public String checkUnFail(Trade trade) {
        var addresses = tradeUtil.getTradeAddresses(trade);
        if (addresses == null) {
            return "Addresses not found";
        }
        StringBuilder blockingTrades = new StringBuilder();
        for (var entry : xmrWalletService.getAddressEntryListAsImmutableList()) {
            if (entry.getContext() == XmrAddressEntry.Context.AVAILABLE)
                continue;
            if (entry.getAddressString() != null &&
                    (entry.getAddressString().equals(addresses.first) ||
                            entry.getAddressString().equals(addresses.second))) {
                blockingTrades.append(entry.getOfferId()).append(", ");
            }
        }
        return blockingTrades.toString();
    }

    private void requestPersistence() {
        persistenceManager.requestPersistence();
    }
}
