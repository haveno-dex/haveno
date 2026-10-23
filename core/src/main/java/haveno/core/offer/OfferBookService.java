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
import haveno.core.api.XmrKeyImageListener;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.User;
import haveno.core.util.JsonUtil;
import haveno.core.xmr.wallet.Restrictions;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.NetworkNotReadyException;
import haveno.network.p2p.NodeAddress;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import monero.daemon.model.MoneroKeyImageSpentStatus;

/**
 * Handles validation and announcement of offers added or removed.
 */
@Slf4j
public class OfferBookService {

    private final static long INVALID_OFFERS_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private final static int DELAY_LOG_INVALID_OFFER_SEC = 10;
    private static final int DUMP_STATISTICS_DELAY_SEC = 30;

    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final List<OfferBookChangedListener> offerBookChangedListeners = new LinkedList<>();
    private final FilterManager filterManager;
    private final JsonFileManager jsonFileManager;
    private final XmrConnectionService xmrConnectionService;
    private final ArbitratorManager arbitratorManager;
    private final User user;
    private Map<NodeAddress, Arbitrator> verifiedArbitrators = Map.of();
    private final List<Offer> validOffers = new ArrayList<Offer>();
    private final List<Offer> invalidOffers = new ArrayList<Offer>();
    private final Map<String, Timer> invalidOfferTimers = new HashMap<>();
    private final AtomicBoolean dumpStatisticsScheduled = new AtomicBoolean();
    private volatile boolean shutDownRequested;

    public interface OfferBookChangedListener {
        void onAdded(Offer offer);
        void onRemoved(Offer offer);
        void onRefresh(Offer offer);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OfferBookService(P2PService p2PService,
                            PriceFeedService priceFeedService,
                            FilterManager filterManager,
                            XmrConnectionService xmrConnectionService,
                            ArbitratorManager arbitratorManager,
                            User user,
                            @Named(Config.STORAGE_DIR) File storageDir,
                            @Named(Config.DUMP_STATISTICS) boolean dumpStatistics) {
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.xmrConnectionService = xmrConnectionService;
        this.arbitratorManager = arbitratorManager;
        this.user = user;
        jsonFileManager = new JsonFileManager(storageDir);

        // listen for offers
        p2PService.addHashSetChangedListener(new HashMapChangedListener() {
            @Override
            public void onAdded(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                if (protectedStorageEntries.stream().noneMatch(entry -> entry.getProtectedStoragePayload() instanceof OfferPayload
                        || entry.getProtectedStoragePayload() instanceof Arbitrator)) return;
                ThreadUtils.execute(() -> {
                    Map<NodeAddress, Arbitrator> arbitrators = arbitratorManager.getVerifiedDisputeAgents();
                    boolean newArbitrator = arbitrators.entrySet().stream()
                            .anyMatch(entry -> !entry.getValue().equals(verifiedArbitrators.get(entry.getKey())));
                    verifiedArbitrators = arbitrators;
                    protectedStorageEntries.forEach(entry -> addStoredOffer(entry, arbitrators, false));
                    // retry stored offers when their signing arbitrator arrives, even after the invalid-offer timeout
                    if (newArbitrator) p2PService.getDataMap().values().forEach(entry -> addStoredOffer(entry, arbitrators, true));
                }, OfferBookService.class.getSimpleName());
            }

            @Override
            public void onRemoved(Collection<ProtectedStorageEntry> protectedStorageEntries) {
                if (protectedStorageEntries.stream().noneMatch(entry -> entry.getProtectedStoragePayload() instanceof OfferPayload)) return;
                ThreadUtils.execute(() -> {
                    Map<NodeAddress, Arbitrator> arbitrators = arbitratorManager.getVerifiedDisputeAgents();
                    // forget removed registrations while leaving queued arrivals to trigger a rescan
                    verifiedArbitrators = new HashMap<>(verifiedArbitrators);
                    verifiedArbitrators.entrySet().retainAll(arbitrators.entrySet());
                    protectedStorageEntries.forEach(protectedStorageEntry -> {
                        if (protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload) {
                            OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
                            Offer offer = new Offer(offerPayload);
                            offer.setPriceFeedService(priceFeedService);
                            synchronized (validOffers) {
                                if (validOffers.removeIf(existing -> existing.getOfferPayload().equals(offerPayload))) {
                                    announceOfferRemoved(offer);
                                    // a stale revision can hide the current one until it is removed
                                    p2PService.getDataMap().values().stream()
                                            .filter(entry -> entry.getProtectedStoragePayload() instanceof OfferPayload
                                                    && ((OfferPayload) entry.getProtectedStoragePayload()).getId().equals(offerPayload.getId()))
                                            .forEach(entry -> addStoredOffer(entry, arbitrators, true));
                                }
                                synchronized (invalidOffers) {
                                    if (invalidOffers.stream().anyMatch(existing -> existing.getOfferPayload().equals(offerPayload))) removeInvalidOffer(offerPayload.getId());
                                }
                                refreshInvalidOffers(arbitrators);
                            }
                        }
                    });
                }, OfferBookService.class.getSimpleName());
            }
        });

        // retry stored offers when their signing arbitrator is unbanned
        filterManager.filterProperty().addListener((observable, oldValue, newValue) -> ThreadUtils.execute(() -> {
            Map<NodeAddress, Arbitrator> arbitrators = arbitratorManager.getVerifiedDisputeAgents();
            verifiedArbitrators = arbitrators;
            p2PService.getDataMap().values().forEach(entry -> addStoredOffer(entry, arbitrators, true));
        }, OfferBookService.class.getSimpleName()));

        if (dumpStatistics) {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onDataReceived() {
                    addOfferBookChangedListener(new OfferBookChangedListener() {
                        @Override
                        public void onAdded(Offer offer) {
                            maybeDumpStatistics();
                        }

                        @Override
                        public void onRemoved(Offer offer) {
                            maybeDumpStatistics();
                        }

                        @Override
                        public void onRefresh(Offer offer) {
                            maybeDumpStatistics();
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

        boolean result;
        try {
            result = p2PService.addProtectedStorageEntry(offer.getOfferPayload());
        } catch (NetworkNotReadyException e) {
            errorMessageHandler.handleErrorMessage("Add offer failed: the P2P network is not bootstrapped yet");
            return;
        }
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

        boolean result;
        try {
            result = p2PService.refreshTTL(offerPayload);
        } catch (NetworkNotReadyException e) {
            errorMessageHandler.handleErrorMessage("Refresh TTL failed: the P2P network is not bootstrapped yet");
            return;
        }
        if (result) {
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Refresh TTL failed.");
        }
    }

    public void activateOffer(Offer offer,
                              ResultHandler resultHandler,
                              ErrorMessageHandler errorMessageHandler) {
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
        boolean removed;
        try {
            removed = p2PService.removeData(offerPayload);
        } catch (NetworkNotReadyException e) {
            if (errorMessageHandler != null)
                errorMessageHandler.handleErrorMessage("Remove offer failed: the P2P network is not bootstrapped yet");
            return;
        }
        if (removed) {
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
                .filter(o -> o.getOfferPayload().getCounterCurrencyCode().equalsIgnoreCase(currencyCode) && o.getDirection().name().equals(direction))
                .collect(Collectors.toList());
    }

    public void removeOfferAtShutDown(OfferPayload offerPayload) {
        removeOffer(offerPayload, null, errorMessage ->
                log.warn("Remove offer at shutdown failed, offerId={}, {}", offerPayload.getId(), errorMessage));
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
        shutDownRequested = true;
        xmrConnectionService.getKeyImagePoller().removeKeyImages(OfferBookService.class.getName());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // a rescan leaves admitted offer ids alone, since stored revisions of an offer carry no order
    private void addStoredOffer(ProtectedStorageEntry protectedStorageEntry, Map<NodeAddress, Arbitrator> arbitrators, boolean rescan) {
        if (!(protectedStorageEntry.getProtectedStoragePayload() instanceof OfferPayload)) return;
        OfferPayload offerPayload = (OfferPayload) protectedStorageEntry.getProtectedStoragePayload();
        Offer offer = new Offer(offerPayload);
        offer.setPriceFeedService(priceFeedService);
        synchronized (validOffers) {
            try {
                if (rescan && hasValidOffer(offerPayload.getId())) return;
                if (!validateOfferPayload(offerPayload, arbitrators)) return;
                boolean removedSuperseded = removeSupersededOffers(offerPayload);
                removeInvalidOffer(offerPayload.getId());
                replaceValidOffer(offer);
                announceOfferAdded(offer);
                if (removedSuperseded) refreshInvalidOffers(arbitrators); // removed offers can unblock invalid offers
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring illegal offer {}: {}", offerPayload.getId(), e.getMessage());
            } catch (Exception e) {
                replaceInvalidOffer(offer); // offer can become valid later

                // wait to log invalid offer in case it becomes valid shortly after
                ThreadUtils.runAfter(() -> {
                    if (hasInvalidOffer(offerPayload.getId())) {
                        log.warn("Invalid offer added, offerId={}: {}", offerPayload.getId(), e.getMessage());
                    }
                }, DELAY_LOG_INVALID_OFFER_SEC);
            }
        }
    }

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
        synchronized (validOffers) {
            for (Offer offer : validOffers) {
                if (offer.getId().equals(offerId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean hasInvalidOffer(String offerId) {
        synchronized (invalidOffers) {
            for (Offer offer : invalidOffers) {
                if (offer.getId().equals(offerId)) {
                    return true;
                }
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

    private void refreshInvalidOffers(Map<NodeAddress, Arbitrator> arbitrators) {
        synchronized (invalidOffers) {
            for (Offer invalidOffer : new ArrayList<Offer>(invalidOffers)) {
                try {
                    if (!validateOfferPayload(invalidOffer.getOfferPayload(), arbitrators)) continue;
                    removeSupersededOffers(invalidOffer.getOfferPayload());
                    removeInvalidOffer(invalidOffer.getId());
                    replaceValidOffer(invalidOffer);
                    announceOfferAdded(invalidOffer);
                } catch (Exception e) {
                    // ignore
                }
            }
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
                }, INVALID_OFFERS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

    private boolean validateOfferPayload(OfferPayload offerPayload, Map<NodeAddress, Arbitrator> arbitrators) {

        // only arbitrator-verified offers may claim an id or reserve key images in the book
        if (offerPayload.getArbitratorSigner() == null || offerPayload.getArbitratorSignature() == null) {
            throw new IllegalArgumentException("Offer has no arbitrator signature");
        }
        Arbitrator arbitrator = arbitrators.get(offerPayload.getArbitratorSigner());
        if (arbitrator == null) {
            Arbitrator registered = user.getRegisteredArbitrator();
            if (registered != null && registered.getNodeAddress().equals(offerPayload.getArbitratorSigner())) arbitrator = registered;
        }
        if (arbitrator == null) return false; // retried from P2P storage when the arbitrator arrives
        if (!HavenoUtils.isArbitratorSignatureValid(offerPayload, arbitrator)) {
            throw new IllegalArgumentException("Offer arbitrator signature is invalid");
        }

        // validate offer is not banned
        if (filterManager.isOfferIdBanned(offerPayload.getId())) {
            throw new IllegalArgumentException("Offer is banned with offerId=" + offerPayload.getId());
        }

        // remove private offers with a buyer deposit if disabled
        if (!HavenoUtils.isGeneralPrivateOffersEnabled() && offerPayload.isPrivateOffer() && !offerPayload.isBuyerAsTakerWithoutDeposit()) {
            throw new IllegalArgumentException("Private offer with a buyer deposit is not enabled on this network with offerId=" + offerPayload.getId());
        }

        // validate v3 node address compliance
        boolean isV3NodeAddressCompliant = !OfferRestrictions.requiresNodeAddressUpdate() || Utils.isV3Address(offerPayload.getOwnerNodeAddress().getHostName());
        if (!isV3NodeAddressCompliant) {
            throw new IllegalArgumentException("Offer with non-V3 node address is not allowed with offerId=" + offerPayload.getId());
        }

        // validate market price margin
        double marketPriceMarginPct = offerPayload.getMarketPriceMarginPct();
        if (!OfferRestrictions.isValidMarketPriceMargin(offerPayload.getDirection(), marketPriceMarginPct)) {
            throw new IllegalArgumentException("Market price margin must be within 100% and keep the price positive but was " + (marketPriceMarginPct * 100) + "% with offerId=" + offerPayload.getId());
        }

        // validate fixed price is positive (market based offers have no fixed price)
        if (!offerPayload.isUseMarketBasedPrice() && offerPayload.getPrice() <= 0) {
            throw new IllegalArgumentException("Fixed price must be positive but was " + offerPayload.getPrice() + " with offerId=" + offerPayload.getId());
        }

        // validate against existing offers
        synchronized (validOffers) {
            int numOffersWithSharedKeyImages = 0;
            for (Offer validOffer : validOffers) {
                OfferPayload validPayload = validOffer.getOfferPayload();
                if (validPayload.getId().equals(offerPayload.getId()) && !validPayload.getPubKeyRing().equals(offerPayload.getPubKeyRing())) {
                    throw new IllegalStateException("Offer id is already owned by another maker");
                }

                // validate that no offer has overlapping but different key images, unless superseded
                if (hasConflictingKeyImages(offerPayload, validPayload) && !supersedes(offerPayload, validPayload)) {
                    throw new RuntimeException("Offer with overlapping but different key images already exists with offerId=" + validOffer.getId());
                }

                // validate that no offer has same key images, payment method, and currency, unless superseded
                if (isDuplicateClone(offerPayload, validPayload) && !supersedes(offerPayload, validPayload)) {
                    throw new RuntimeException("Offer with same key images, payment method, and currency already exists with offerId=" + validOffer.getId());
                }

                // count offers with same key images, excluding conflicting offers which are superseded
                if (!conflictsWith(offerPayload, validPayload) && !validOffer.getId().equals(offerPayload.getId()) && !Collections.disjoint(validPayload.getReserveTxKeyImages(), offerPayload.getReserveTxKeyImages())) numOffersWithSharedKeyImages = Math.max(2, numOffersWithSharedKeyImages + 1);
            }

            // validate max offers with same key images
            if (numOffersWithSharedKeyImages > Restrictions.getMaxOffersWithSharedFunds()) throw new RuntimeException("More than " + Restrictions.getMaxOffersWithSharedFunds() + " offers exist with same key images as new offerId=" + offerPayload.getId());
        }
        return true;
    }

    // remove valid offers superseded by the given offer payload, returning true if any removed
    private boolean removeSupersededOffers(OfferPayload offerPayload) {
        boolean removed = false;
        synchronized (validOffers) {
            for (Offer validOffer : new ArrayList<Offer>(validOffers)) {
                if (validOffer.getId().equals(offerPayload.getId())) continue;
                if (conflictsWith(offerPayload, validOffer.getOfferPayload())) {
                    log.info("Removing offer superseded by more recent offer, old offerId={}, new offerId={}", validOffer.getId(), offerPayload.getId());
                    removeValidOffer(validOffer.getId());
                    replaceInvalidOffer(validOffer); // can become valid again, e.g. if the superseding offer is removed
                    announceOfferRemoved(validOffer);
                    removed = true;
                }
            }
        }
        return removed;
    }

    // offers conflict if their key images overlap or they duplicate a clone, so only one can be valid
    private static boolean conflictsWith(OfferPayload offerPayload, OfferPayload otherPayload) {
        return hasConflictingKeyImages(offerPayload, otherPayload) || isDuplicateClone(offerPayload, otherPayload);
    }

    // key images conflict if they overlap but differ, expected when the maker re-reserves funds
    private static boolean hasConflictingKeyImages(OfferPayload offerPayload, OfferPayload otherPayload) {
        Set<String> keyImages = new HashSet<>(offerPayload.getReserveTxKeyImages());
        Set<String> otherKeyImages = new HashSet<>(otherPayload.getReserveTxKeyImages());
        return !keyImages.equals(otherKeyImages) && !Collections.disjoint(keyImages, otherKeyImages);
    }

    // cloned offers with the same key images, payment method, and currency are duplicates
    private static boolean isDuplicateClone(OfferPayload offerPayload, OfferPayload otherPayload) {
        return !offerPayload.getId().equals(otherPayload.getId()) &&
                new HashSet<>(offerPayload.getReserveTxKeyImages()).equals(new HashSet<>(otherPayload.getReserveTxKeyImages())) &&
                offerPayload.getPaymentMethodId().equals(otherPayload.getPaymentMethodId()) &&
                offerPayload.getBaseCurrencyCode().equals(otherPayload.getBaseCurrencyCode()) &&
                offerPayload.getCounterCurrencyCode().equals(otherPayload.getCounterCurrencyCode());
    }

    // a conflicting offer is superseded by a more recent offer from the same maker, with id as tiebreaker for determinism
    private static boolean supersedes(OfferPayload offerPayload, OfferPayload otherPayload) {
        if (!offerPayload.getPubKeyRing().equals(otherPayload.getPubKeyRing())) return false;
        if (offerPayload.getDate() != otherPayload.getDate()) return offerPayload.getDate() > otherPayload.getDate();
        return offerPayload.getId().compareTo(otherPayload.getId()) > 0;
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
                        listener.onRefresh(offer);
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

    private void maybeDumpStatistics() {
        // Dumping serializes the whole offer book, and offer refresh messages arrive frequently
        // from the whole network, so we batch frequent updates into one dump per interval.
        if (!dumpStatisticsScheduled.compareAndSet(false, true)) {
            return;
        }
        UserThread.runAfter(() -> {
            dumpStatisticsScheduled.set(false);
            if (shutDownRequested) {
                return;
            }
            doDumpStatistics();
        }, DUMP_STATISTICS_DELAY_SEC);
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
