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

package haveno.core.offer;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import common.utils.GenUtils;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.JsonFileManager;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.api.XmrConnectionService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.util.JsonUtil;
import haveno.core.xmr.wallet.XmrKeyImageListener;
import haveno.core.xmr.wallet.XmrKeyImagePoller;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.HashMapChangedListener;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import java.io.File;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import monero.daemon.model.MoneroKeyImageSpentStatus;

/**
 * Handles storage and retrieval of offers.
 * Uses an invalidation flag to only request the full offer map in case there was a change (anyone has added or removed an offer).
 */
public class OfferBookService {

    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final List<OfferBookChangedListener> offerBookChangedListeners = new LinkedList<>();
    private final FilterManager filterManager;
    private final JsonFileManager jsonFileManager;
    private final XmrConnectionService xmrConnectionService;

    // poll key images of offers
    private XmrKeyImagePoller keyImagePoller;
    private static final long KEY_IMAGE_REFRESH_PERIOD_MS_LOCAL = 20000; // 20 seconds
    private static final long KEY_IMAGE_REFRESH_PERIOD_MS_REMOTE = 300000; // 5 minutes

    public interface OfferBookChangedListener {
        void onAdded(Offer offer);

        void onRemoved(Offer offer);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OfferBookService(P2PService p2PService,
                            PriceFeedService priceFeedService,
                            FilterManager filterManager,
                            XmrConnectionService xmrConnectionService,
                            @Named(Config.STORAGE_DIR) File storageDir,
                            @Named(Config.DUMP_STATISTICS) boolean dumpStatistics) {
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.xmrConnectionService = xmrConnectionService;
        jsonFileManager = new JsonFileManager(storageDir);

        // listen for connection changes to monerod
        xmrConnectionService.addConnectionListener((connection) -> {
            maybeInitializeKeyImagePoller();
            keyImagePoller.setDaemon(xmrConnectionService.getDaemon());
            keyImagePoller.setRefreshPeriodMs(getKeyImageRefreshPeriodMs());
        });

        // listen for offers
        p2PService.addHashSetChangedListener(new HashMapChangedListener() {
            @Override
            public void onAdded(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                    protectedStorageEntries.forEach(protectedStorageEntry -> {
                        synchronized (offerBookChangedListeners) {
                            offerBookChangedListeners.forEach(listener -> {
                                if (protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload) {
                                    OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
                                    maybeInitializeKeyImagePoller();
                                    keyImagePoller.addKeyImages(offerPayload.getReserveTxKeyImages());
                                    Offer offer = new Offer(offerPayload);
                                    offer.setPriceFeedService(priceFeedService);
                                    setReservedFundsSpent(offer);
                                    listener.onAdded(offer);
                                }
                            });
                        }
                    });
            }

            @Override
            public void onRemoved(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                protectedStorageEntries.forEach(protectedStorageEntry -> {
                    synchronized (offerBookChangedListeners) {
                        offerBookChangedListeners.forEach(listener -> {
                            if (protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload) {
                                OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
                                maybeInitializeKeyImagePoller();
                                keyImagePoller.removeKeyImages(offerPayload.getReserveTxKeyImages());
                                Offer offer = new Offer(offerPayload);
                                offer.setPriceFeedService(priceFeedService);
                                setReservedFundsSpent(offer);
                                listener.onRemoved(offer);
                            }
                        });
                    }
                });
            }
        });

        if (dumpStatistics) {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onUpdatedDataReceived() {
                    addOfferBookChangedListener(new OfferBookChangedListener() {
                        @Override
                        public void onAdded(Offer offer) {
                            doDumpStatistics();
                        }

                        @Override
                        public void onRemoved(Offer offer) {
                            doDumpStatistics();
                        }
                    });
                    UserThread.runAfter(OfferBookService.this::doDumpStatistics, 1);
                }
            });
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (filterManager.requireUpdateToNewVersionForTrading()) {
            errorMessageHandler.handleErrorMessage(Res.get("popup.warning.mandatoryUpdate.trading"));
            return;
        }

        boolean result = p2PService.addProtectedStorageEntry(offer.getOfferPayload());
        if (result) {
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Add offer failed");
        }
    }

    public void refreshTTL(OfferPayload offerPayload,
                           ResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {
        if (filterManager.requireUpdateToNewVersionForTrading()) {
            errorMessageHandler.handleErrorMessage(Res.get("popup.warning.mandatoryUpdate.trading"));
            return;
        }

        boolean result = p2PService.refreshTTL(offerPayload);
        if (result) {
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Refresh TTL failed.");
        }
    }

    public void activateOffer(Offer offer,
                              @Nullable ResultHandler resultHandler,
                              @Nullable ErrorMessageHandler errorMessageHandler) {
        addOffer(offer, resultHandler, errorMessageHandler);
    }

    public void deactivateOffer(OfferPayload offerPayload,
                                @Nullable ResultHandler resultHandler,
                                @Nullable ErrorMessageHandler errorMessageHandler) {
        removeOffer(offerPayload, resultHandler, errorMessageHandler);
    }

    public void removeOffer(OfferPayload offerPayload,
                            @Nullable ResultHandler resultHandler,
                            @Nullable ErrorMessageHandler errorMessageHandler) {
        if (p2PService.removeData(offerPayload)) {
            if (resultHandler != null)
                resultHandler.handleResult();
        } else {
            if (errorMessageHandler != null)
                errorMessageHandler.handleErrorMessage("Remove offer failed");
        }
    }

    public List<Offer> getOffers() {
        return p2PService.getDataMap().values().stream()
                .filter(data -> data.getProtectedStoragePayload() instanceof OfferPayload)
                .map(data -> {
                    OfferPayload offerPayload = (OfferPayload) data.getProtectedStoragePayload();
                    Offer offer = new Offer(offerPayload);
                    offer.setPriceFeedService(priceFeedService);
                    setReservedFundsSpent(offer);
                    return offer;
                })
                .collect(Collectors.toList());
    }

    public List<Offer> getOffersByCurrency(String direction, String currencyCode) {
        return getOffers().stream()
                .filter(o -> o.getOfferPayload().getBaseCurrencyCode().equalsIgnoreCase(currencyCode) && o.getDirection().name() == direction)
                .collect(Collectors.toList());
    }

    public void removeOfferAtShutDown(OfferPayload offerPayload) {
        removeOffer(offerPayload, null, null);
    }

    public boolean isBootstrapped() {
        return p2PService.isBootstrapped();
    }

    public void addOfferBookChangedListener(OfferBookChangedListener offerBookChangedListener) {
        synchronized (offerBookChangedListeners) {
            offerBookChangedListeners.add(offerBookChangedListener);
        }
    }

    public void shutDown() {
        if (keyImagePoller != null) keyImagePoller.clearKeyImages();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private synchronized void maybeInitializeKeyImagePoller() {
        if (keyImagePoller != null) return;
        keyImagePoller = new XmrKeyImagePoller(xmrConnectionService.getDaemon(), getKeyImageRefreshPeriodMs());

        // handle when key images spent
        keyImagePoller.addListener(new XmrKeyImageListener() {
            @Override
            public void onSpentStatusChanged(Map<String, MoneroKeyImageSpentStatus> spentStatuses) {
                for (String keyImage : spentStatuses.keySet()) {
                    updateAffectedOffers(keyImage);
                }
            }
        });

        // first poll after 20s
        // TODO: remove?
        new Thread(() -> {
            GenUtils.waitFor(20000);
            keyImagePoller.poll();
        }).start();
    }

    private long getKeyImageRefreshPeriodMs() {
        return xmrConnectionService.isConnectionLocal() ? KEY_IMAGE_REFRESH_PERIOD_MS_LOCAL : KEY_IMAGE_REFRESH_PERIOD_MS_REMOTE;
    }

    private void updateAffectedOffers(String keyImage) {
        for (Offer offer : getOffers()) {
            if (offer.getOfferPayload().getReserveTxKeyImages().contains(keyImage)) {
                synchronized (offerBookChangedListeners) {
                    offerBookChangedListeners.forEach(listener -> {

                        // notify off thread to avoid deadlocking
                        new Thread(() -> {
                            listener.onRemoved(offer);
                            listener.onAdded(offer);
                        }).start();
                    });
                }
            }
        }
    }

    private void setReservedFundsSpent(Offer offer) {
        if (keyImagePoller == null) return;
        for (String keyImage : offer.getOfferPayload().getReserveTxKeyImages()) {
            if (Boolean.TRUE.equals(keyImagePoller.isSpent(keyImage))) {
                offer.setReservedFundsSpent(true);
            }
        }
    }

    private void doDumpStatistics() {
        // We filter the case that it is a MarketBasedPrice but the price is not available
        // That should only be possible if the price feed provider is not available
        final List<OfferForJson> offerForJsonList = getOffers().stream()
                .filter(offer -> !offer.isUseMarketBasedPrice() || priceFeedService.getMarketPrice(offer.getCurrencyCode()) != null)
                .map(offer -> {
                    try {
                        return new OfferForJson(offer.getDirection(),
                                offer.getCurrencyCode(),
                                offer.getMinAmount(),
                                offer.getAmount(),
                                offer.getPrice(),
                                offer.getDate(),
                                offer.getId(),
                                offer.isUseMarketBasedPrice(),
                                offer.getMarketPriceMarginPct(),
                                offer.getPaymentMethod()
                        );
                    } catch (Throwable t) {
                        // In case an offer was corrupted with null values we ignore it
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(offerForJsonList), "offers_statistics");
    }
}
