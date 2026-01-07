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

import haveno.common.ThreadUtils;
import haveno.common.Timer;
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
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrKeyImageListener;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.HashMapChangedListener;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.utils.Utils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import monero.daemon.model.MoneroKeyImageSpentStatus;

/**
 * Handles validation and announcement of offers added or removed.
 */
@Slf4j
public class OfferBookService {

    private final static long INVALID_OFFERS_TIMEOUT = 5 * 60 * 1000; // 5 minutes

    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final List<OfferBookChangedListener> offerBookChangedListeners = new LinkedList<>();
    private final FilterManager filterManager;
    private final JsonFileManager jsonFileManager;
    private final XmrConnectionService xmrConnectionService;
    private final List<Offer> validOffers = new ArrayList<Offer>();
    private final List<Offer> invalidOffers = new ArrayList<Offer>();
    private final Map<String, Timer> invalidOfferTimers = new HashMap<>();

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

        // listen for offers
        p2PService.addHashSetChangedListener(new HashMapChangedListener() {
            @Override
            public void onAdded(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                ThreadUtils.execute(() -> {
                    protectedStorageEntries.forEach(protectedStorageEntry -> {
                        if (protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload) {
                            OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
                            Offer offer = new Offer(offerPayload);
                            offer.setPriceFeedService(priceFeedService);
                            synchronized (validOffers) {
                                try {
                                    validateOfferPayload(offerPayload);
                                    replaceValidOffer(offer);
                                    announceOfferAdded(offer);
                                } catch (IllegalArgumentException e) {
                                    log.warn("Ignoring invalid offer {}: {}", offerPayload.getId(), e.getMessage());
                                } catch (RuntimeException e) {
                                    replaceInvalidOffer(offer); // offer can become valid later
                                }
                            }
                        }
                    });
                }, OfferBookService.class.getSimpleName());
            }

            @Override
            public void onRemoved(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                ThreadUtils.execute(() -> {
                    protectedStorageEntries.forEach(protectedStorageEntry -> {
                        if (protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload) {
                            OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
                            removeValidOffer(offerPayload.getId());
                            Offer offer = new Offer(offerPayload);
                            offer.setPriceFeedService(priceFeedService);
                            announceOfferRemoved(offer);

                            // check if invalid offers are now valid
                            synchronized (invalidOffers) {
                                for (Offer invalidOffer : new ArrayList<Offer>(invalidOffers)) {
                                    try {
                                        validateOfferPayload(invalidOffer.getOfferPayload());
                                        removeInvalidOffer(invalidOffer.getId());
                                        replaceValidOffer(invalidOffer);
                                        announceOfferAdded(invalidOffer);
                                    } catch (Exception e) {
                                        // ignore
                                    }
                                }
                            }
                        }
                    });
                }, OfferBookService.class.getSimpleName());
            }
        });

        if (dumpStatistics) {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onDataReceived() {
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

        // listen for changes to key images
        xmrConnectionService.getKeyImagePoller().addListener(new XmrKeyImageListener() {
            @Override
            public void onSpentStatusChanged(Map<String, MoneroKeyImageSpentStatus> spentStatuses) {
                for (String keyImage : spentStatuses.keySet()) {
                    updateAffectedOffers(keyImage);
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean hasOffer(String offerId) {
        return hasValidOffer(offerId);
    }
    
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
        synchronized (validOffers) {
            return new ArrayList<>(validOffers);
        }
    }

    public List<Offer> getOffersByCurrency(String direction, String currencyCode) {
        return getOffers().stream()
                .filter(o -> o.getOfferPayload().getCounterCurrencyCode().equalsIgnoreCase(currencyCode) && o.getDirection().name() == direction)
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
        xmrConnectionService.getKeyImagePoller().removeKeyImages(OfferBookService.class.getName());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void announceOfferAdded(Offer offer) {
        xmrConnectionService.getKeyImagePoller().addKeyImages(offer.getOfferPayload().getReserveTxKeyImages(), OfferBookService.class.getSimpleName());
        updateReservedFundsSpentStatus(offer);
        synchronized (offerBookChangedListeners) {
            offerBookChangedListeners.forEach(listener -> listener.onAdded(offer));
        }
    }

    private void announceOfferRemoved(Offer offer) {
        updateReservedFundsSpentStatus(offer);
        removeKeyImages(offer);
        synchronized (offerBookChangedListeners) {
            offerBookChangedListeners.forEach(listener -> listener.onRemoved(offer));
        }
    }

    private boolean hasValidOffer(String offerId) {
        for (Offer offer : getOffers()) {
            if (offer.getId().equals(offerId)) {
                return true;
            }
        }
        return false;
    }
    
    private void replaceValidOffer(Offer offer) {
        synchronized (validOffers) {
            removeValidOffer(offer.getId());
            validOffers.add(offer);
        }
    }

    private void replaceInvalidOffer(Offer offer) {
        synchronized (invalidOffers) {
            removeInvalidOffer(offer.getId());
            invalidOffers.add(offer);

            // remove invalid offer after timeout
            synchronized (invalidOfferTimers) {
                Timer timer = invalidOfferTimers.get(offer.getId());
                if (timer != null) timer.stop();
                timer = UserThread.runAfter(() -> {
                    removeInvalidOffer(offer.getId());
                }, INVALID_OFFERS_TIMEOUT);
                invalidOfferTimers.put(offer.getId(), timer);
            }
        }
    }

    private void removeValidOffer(String offerId) {
        synchronized (validOffers) {
            validOffers.removeIf(offer -> offer.getId().equals(offerId));
        }
    }

    private void removeInvalidOffer(String offerId) {
        synchronized (invalidOffers) {
            invalidOffers.removeIf(offer -> offer.getId().equals(offerId));

            // remove timeout
            synchronized (invalidOfferTimers) {
                Timer timer = invalidOfferTimers.get(offerId);
                if (timer != null) timer.stop();
                invalidOfferTimers.remove(offerId);
            }
        }
    }

    private void validateOfferPayload(OfferPayload offerPayload) {

        // validate offer is not banned
        if (filterManager.isOfferIdBanned(offerPayload.getId())) {
            throw new IllegalArgumentException("Offer is banned with offerId=" + offerPayload.getId());
        }

        // validate v3 node address compliance
        boolean isV3NodeAddressCompliant = !OfferRestrictions.requiresNodeAddressUpdate() || Utils.isV3Address(offerPayload.getOwnerNodeAddress().getHostName());
        if (!isV3NodeAddressCompliant) {
            throw new IllegalArgumentException("Offer with non-V3 node address is not allowed with offerId=" + offerPayload.getId());
        }

        // validate market price margin
        double marketPriceMarginPct = offerPayload.getMarketPriceMarginPct();
        if (marketPriceMarginPct <= -1 || marketPriceMarginPct >= 1) {
            throw new IllegalArgumentException("Market price margin must be greater than -100% and less than 100% but was " + (marketPriceMarginPct * 100) + "% with offerId=" + offerPayload.getId());
        }

        // validate against existing offers
        synchronized (validOffers) {
            int numOffersWithSharedKeyImages = 0;
            for (Offer offer : validOffers) {

                // validate that no offer has overlapping but different key images
                if (!offer.getOfferPayload().getReserveTxKeyImages().equals(offerPayload.getReserveTxKeyImages()) && 
                        !Collections.disjoint(offer.getOfferPayload().getReserveTxKeyImages(), offerPayload.getReserveTxKeyImages())) {
                    throw new RuntimeException("Offer with overlapping key images already exists with offerId=" + offer.getId());
                }
    
                // validate that no offer has same key images, payment method, and currency
                if (!offer.getId().equals(offerPayload.getId()) && 
                        offer.getOfferPayload().getReserveTxKeyImages().equals(offerPayload.getReserveTxKeyImages()) &&
                        offer.getOfferPayload().getPaymentMethodId().equals(offerPayload.getPaymentMethodId()) &&
                        offer.getOfferPayload().getBaseCurrencyCode().equals(offerPayload.getBaseCurrencyCode()) &&
                        offer.getOfferPayload().getCounterCurrencyCode().equals(offerPayload.getCounterCurrencyCode())) {
                    throw new RuntimeException("Offer with same key images, payment method, and currency already exists with offerId=" + offer.getId());
                }
    
                // count offers with same key images
                if (!offer.getId().equals(offerPayload.getId()) && !Collections.disjoint(offer.getOfferPayload().getReserveTxKeyImages(), offerPayload.getReserveTxKeyImages())) numOffersWithSharedKeyImages = Math.max(2, numOffersWithSharedKeyImages + 1);
            }
    
            // validate max offers with same key images
            if (numOffersWithSharedKeyImages > Restrictions.getMaxOffersWithSharedFunds()) throw new RuntimeException("More than " + Restrictions.getMaxOffersWithSharedFunds() + " offers exist with same same key images as new offerId=" + offerPayload.getId());
        }
    }

    private void removeKeyImages(Offer offer) {
        Set<String> unsharedKeyImages = new HashSet<>(offer.getOfferPayload().getReserveTxKeyImages());
        synchronized (validOffers) {
            for (Offer validOffer : validOffers) {
                if (validOffer.getId().equals(offer.getId())) continue;
                unsharedKeyImages.removeAll(validOffer.getOfferPayload().getReserveTxKeyImages());
            }
        }
        xmrConnectionService.getKeyImagePoller().removeKeyImages(unsharedKeyImages, OfferBookService.class.getSimpleName());
    }
    
    private void updateAffectedOffers(String keyImage) {
        for (Offer offer : getOffers()) {
            if (offer.getOfferPayload().getReserveTxKeyImages().contains(keyImage)) {
                updateReservedFundsSpentStatus(offer);
                synchronized (offerBookChangedListeners) {
                    offerBookChangedListeners.forEach(listener -> {
                        listener.onRemoved(offer);
                        listener.onAdded(offer);
                    });
                }
            }
        }
    }

    private void updateReservedFundsSpentStatus(Offer offer) {
        for (String keyImage : offer.getOfferPayload().getReserveTxKeyImages()) {
            if (Boolean.TRUE.equals(xmrConnectionService.getKeyImagePoller().isSpent(keyImage))) {
                offer.setReservedFundsSpent(true);
            }
        }
    }

    private void doDumpStatistics() {
        // We filter the case that it is a MarketBasedPrice but the price is not available
        // That should only be possible if the price feed provider is not available
        final List<OfferForJson> offerForJsonList = getOffers().stream()
                .filter(offer -> !offer.isUseMarketBasedPrice() || priceFeedService.getMarketPrice(offer.getCounterCurrencyCode()) != null)
                .map(offer -> {
                    try {
                        return new OfferForJson(offer.getDirection(),
                                offer.getCounterCurrencyCode(),
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
