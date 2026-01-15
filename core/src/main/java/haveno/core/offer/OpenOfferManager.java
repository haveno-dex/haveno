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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Capabilities;
import haveno.common.app.Capability;
import haveno.common.app.Version;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.common.util.Tuple2;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.CoreContext;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrKeyImageListener;
import haveno.core.api.XmrKeyImagePoller;
import haveno.core.exceptions.TradePriceOutOfToleranceException;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.offer.messages.OfferAvailabilityRequest;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.core.offer.messages.SignOfferRequest;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.offer.placeoffer.PlaceOfferProtocol;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradableList;
import haveno.core.trade.handlers.TransactionResultHandler;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.JsonUtil;
import haveno.core.util.PriceUtil;
import haveno.core.util.Validator;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendDirectMessageListener;
import haveno.network.p2p.peers.Broadcaster;
import haveno.network.p2p.peers.PeerManager;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javax.annotation.Nullable;
import lombok.Getter;
import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroKeyImageSpentStatus;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenOfferManager implements PeerManager.Listener, DecryptedDirectMessageListener, PersistedDataHost {
    private static final Logger log = LoggerFactory.getLogger(OpenOfferManager.class);

    private static final String THREAD_ID = OpenOfferManager.class.getSimpleName();
    private static final long RETRY_REPUBLISH_DELAY_SEC = 10;
    private static final long REPUBLISH_AGAIN_AT_STARTUP_DELAY_SEC = 30;
    private static final long REPUBLISH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long REFRESH_INTERVAL_MS = OfferPayload.TTL / 2;
    private static final int NUM_ATTEMPTS_THRESHOLD = 5; // process offer only on republish cycle after this many attempts
    private static final long SHUTDOWN_TIMEOUT_MS = 60000;
    private static final String OPEN_OFFER_GROUP_KEY_IMAGE_ID = OpenOffer.class.getSimpleName();
    private static final String SIGNED_OFFER_KEY_IMAGE_GROUP_ID = SignedOffer.class.getSimpleName();

    private final CoreContext coreContext;
    private final KeyRing keyRing;
    private final User user;
    private final P2PService p2PService;
    @Getter
    private final XmrConnectionService xmrConnectionService;
    private final BtcWalletService btcWalletService;
    @Getter
    private final XmrWalletService xmrWalletService;
    private final TradeWalletService tradeWalletService;
    private final OfferBookService offerBookService;
    private final ClosedTradableManager closedTradableManager;
    private final PriceFeedService priceFeedService;
    private final Preferences preferences;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final FilterManager filterManager;
    private final Broadcaster broadcaster;
    private final PersistenceManager<TradableList<OpenOffer>> persistenceManager;
    private final Map<String, OpenOffer> offersToBeEdited = new HashMap<>();
    private final TradableList<OpenOffer> openOffers = new TradableList<>();
    private final SignedOfferList signedOffers = new SignedOfferList();
    private final PersistenceManager<SignedOfferList> signedOfferPersistenceManager;
    private final Map<String, PlaceOfferProtocol> placeOfferProtocols = new HashMap<String, PlaceOfferProtocol>();
    private boolean stopped;
    private Timer periodicRepublishOffersTimer, periodicRefreshOffersTimer, retryRepublishOffersTimer;
    @Getter
    private final ObservableList<Tuple2<OpenOffer, String>> invalidOffers = FXCollections.observableArrayList();
    @Getter
    private final AccountAgeWitnessService accountAgeWitnessService;

    private Object processOffersLock = new Object(); // lock for processing offers


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OpenOfferManager(CoreContext coreContext,
                            KeyRing keyRing,
                            User user,
                            P2PService p2PService,
                            XmrConnectionService xmrConnectionService,
                            BtcWalletService btcWalletService,
                            XmrWalletService xmrWalletService,
                            TradeWalletService tradeWalletService,
                            OfferBookService offerBookService,
                            ClosedTradableManager closedTradableManager,
                            PriceFeedService priceFeedService,
                            Preferences preferences,
                            TradeStatisticsManager tradeStatisticsManager,
                            ArbitratorManager arbitratorManager,
                            MediatorManager mediatorManager,
                            FilterManager filterManager,
                            Broadcaster broadcaster,
                            PersistenceManager<TradableList<OpenOffer>> persistenceManager,
                            PersistenceManager<SignedOfferList> signedOfferPersistenceManager,
                            AccountAgeWitnessService accountAgeWitnessService) {
        this.coreContext = coreContext;
        this.keyRing = keyRing;
        this.user = user;
        this.p2PService = p2PService;
        this.xmrConnectionService = xmrConnectionService;
        this.btcWalletService = btcWalletService;
        this.xmrWalletService = xmrWalletService;
        this.tradeWalletService = tradeWalletService;
        this.offerBookService = offerBookService;
        this.closedTradableManager = closedTradableManager;
        this.priceFeedService = priceFeedService;
        this.preferences = preferences;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.filterManager = filterManager;
        this.broadcaster = broadcaster;
        this.persistenceManager = persistenceManager;
        this.signedOfferPersistenceManager = signedOfferPersistenceManager;
        this.accountAgeWitnessService = accountAgeWitnessService;

        HavenoUtils.openOfferManager = this;
        ThreadUtils.reset(THREAD_ID);

        this.persistenceManager.initialize(openOffers, "OpenOffers", PersistenceManager.Source.PRIVATE);
        this.signedOfferPersistenceManager.initialize(signedOffers, "SignedOffers", PersistenceManager.Source.PRIVATE); // arbitrator stores reserve tx for signed offers
    }

    @Override
    public void readPersisted(Runnable completeHandler) {

        // read open offers
        persistenceManager.readPersisted(persisted -> {
            openOffers.setAll(persisted.getList());
            openOffers.forEach(openOffer -> openOffer.getOffer().setPriceFeedService(priceFeedService));

            // read signed offers
            signedOfferPersistenceManager.readPersisted(signedOfferPersisted -> {
                signedOffers.setAll(signedOfferPersisted.getList());
                completeHandler.run();
            },
            completeHandler);
        },
        completeHandler);
    }

    public void onAllServicesInitialized() {
        p2PService.addDecryptedDirectMessageListener(this);

        if (p2PService.isBootstrapped()) {
            onBootstrapComplete();
        } else {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onDataReceived() {
                    onBootstrapComplete();
                }
            });
        }

        cleanUpAddressEntries();
    }

    private void cleanUpAddressEntries() {
        Set<String> openOffersIdSet;
        synchronized (openOffers.getList()) {
            openOffersIdSet = openOffers.getList().stream().map(OpenOffer::getId).collect(Collectors.toSet());
        }
        xmrWalletService.getAddressEntriesForOpenOffer().stream()
                .filter(e -> !openOffersIdSet.contains(e.getOfferId()))
                .forEach(e -> {
                    log.warn("We found an outdated addressEntry with context {} for openOffer {} (openOffers does not contain that " +
                                    "offer), offers.size={}",
                            e.getContext(),
                            e.getOfferId(), openOffers.size());
                    xmrWalletService.resetAddressEntriesForOpenOffer(e.getOfferId());
                });
    }

    public void shutDown(@Nullable Runnable completeHandler) {
        stopped = true;
        p2PService.getPeerManager().removeListener(this);
        p2PService.removeDecryptedDirectMessageListener(this);
        xmrConnectionService.getKeyImagePoller().removeKeyImages(OPEN_OFFER_GROUP_KEY_IMAGE_ID);
        xmrConnectionService.getKeyImagePoller().removeKeyImages(SIGNED_OFFER_KEY_IMAGE_GROUP_ID);

        stopPeriodicRefreshOffersTimer();
        stopPeriodicRepublishOffersTimer();
        stopRetryRepublishOffersTimer();

        // we remove own offers from offerbook when we go offline
        // Normally we use a delay for broadcasting to the peers, but at shut down we want to get it fast out
        int size = openOffers.size();
        log.info("Remove open offers at shutDown. Number of open offers: {}", size);
        if (offerBookService.isBootstrapped() && size > 0) {
            ThreadUtils.execute(() -> {

                // remove offers from offer book
                synchronized (openOffers.getList()) {
                    openOffers.forEach(openOffer -> {
                        if (openOffer.getState() == OpenOffer.State.AVAILABLE) {
                            offerBookService.removeOfferAtShutDown(openOffer.getOffer().getOfferPayload());
                        }
                    });
                }

                // Force broadcaster to send out immediately, otherwise we could have a 2 sec delay until the
                // bundled messages sent out.
                broadcaster.flush();
                // For typical number of offers we are tolerant with delay to give enough time to broadcast.
                // If number of offers is very high we limit to 3 sec. to not delay other shutdown routines.
                long delayMs = Math.min(3000, size * 200 + 500);
                HavenoUtils.waitFor(delayMs);
            }, THREAD_ID);
        } else {
            broadcaster.flush();
        }

        // shut down thread pool off main thread
        ThreadUtils.submitToPool(() -> {
            shutDownThreadPool();

            // invoke completion handler
            if (completeHandler != null) completeHandler.run();
        });
    }

    private void shutDownThreadPool() {
        try {
            ThreadUtils.shutDown(THREAD_ID, SHUTDOWN_TIMEOUT_MS);
        } catch (Exception e) {
            log.error("Error shutting down OpenOfferManager thread pool", e);
        }
    }

    public void removeAllOpenOffers(@Nullable Runnable completeHandler) {
        removeOpenOffers(getObservableList(), completeHandler);
    }

    private void removeOpenOffers(List<OpenOffer> openOffers, @Nullable Runnable completeHandler) {
        synchronized (openOffers) {
            int size = openOffers.size();
            // Copy list as we remove in the loop
            List<OpenOffer> openOffersList = new ArrayList<>(openOffers);
            openOffersList.forEach(openOffer -> cancelOpenOffer(openOffer, () -> {
            }, errorMessage -> {
                log.warn("Error removing open offer: " + errorMessage);
            }));
            if (completeHandler != null)
                UserThread.runAfter(completeHandler, size * 200 + 500, TimeUnit.MILLISECONDS);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peerNodeAddress) {
        // Handler for incoming offer availability requests
        // We get an encrypted message but don't do the signature check as we don't know the peer yet.
        // A basic sig check is in done also at decryption time
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (networkEnvelope instanceof SignOfferRequest) {
            handleSignOfferRequest((SignOfferRequest) networkEnvelope, peerNodeAddress);
        } if (networkEnvelope instanceof SignOfferResponse) {
            handleSignOfferResponse((SignOfferResponse) networkEnvelope, peerNodeAddress);
        } else if (networkEnvelope instanceof OfferAvailabilityRequest) {
            handleOfferAvailabilityRequest((OfferAvailabilityRequest) networkEnvelope, peerNodeAddress);
        } else if (networkEnvelope instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) networkEnvelope;
            if (ackMessage.getSourceType() == AckMessageSourceType.OFFER_MESSAGE) {
                if (ackMessage.isSuccess()) {
                    log.info("Received AckMessage for {} with offerId {} and uid {}",
                            ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getSourceUid());
                } else {
                    log.warn("Received AckMessage with error state for {} with offerId {} and errorMessage={}",
                            ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getErrorMessage());
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BootstrapListener delegate
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onBootstrapComplete() {
        stopped = false;

        maybeUpdatePersistedOffers();

        // listen for spent key images to close open and signed offers
        xmrConnectionService.getKeyImagePoller().addListener(new XmrKeyImageListener() {
            @Override
            public void onSpentStatusChanged(Map<String, MoneroKeyImageSpentStatus> spentStatuses) {
                for (Entry<String, MoneroKeyImageSpentStatus> entry : spentStatuses.entrySet()) {
                    if (XmrKeyImagePoller.isSpent(entry.getValue())) {
                        cancelOpenOffersOnSpent(entry.getKey());
                        removeSignedOffers(entry.getKey());
                    }
                }
            }
        });

        // run off user thread so app is not blocked from starting
        ThreadUtils.submitToPool(() -> {

            // wait for prices to be available
            priceFeedService.awaitExternalPrices();

            // process open offers on dedicated thread
            ThreadUtils.execute(() -> {

                // Republish means we send the complete offer object
                republishOffers();
                startPeriodicRepublishOffersTimer();

                // Refresh is started once we get a success from republish

                // We republish after a bit as it might be that our connected node still has the offer in the data map
                // but other peers have it already removed because of expired TTL.
                // Those other not directly connected peers would not get the broadcast of the new offer, as the first
                // connected peer (seed node) does not broadcast if it has the data in the map.
                // To update quickly to the whole network we repeat the republishOffers call after a few seconds when we
                // are better connected to the network. There is no guarantee that all peers will receive it but we also
                // have our periodic timer, so after that longer interval the offer should be available to all peers.
                if (retryRepublishOffersTimer == null)
                    retryRepublishOffersTimer = UserThread.runAfter(OpenOfferManager.this::republishOffers,
                            REPUBLISH_AGAIN_AT_STARTUP_DELAY_SEC);

                p2PService.getPeerManager().addListener(this);

                // TODO: add to invalid offers on failure
        //        openOffers.stream()
        //                .forEach(openOffer -> OfferUtil.getInvalidMakerFeeTxErrorMessage(openOffer.getOffer(), btcWalletService)
        //                        .ifPresent(errorMsg -> invalidOffers.add(new Tuple2<>(openOffer, errorMsg))));

                // processs offers
                processOffers(false, (transaction) -> {}, (errorMessage) -> {
                    log.warn("Error processing offers on bootstrap: " + errorMessage);
                });

                // register to process offers on new block
                xmrWalletService.addWalletListener(new MoneroWalletListener() {
                    @Override
                    public void onNewBlock(long height) {

                        // process each offer on new block a few times, then rely on period republish
                        processOffers(true, (transaction) -> {}, (errorMessage) -> {
                            log.warn("Error processing offers on new block {}: {}", height, errorMessage);
                        });
                    }
                });

                // poll spent status of open offer key images
                synchronized (openOffers.getList()) {
                    for (OpenOffer openOffer : openOffers.getList()) {
                        xmrConnectionService.getKeyImagePoller().addKeyImages(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages(), OPEN_OFFER_GROUP_KEY_IMAGE_ID);
                    }
                }

                // poll spent status of signed offer key images
                synchronized (signedOffers.getList()) {
                    for (SignedOffer signedOffer : signedOffers.getList()) {
                        xmrConnectionService.getKeyImagePoller().addKeyImages(signedOffer.getReserveTxKeyImages(), SIGNED_OFFER_KEY_IMAGE_GROUP_ID);
                    }
                }
            }, THREAD_ID);
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PeerManager.Listener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllConnectionsLost() {
        log.info("onAllConnectionsLost");
        stopped = true;
        stopPeriodicRefreshOffersTimer();
        stopPeriodicRepublishOffersTimer();
        stopRetryRepublishOffersTimer();

        restart();
    }

    @Override
    public void onNewConnectionAfterAllConnectionsLost() {
        log.info("onNewConnectionAfterAllConnectionsLost");
        stopped = false;
        restart();
    }

    @Override
    public void onAwakeFromStandby() {
        log.info("onAwakeFromStandby");
        stopped = false;
        if (!p2PService.getNetworkNode().getAllConnections().isEmpty())
            restart();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void placeOffer(Offer offer,
                           boolean useSavingsWallet,
                           long triggerPrice,
                           boolean reserveExactAmount,
                           boolean resetAddressEntriesOnError,
                           String sourceOfferId,
                           TransactionResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {
        ThreadUtils.execute(() -> {

            // cannot set trigger price for fixed price offers
            if (triggerPrice != 0 && offer.getOfferPayload().getPrice() != 0) {
                errorMessageHandler.handleErrorMessage("Cannot set trigger price for fixed price offers.");
                return;
            }

            // check source offer and clone limit
            OpenOffer sourceOffer = null;
            if (sourceOfferId != null) {

                // get source offer
                Optional<OpenOffer> sourceOfferOptional = getOpenOffer(sourceOfferId);
                if (!sourceOfferOptional.isPresent()) {
                    errorMessageHandler.handleErrorMessage("Source offer not found to clone, offerId=" + sourceOfferId + ".");
                    return;
                }
                sourceOffer = sourceOfferOptional.get();

                // check clone limit
                int numClones = getOpenOfferGroup(sourceOffer.getGroupId()).size();
                if (numClones >= Restrictions.getMaxOffersWithSharedFunds()) {
                    errorMessageHandler.handleErrorMessage("Cannot create offer because maximum number of " + Restrictions.getMaxOffersWithSharedFunds() + " cloned offers with shared funds reached.");
                    return;
                }
            }

            // create open offer
            OpenOffer openOffer = new OpenOffer(offer, triggerPrice, sourceOffer == null ? reserveExactAmount : sourceOffer.isReserveExactAmount());

            // set state from source offer
            if (sourceOffer != null) {
                openOffer.setReserveTxHash(sourceOffer.getReserveTxHash());
                openOffer.setReserveTxHex(sourceOffer.getReserveTxHex());
                openOffer.setReserveTxKey(sourceOffer.getReserveTxKey());
                openOffer.setGroupId(sourceOffer.getGroupId());
                openOffer.getOffer().getOfferPayload().setReserveTxKeyImages(sourceOffer.getOffer().getOfferPayload().getReserveTxKeyImages());
                xmrWalletService.cloneAddressEntries(sourceOffer.getOffer().getId(), openOffer.getOffer().getId());
                if (hasConflictingClone(openOffer)) openOffer.setState(OpenOffer.State.DEACTIVATED);
            }

            // add the open offer
            synchronized (processOffersLock) {
                addOpenOffer(openOffer);
            }

            // done if source offer is pending
            if (sourceOffer != null && sourceOffer.isPending()) {
                resultHandler.handleResult(null);
                return;
            }

            // schedule or post offer
            synchronized (processOffersLock) {
                CountDownLatch latch = new CountDownLatch(1);
                processOffer(getOpenOffers(), openOffer, (transaction) -> {
                    requestPersistence();
                    latch.countDown();
                    resultHandler.handleResult(transaction);
                }, (errorMessage) -> {
                    if (!openOffer.isCanceled()) {
                        log.warn("Error processing offer {}: {}", openOffer.getId(), errorMessage);
                        doCancelOffer(openOffer, resetAddressEntriesOnError);
                    }
                    latch.countDown();
                    errorMessageHandler.handleErrorMessage(errorMessage);
                });
                HavenoUtils.awaitLatch(latch);
            }
        }, THREAD_ID);
    }

    // Remove from offerbook
    public void removeOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Optional<OpenOffer> openOfferOptional = getOpenOffer(offer.getId());
        if (openOfferOptional.isPresent()) {
            cancelOpenOffer(openOfferOptional.get(), resultHandler, errorMessageHandler);
        } else {
            String errorMsg = "Offer was not found in our list of open offers. We still try to remove it from the offerbook.";
            log.warn(errorMsg);
            errorMessageHandler.handleErrorMessage(errorMsg);
            offerBookService.removeOffer(offer.getOfferPayload(), () -> offer.setState(Offer.State.REMOVED), null);
        }
    }

    public void activateOpenOffer(OpenOffer openOffer,
                                  ResultHandler resultHandler,
                                  ErrorMessageHandler errorMessageHandler) {
        if (openOffer.isPending()) {
            resultHandler.handleResult(); // ignore if pending
        } else if (offersToBeEdited.containsKey(openOffer.getId())) {
            errorMessageHandler.handleErrorMessage(Res.get("offerbook.cannotActivateEditedOffer.warning"));
        } else if (hasConflictingClone(openOffer)) {
            errorMessageHandler.handleErrorMessage(Res.get("offerbook.hasConflictingClone.warning"));
        } else {
            try {

                // validate arbitrator signature
                validateSignedState(openOffer);

                // activate offer on offer book
                Offer offer = openOffer.getOffer();
                offerBookService.activateOffer(offer,
                        () -> {
                            openOffer.setState(OpenOffer.State.AVAILABLE);
                            applyTriggerState(openOffer);
                            requestPersistence();
                            log.debug("activateOpenOffer, offerId={}", offer.getId());
                            resultHandler.handleResult();
                        },
                        errorMessageHandler);
            } catch (Exception e) {
                errorMessageHandler.handleErrorMessage(e.getMessage());
                return;
            }
        }
    }

    private void applyTriggerState(OpenOffer openOffer) {
        if (openOffer.getState() != OpenOffer.State.AVAILABLE) return;
        if (TriggerPriceService.isTriggered(priceFeedService.getMarketPrice(openOffer.getOffer().getCounterCurrencyCode()), openOffer)) {
            openOffer.deactivate(true);
        }
    }

    public void deactivateOpenOffer(OpenOffer openOffer,
                                    boolean deactivatedByTrigger,
                                    ResultHandler resultHandler,
                                    ErrorMessageHandler errorMessageHandler) {
        Offer offer = openOffer.getOffer();
        if (openOffer.isAvailable()) {
            offerBookService.deactivateOffer(offer.getOfferPayload(),
                    () -> {
                        openOffer.deactivate(deactivatedByTrigger);
                        requestPersistence();
                        log.debug("deactivateOpenOffer, offerId={}", offer.getId());
                        resultHandler.handleResult();
                    },
                    errorMessageHandler);
        } else {
            resultHandler.handleResult(); // ignore if unavailable
        }
    }

    public void cancelOpenOffer(OpenOffer openOffer,
                                ResultHandler resultHandler,
                                ErrorMessageHandler errorMessageHandler) {
        log.info("Canceling open offer: {}", openOffer.getId());
        if (!offersToBeEdited.containsKey(openOffer.getId())) {
            if (isOnOfferBook(openOffer)) {
                openOffer.setState(OpenOffer.State.CANCELED);
                offerBookService.removeOffer(openOffer.getOffer().getOfferPayload(),
                        () -> {
                            ThreadUtils.submitToPool(() -> { // TODO: this runs off thread and then shows popup when done. should show overlay spinner until done
                                doCancelOffer(openOffer);
                                if (resultHandler != null) resultHandler.handleResult();
                            });
                        },
                        errorMessageHandler);
            } else {
                openOffer.setState(OpenOffer.State.CANCELED);
                ThreadUtils.submitToPool(() -> {
                    doCancelOffer(openOffer);
                    if (resultHandler != null) resultHandler.handleResult();
                });
            }
        } else {
            if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage("You can't cancel an offer that is currently edited.");
        }
    }

    private boolean isOnOfferBook(OpenOffer openOffer) {
        return openOffer.isAvailable() || openOffer.isReserved();
    }

    public void editOpenOfferStart(OpenOffer openOffer,
                                   ResultHandler resultHandler,
                                   ErrorMessageHandler errorMessageHandler) {
        if (offersToBeEdited.containsKey(openOffer.getId())) {
            log.warn("editOpenOfferStart called for an offer which is already in edit mode.");
            resultHandler.handleResult();
            return;
        }

        log.info("Editing open offer: {}", openOffer.getId());
        offersToBeEdited.put(openOffer.getId(), openOffer);

        if (openOffer.isAvailable()) {
            deactivateOpenOffer(openOffer,
                    false,
                    resultHandler,
                    errorMessage -> {
                        offersToBeEdited.remove(openOffer.getId());
                        errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } else {
            resultHandler.handleResult();
        }
    }

    public void editOpenOfferPublish(Offer editedOffer,
                                     long triggerPrice,
                                     OpenOffer.State originalState,
                                     ResultHandler resultHandler,
                                     ErrorMessageHandler errorMessageHandler) {
        ThreadUtils.execute(() -> {
            Optional<OpenOffer> openOfferOptional = getOpenOffer(editedOffer.getId());

            // check that trigger price is not set for fixed price offers
            boolean isFixedPrice = editedOffer.getOfferPayload().getPrice() != 0;
            if (triggerPrice != 0 && isFixedPrice) {
                errorMessageHandler.handleErrorMessage("Cannot set trigger price for fixed price offers.");
                return;
            }

            if (openOfferOptional.isPresent()) {
                OpenOffer openOffer = openOfferOptional.get();

                openOffer.getOffer().setState(Offer.State.REMOVED);
                openOffer.setState(OpenOffer.State.CANCELED);
                removeOpenOffer(openOffer);

                OpenOffer editedOpenOffer = new OpenOffer(editedOffer, triggerPrice, openOffer);
                if (originalState == OpenOffer.State.DEACTIVATED && openOffer.isDeactivatedByTrigger()) {
                    if (hasConflictingClone(editedOpenOffer)) {
                        editedOpenOffer.setState(OpenOffer.State.DEACTIVATED);
                    } else {
                        editedOpenOffer.setState(OpenOffer.State.AVAILABLE);
                    }
                } else {
                    if (originalState == OpenOffer.State.AVAILABLE && hasConflictingClone(editedOpenOffer)) {
                        editedOpenOffer.setState(OpenOffer.State.DEACTIVATED);
                    } else {
                        editedOpenOffer.setState(originalState);
                    }
                }
                
                applyTriggerState(editedOpenOffer); // apply trigger state before adding so it's not immediately removed
                addOpenOffer(editedOpenOffer);

                // check for valid arbitrator signature after editing
                Arbitrator arbitrator = user.getAcceptedArbitratorByAddress(editedOpenOffer.getOffer().getOfferPayload().getArbitratorSigner());
                if (arbitrator == null || !HavenoUtils.isArbitratorSignatureValid(editedOpenOffer.getOffer().getOfferPayload(), arbitrator)) {

                    // reset arbitrator signature
                    editedOpenOffer.getOffer().getOfferPayload().setArbitratorSignature(null);
                    editedOpenOffer.getOffer().getOfferPayload().setArbitratorSigner(null);
                    if (editedOpenOffer.isAvailable()) editedOpenOffer.setState(OpenOffer.State.PENDING);

                    // process offer to sign and publish
                    synchronized (processOffersLock) {
                        CountDownLatch latch = new CountDownLatch(1);
                        processOffer(getOpenOffers(), editedOpenOffer, (transaction) -> {
                            offersToBeEdited.remove(openOffer.getId());
                            requestPersistence();
                            latch.countDown();
                            resultHandler.handleResult();
                        }, (errorMsg) -> {
                            latch.countDown();
                            errorMessageHandler.handleErrorMessage(errorMsg);
                        });
                        HavenoUtils.awaitLatch(latch);
                    }
                } else {
                    maybeRepublishOffer(editedOpenOffer, null);
                    offersToBeEdited.remove(openOffer.getId());
                    requestPersistence();
                    resultHandler.handleResult();
                }
            } else {
                errorMessageHandler.handleErrorMessage("There is no offer with this id existing to be published.");
            }
        }, THREAD_ID);
    }

    public void editOpenOfferCancel(OpenOffer openOffer,
                                    OpenOffer.State originalState,
                                    ResultHandler resultHandler,
                                    ErrorMessageHandler errorMessageHandler) {
        if (offersToBeEdited.containsKey(openOffer.getId())) {
            offersToBeEdited.remove(openOffer.getId());
            if (originalState.equals(OpenOffer.State.AVAILABLE)) {
                activateOpenOffer(openOffer, resultHandler, errorMessageHandler);
            } else {
                resultHandler.handleResult();
            }
            requestPersistence();
        } else {
            errorMessageHandler.handleErrorMessage("Editing of offer can't be canceled as it is not edited.");
        }
    }

    private void doCancelOffer(OpenOffer openOffer) {
        doCancelOffer(openOffer, true);
    }

    // cancel open offer which thaws its key images
    private void doCancelOffer(@NotNull OpenOffer openOffer, boolean resetAddressEntries) {
        Offer offer = openOffer.getOffer();
        offer.setState(Offer.State.REMOVED);
        openOffer.setState(OpenOffer.State.CANCELED);
        boolean hasClonedOffer = hasClonedOffer(offer.getId()); // record before removing open offer
        removeOpenOffer(openOffer); 
        if (!hasClonedOffer) closedTradableManager.add(openOffer); // do not add clones to closed trades TODO: don't add canceled offers to closed tradables?
        if (resetAddressEntries) xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId());
        requestPersistence();
        if (!hasClonedOffer) xmrWalletService.thawOutputs(offer.getOfferPayload().getReserveTxKeyImages());
    }

    // close open offer group after key images spent
    public void closeSpentOffer(Offer offer) {
        getOpenOffer(offer.getId()).ifPresent(openOffer -> {
            for (OpenOffer groupOffer: getOpenOfferGroup(openOffer.getGroupId())) {
                doCloseOpenOffer(groupOffer);
            }
        });
    }

    private void doCloseOpenOffer(OpenOffer openOffer) {
        removeOpenOffer(openOffer);
        openOffer.setState(OpenOffer.State.CLOSED);
        xmrWalletService.resetAddressEntriesForOpenOffer(openOffer.getId());
        offerBookService.removeOffer(openOffer.getOffer().getOfferPayload(),
                () -> log.info("Successfully removed offer {}", openOffer.getId()),
                log::error);
        requestPersistence();
    }

    public void reserveOpenOffer(OpenOffer openOffer) {
        openOffer.setState(OpenOffer.State.RESERVED);
        requestPersistence();
    }

    public void unreserveOpenOffer(OpenOffer openOffer) {
        openOffer.setState(OpenOffer.State.AVAILABLE);
        requestPersistence();
    }

    public boolean hasConflictingClone(OpenOffer openOffer) {
        return hasConflictingClone(getOpenOffers(), openOffer);
    }

    private static boolean hasConflictingClone(List<OpenOffer> openOffers, OpenOffer openOffer) {
        for (OpenOffer clonedOffer : getOpenOfferGroup(openOffers, openOffer.getGroupId())) {
            if (clonedOffer.getId().equals(openOffer.getId())) continue;
            if (clonedOffer.isDeactivated()) continue; // deactivated offers do not conflict

            // pending offers later in the order do not conflict
            if (clonedOffer.isPending() && openOffers.indexOf(clonedOffer) > openOffers.indexOf(openOffer)) {
                continue;
            }

            // conflicts if same payment method and currency
            if (samePaymentMethodAndCurrency(clonedOffer.getOffer(), openOffer.getOffer())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasConflictingClone(Offer offer, OpenOffer sourceOffer) {
        return hasConflictingClone(getOpenOffers(), offer, sourceOffer);
    }

    private static boolean hasConflictingClone(List<OpenOffer> openOffers, Offer offer, OpenOffer sourceOffer) {
        return getOpenOfferGroup(openOffers, sourceOffer.getGroupId()).stream()
                .filter(openOffer -> !openOffer.isDeactivated()) // we only check with activated offers
                .anyMatch(openOffer -> samePaymentMethodAndCurrency(openOffer.getOffer(), offer));
    }

    private static boolean samePaymentMethodAndCurrency(Offer offer1, Offer offer2) {
        return offer1.getPaymentMethodId().equalsIgnoreCase(offer2.getPaymentMethodId()) &&
                offer1.getCounterCurrencyCode().equalsIgnoreCase(offer2.getCounterCurrencyCode()) &&
                offer1.getBaseCurrencyCode().equalsIgnoreCase(offer2.getBaseCurrencyCode());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public boolean hasAvailableOpenOffers() {
        for (OpenOffer openOffer : getOpenOffers()) {
            if (openOffer.getState() == OpenOffer.State.AVAILABLE) {
                return true;
            }
        }
        return false;
    }

    public List<OpenOffer> getOpenOffers() {
        synchronized (openOffers.getList()) {
            return ImmutableList.copyOf(getObservableList());
        }
    }

    public List<OpenOffer> getOpenOfferGroup(String groupId) {
        return getOpenOfferGroup(getOpenOffers(), groupId);
    }

    private static List<OpenOffer> getOpenOfferGroup(List<OpenOffer> openOffers, String groupId) {
        if (groupId == null) throw new IllegalArgumentException("groupId cannot be null");
        return openOffers.stream()
                .filter(openOffer -> groupId.equals(openOffer.getGroupId()))
                .collect(Collectors.toList());
    }

    public boolean hasClonedOffer(String offerId) {
        return hasClonedOffer(getOpenOffers(), offerId);
    }

    private static boolean hasClonedOffer(List<OpenOffer> openOffers, String offerId) {
        OpenOffer openOffer = getOpenOffer(openOffers, offerId).orElse(null);
        if (openOffer == null) return false;
        return getOpenOfferGroup(openOffers, openOffer.getGroupId()).size() > 1;
    }

    public boolean hasClonedOffers() {
        List<OpenOffer> openOffers = getOpenOffers();
        for (OpenOffer openOffer : openOffers) {
            if (getOpenOfferGroup(openOffers, openOffer.getGroupId()).size() > 1) {
                return true;
            }
        }
        return false;
    }

    public List<SignedOffer> getSignedOffers() {
        synchronized (signedOffers.getList()) {
            return ImmutableList.copyOf(signedOffers.getObservableList());
        }
    }

    public ObservableList<SignedOffer> getObservableSignedOffersList() {
        synchronized (signedOffers.getList()) {
            return signedOffers.getObservableList();
        }
    }

    public ObservableList<OpenOffer> getObservableList() {
        return openOffers.getObservableList();
    }

    public Optional<OpenOffer> getOpenOffer(String offerId) {
        return getOpenOffer(getOpenOffers(), offerId);
    }

    private static Optional<OpenOffer> getOpenOffer(List<OpenOffer> openOffers, String offerId) {
        return openOffers.stream().filter(e -> e.getId().equals(offerId)).findFirst();
    }

    private static boolean hasOpenOffer(List<OpenOffer> openOffers, String offerId) {
        return getOpenOffer(openOffers, offerId).isPresent();
    }

    public Optional<SignedOffer> getSignedOfferById(String offerId) {
        return getSignedOffers().stream().filter(e -> e.getOfferId().equals(offerId)).findFirst();
    }

    private void addOpenOffer(OpenOffer openOffer) {
        log.info("Adding open offer {}", openOffer.getId());
        synchronized (openOffers.getList()) {
            openOffers.add(openOffer);
            if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() != null) {
                xmrConnectionService.getKeyImagePoller().addKeyImages(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages(), OPEN_OFFER_GROUP_KEY_IMAGE_ID);
            }
        }
    }

    private void removeOpenOffer(OpenOffer openOffer) {
        log.info("Removing open offer {}", openOffer.getId());
        synchronized (openOffers.getList()) {
            openOffers.remove(openOffer);
            if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() != null) {
                xmrConnectionService.getKeyImagePoller().removeKeyImages(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages(), OPEN_OFFER_GROUP_KEY_IMAGE_ID);
            }
        }

        // cancel place offer protocol
        ThreadUtils.execute(() -> {
            synchronized (processOffersLock) {
                synchronized (placeOfferProtocols) {
                    PlaceOfferProtocol protocol = placeOfferProtocols.remove(openOffer.getId());
                    if (protocol != null) protocol.cancelOffer();
                }
            }
        }, THREAD_ID);
    }

    private void cancelOpenOffersOnSpent(String keyImage) {
        synchronized (openOffers.getList()) {
            for (OpenOffer openOffer : openOffers.getList()) {
                if (openOffer.getState() != OpenOffer.State.RESERVED && openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() != null && openOffer.getOffer().getOfferPayload().getReserveTxKeyImages().contains(keyImage)) {
                    log.warn("Canceling open offer because reserved funds have been spent unexpectedly, offerId={}, state={}", openOffer.getId(), openOffer.getState());
                    cancelOpenOffer(openOffer, null, null);
                }
            }
        }
    }

    private void addSignedOffer(SignedOffer signedOffer) {
        log.info("Adding SignedOffer for offer {}", signedOffer.getOfferId());
        synchronized (signedOffers.getList()) {

            // remove signed offers with common key images
            for (String keyImage : signedOffer.getReserveTxKeyImages()) {
                removeSignedOffers(keyImage);
            }

            // add new signed offer
            signedOffers.add(signedOffer);
            xmrConnectionService.getKeyImagePoller().addKeyImages(signedOffer.getReserveTxKeyImages(), SIGNED_OFFER_KEY_IMAGE_GROUP_ID);
        }
    }

    private void removeSignedOffer(SignedOffer signedOffer) {
        log.info("Removing SignedOffer for offer {}", signedOffer.getOfferId());
        synchronized (signedOffers.getList()) {
            signedOffers.remove(signedOffer);
        }
        xmrConnectionService.getKeyImagePoller().removeKeyImages(signedOffer.getReserveTxKeyImages(), SIGNED_OFFER_KEY_IMAGE_GROUP_ID);
    }

    private void removeSignedOffers(String keyImage) {
        synchronized (signedOffers.getList()) {
            for (SignedOffer signedOffer : getSignedOffers()) {
                if (signedOffer.getReserveTxKeyImages().contains(keyImage)) {
                    removeSignedOffer(signedOffer);
                }
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Place offer helpers
    ///////////////////////////////////////////////////////////////////////////////////////////
    private void processOffers(boolean skipOffersWithTooManyAttempts,
                                       TransactionResultHandler resultHandler, // TODO (woodser): transaction not needed with result handler
                                       ErrorMessageHandler errorMessageHandler) {
        ThreadUtils.execute(() -> {
            List<String> errorMessages = new ArrayList<String>();
            synchronized (processOffersLock) {
                List<OpenOffer> openOffers = getOpenOffers();
                for (OpenOffer offer : openOffers) {
                    if (skipOffersWithTooManyAttempts && offer.getNumProcessingAttempts() > NUM_ATTEMPTS_THRESHOLD) continue; // skip offers with too many attempts
                    CountDownLatch latch = new CountDownLatch(1);
                    processOffer(openOffers, offer, (transaction) -> {
                        latch.countDown();
                    }, errorMessage -> {
                        errorMessages.add(errorMessage);
                        latch.countDown();
                    });
                    HavenoUtils.awaitLatch(latch);
                }
            }
            requestPersistence();
            if (errorMessages.isEmpty()) {
                if (resultHandler != null) resultHandler.handleResult(null);
            } else {
                if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage(errorMessages.toString());
            }
        }, THREAD_ID);
    }

    private void processOffer(List<OpenOffer> openOffers, OpenOffer openOffer, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        
        // skip if already processing
        if (openOffer.isProcessing()) {
            resultHandler.handleResult(null);
            return;
        }

        // process offer
        openOffer.setProcessing(true);
        doProcessOffer(openOffers, openOffer, (transaction) -> {
            openOffer.setProcessing(false);
            resultHandler.handleResult(transaction);
        }, (errorMsg) -> {
            openOffer.setProcessing(false);
            openOffer.setNumProcessingAttempts(openOffer.getNumProcessingAttempts() + 1);
            openOffer.getOffer().setErrorMessage(errorMsg);
            if (!openOffer.isCanceled()) {
                errorMsg = "Error processing offer, offerId=" + openOffer.getId() + ", attempt=" + openOffer.getNumProcessingAttempts() + ": " + errorMsg;
                openOffer.getOffer().setErrorMessage(errorMsg);
                
                // cancel offer if invalid
                if (openOffer.getOffer().getState() == Offer.State.INVALID) {
                    log.warn("Canceling offer because it's invalid: {}", openOffer.getId());
                    doCancelOffer(openOffer);
                }
            }
            errorMessageHandler.handleErrorMessage(errorMsg);
        });
    }

    private void doProcessOffer(List<OpenOffer> openOffers, OpenOffer openOffer, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        new Thread(() -> {
            try {

                // done processing if canceled or wallet not initialized
                if (openOffer.isCanceled() || xmrWalletService.getWallet() == null) {
                    resultHandler.handleResult(null);
                    return;
                }

                // validate offer
                try {
                    ValidateOffer.validateOffer(openOffer.getOffer(), accountAgeWitnessService, user);
                } catch (Exception e) {
                    openOffer.getOffer().setState(Offer.State.INVALID);
                    errorMessageHandler.handleErrorMessage("Failed to validate offer: " + e.getMessage());
                    return;
                }

                // handle pending offer
                if (openOffer.isPending()) {

                    // only process the first offer of a pending clone group
                    if (openOffer.getGroupId() != null) {
                        List<OpenOffer> openOfferClones = getOpenOfferGroup(openOffers, openOffer.getGroupId());
                        if (openOfferClones.size() > 1 && !openOfferClones.get(0).getId().equals(openOffer.getId()) && openOfferClones.get(0).isPending()) {
                            resultHandler.handleResult(null);
                            return;
                        }
                    }
                } else {

                    // validate or reset non-pending state
                    try {
                        validateSignedState(openOffer);
                        resultHandler.handleResult(null); // done processing if non-pending state is valid
                        return;
                    } catch (Exception e) {
                        log.info("Open offer {} has invalid signature, which can happen after editing or cloning offer, validationMsg={}", openOffer.getId(), e.getMessage());

                        // reset arbitrator signature
                        openOffer.getOffer().getOfferPayload().setArbitratorSignature(null);
                        openOffer.getOffer().getOfferPayload().setArbitratorSigner(null);
                        if (openOffer.isAvailable()) openOffer.setState(OpenOffer.State.PENDING);
                    }
                }

                // sign and post offer if already funded
                if (openOffer.getReserveTxHash() != null) {
                    signAndPostOffer(openOffer, false, resultHandler, errorMessageHandler);
                    return;
                }

                // cancel offer if scheduled txs unavailable
                if (openOffer.getScheduledTxHashes() != null) {
                    boolean scheduledTxsAvailable = true;
                    for (MoneroTxWallet tx : xmrWalletService.getTxs(openOffer.getScheduledTxHashes())) {
                        if (!tx.isLocked() && !hasSpendableAmount(tx)) {
                            scheduledTxsAvailable = false;
                            break;
                        }
                    }
                    if (!scheduledTxsAvailable) {
                        log.warn("Canceling offer {} because scheduled txs are no longer available", openOffer.getId());
                        doCancelOffer(openOffer);
                        resultHandler.handleResult(null);
                        return;
                    }
                }

                // get amount needed to reserve offer
                BigInteger amountNeeded = openOffer.getOffer().getAmountNeeded();

                // handle split output offer
                if (openOffer.isReserveExactAmount()) {

                    // find tx with exact input amount
                    MoneroTxWallet splitOutputTx = getSplitOutputFundingTx(openOffers, openOffer);
                    if (splitOutputTx != null && openOffer.getSplitOutputTxHash() == null) {
                        setSplitOutputTx(openOffer, splitOutputTx);
                    }

                    // if wallet has exact available balance, try to sign and post directly
                    if (xmrWalletService.getAvailableBalance().equals(amountNeeded)) {
                        signAndPostOffer(openOffer, true, resultHandler, (errorMessage) -> {
                            splitOrSchedule(splitOutputTx, openOffers, openOffer, amountNeeded, resultHandler, errorMessageHandler);
                        });
                        return;
                    } else {
                        splitOrSchedule(splitOutputTx, openOffers, openOffer, amountNeeded, resultHandler, errorMessageHandler);
                    }
                } else {

                    // sign and post offer if enough funds
                    boolean hasSufficientBalance = xmrWalletService.getAvailableBalance().compareTo(amountNeeded) >= 0;
                    if (hasSufficientBalance) {
                        signAndPostOffer(openOffer, true, resultHandler, errorMessageHandler);
                        return;
                    } else if (openOffer.getScheduledTxHashes() == null) {
                        scheduleWithEarliestTxs(openOffers, openOffer);
                    }

                    resultHandler.handleResult(null);
                    return;
                }
            } catch (Exception e) {
                if (!openOffer.isCanceled()) log.error("Error processing offer: {}\n", e.getMessage(), e);
                errorMessageHandler.handleErrorMessage(e.getMessage());
            }
        }).start();
    }

    private void validateSignedState(OpenOffer openOffer) {
        Arbitrator arbitrator = user.getAcceptedArbitratorByAddress(openOffer.getOffer().getOfferPayload().getArbitratorSigner());
        if (openOffer.getOffer().getOfferPayload().getArbitratorSigner() == null) {
            throw new IllegalArgumentException("Offer " + openOffer.getId() + " has no arbitrator signer");
        } else if (openOffer.getOffer().getOfferPayload().getArbitratorSignature() == null) {
            throw new IllegalArgumentException("Offer " + openOffer.getId() + " has no arbitrator signature");
        } else if (arbitrator == null) {
            throw new IllegalArgumentException("Offer " + openOffer.getId() + " signed by unregistered arbitrator");
        } else if (!HavenoUtils.isArbitratorSignatureValid(openOffer.getOffer().getOfferPayload(), arbitrator)) {
            throw new IllegalArgumentException("Offer " + openOffer.getId() + " has invalid arbitrator signature");
        } else if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() == null || openOffer.getOffer().getOfferPayload().getReserveTxKeyImages().isEmpty() || openOffer.getReserveTxHash() == null || openOffer.getReserveTxHash().isEmpty()) {
            throw new IllegalArgumentException("Offer " + openOffer.getId() + " is missing reserve tx hash or key images");
        }
    }

    private MoneroTxWallet getSplitOutputFundingTx(List<OpenOffer> openOffers, OpenOffer openOffer) {
        XmrAddressEntry addressEntry = xmrWalletService.getOrCreateAddressEntry(openOffer.getId(), XmrAddressEntry.Context.OFFER_FUNDING);
        return getSplitOutputFundingTx(openOffers, openOffer, openOffer.getOffer().getAmountNeeded(), addressEntry.getSubaddressIndex());
    }

    private MoneroTxWallet getSplitOutputFundingTx(List<OpenOffer> openOffers, OpenOffer openOffer, BigInteger reserveAmount, Integer preferredSubaddressIndex) {

        // return split output tx if already assigned
        if (openOffer != null && openOffer.getSplitOutputTxHash() != null) {

            // get recorded split output tx
            MoneroTxWallet splitOutputTx = xmrWalletService.getTx(openOffer.getSplitOutputTxHash());

            // check if split output tx is available for offer
            if (splitOutputTx != null) {
                if (splitOutputTx.isLocked()) return splitOutputTx;
                else {
                    boolean isAvailable = true;
                    for (MoneroOutputWallet output : splitOutputTx.getOutputsWallet()) {
                        if (output.isSpent() || output.isFrozen()) {
                            isAvailable = false;
                            break;
                        }
                    }
                    if (isAvailable || isReservedByOffer(openOffer, splitOutputTx)) return splitOutputTx;
                    else log.warn("Split output tx {} is no longer available for offer {}", openOffer.getSplitOutputTxHash(), openOffer.getId());
                }
            } else {
                log.warn("Split output tx {} no longer exists for offer {}", openOffer.getSplitOutputTxHash(), openOffer.getId());
            }
        }

        // get split output tx to offer's preferred subaddress
        if (preferredSubaddressIndex != null) {
            List<MoneroTxWallet> fundingTxs = getSplitOutputFundingTxs(reserveAmount, preferredSubaddressIndex);
            MoneroTxWallet earliestUnscheduledTx = getEarliestUnscheduledTx(openOffers, openOffer, fundingTxs);
            if (earliestUnscheduledTx != null) return earliestUnscheduledTx;
        }

        // get split output tx to any subaddress
        List<MoneroTxWallet> fundingTxs = getSplitOutputFundingTxs(reserveAmount, null);
        return getEarliestUnscheduledTx(openOffers, openOffer, fundingTxs);
    }

    private boolean isReservedByOffer(OpenOffer openOffer, MoneroTxWallet tx) {
        if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() == null) return false;
        Set<String> offerKeyImages = new HashSet<String>(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages());
        for (MoneroOutputWallet output : tx.getOutputsWallet()) {
            if (offerKeyImages.contains(output.getKeyImage().getHex())) return true;
        }
        return false;
    }

    private List<MoneroTxWallet> getSplitOutputFundingTxs(BigInteger reserveAmount, Integer preferredSubaddressIndex) {
        List<MoneroTxWallet> splitOutputTxs = xmrWalletService.getTxs(new MoneroTxQuery().setIsFailed(false)); // TODO: not using setIsIncoming(true) because split output txs sent to self have false; fix in monero-java?
        Set<MoneroTxWallet> removeTxs = new HashSet<MoneroTxWallet>();
        for (MoneroTxWallet tx : splitOutputTxs) {
            if (tx.getOutputs() != null) { // outputs not available until first confirmation
                for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                    if (output.isSpent() || output.isFrozen()) removeTxs.add(tx);
                }
            }
            if (!hasExactOutput(tx, reserveAmount, preferredSubaddressIndex)) removeTxs.add(tx);
        }
        splitOutputTxs.removeAll(removeTxs);
        return splitOutputTxs;
    }

    private boolean hasExactOutput(MoneroTxWallet tx, BigInteger amount, Integer preferredSubaddressIndex) {
        boolean hasExactOutput = (tx.getOutputsWallet(new MoneroOutputQuery()
                .setAccountIndex(0)
                .setSubaddressIndex(preferredSubaddressIndex)
                .setAmount(amount)).size() > 0);
        if (hasExactOutput) return true;
        boolean hasExactTransfer = (tx.getTransfers(new MoneroTransferQuery()
                .setAccountIndex(0)
                .setSubaddressIndex(preferredSubaddressIndex)
                .setIsIncoming(true)
                .setAmount(amount)).size() > 0);
        return hasExactTransfer;
    }

    private MoneroTxWallet getEarliestUnscheduledTx(List<OpenOffer> openOffers, OpenOffer excludeOpenOffer, List<MoneroTxWallet> txs) {
        MoneroTxWallet earliestUnscheduledTx = null;
        for (MoneroTxWallet tx : txs) {
            if (isTxScheduledByOtherOffer(openOffers, excludeOpenOffer, tx.getHash())) continue;
            if (earliestUnscheduledTx == null || (earliestUnscheduledTx.getNumConfirmations() < tx.getNumConfirmations())) earliestUnscheduledTx = tx;
        }
        return earliestUnscheduledTx;
    }

    // if split tx not found and cannot reserve exact amount directly, create tx to split or reserve exact output
    private void splitOrSchedule(MoneroTxWallet splitOutputTx, List<OpenOffer> openOffers, OpenOffer openOffer, BigInteger amountNeeded, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (splitOutputTx == null) {
            if (openOffer.getSplitOutputTxHash() != null) {
                log.warn("Split output tx unexpectedly unavailable for offer, offerId={}, split output tx={}", openOffer.getId(), openOffer.getSplitOutputTxHash());
                setSplitOutputTx(openOffer, null);
            }
            try {
                splitOrScheduleAux(openOffers, openOffer, amountNeeded);
                resultHandler.handleResult(null);
                return;
            } catch (Exception e) {
                log.warn("Unable to split or schedule funds for offer {}: {}", openOffer.getId(), e.getMessage());
                openOffer.getOffer().setState(Offer.State.INVALID);
                errorMessageHandler.handleErrorMessage(e.getMessage());
                return;
            }
        } else if (!splitOutputTx.isLocked()) {

            // otherwise sign and post offer if split output available
            signAndPostOffer(openOffer, true, resultHandler, errorMessageHandler);
            return;
        } else {
            resultHandler.handleResult(null);
            return;
        }
    }

    private void splitOrScheduleAux(List<OpenOffer> openOffers, OpenOffer openOffer, BigInteger offerReserveAmount) {

        // handle sufficient available balance to split output
        boolean sufficientAvailableBalance = xmrWalletService.getAvailableBalance().compareTo(offerReserveAmount) >= 0;
        if (sufficientAvailableBalance && openOffer.getSplitOutputTxHash() == null) {
            log.info("Splitting and scheduling outputs for offer {}", openOffer.getShortId());
            splitAndSchedule(openOffer);
        } else if (openOffer.getScheduledTxHashes() == null) {
            scheduleWithEarliestTxs(openOffers, openOffer);
        }
    }

    private MoneroTxWallet splitAndSchedule(OpenOffer openOffer) {
        BigInteger reserveAmount = openOffer.getOffer().getAmountNeeded();
        xmrWalletService.swapAddressEntryToAvailable(openOffer.getId(), XmrAddressEntry.Context.OFFER_FUNDING); // change funding subaddress in case funded with unsuitable output(s)
        MoneroTxWallet splitOutputTx = null;
        synchronized (HavenoUtils.xmrWalletService.getWalletLock()) {
            XmrAddressEntry entry = xmrWalletService.getOrCreateAddressEntry(openOffer.getId(), XmrAddressEntry.Context.OFFER_FUNDING);
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                long startTime = System.currentTimeMillis();
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                    try {
                        log.info("Creating split output tx to fund offer {} at subaddress {}", openOffer.getShortId(), entry.getSubaddressIndex());
                        splitOutputTx = xmrWalletService.createTx(new MoneroTxConfig()
                                .setAccountIndex(0)
                                .setAddress(entry.getAddressString())
                                .setAmount(reserveAmount)
                                .setRelay(true)
                                .setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY));
                        break;
                    } catch (Exception e) {
                        if (e.getMessage().contains("not enough")) throw e; // do not retry if not enough funds
                        log.warn("Error creating split output tx to fund offer, offerId={}, subaddress={}, attempt={}/{}, error={}", openOffer.getShortId(), entry.getSubaddressIndex(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                        xmrWalletService.handleWalletError(e, sourceConnection, i + 1);
                        if (stopped || i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    }
                }
                log.info("Done creating split output tx to fund offer {} in {} ms", openOffer.getId(), System.currentTimeMillis() - startTime);
            }
        }

        // set split tx
        setSplitOutputTx(openOffer, splitOutputTx);
        return splitOutputTx;
    }

    private void setSplitOutputTx(OpenOffer openOffer, MoneroTxWallet splitOutputTx) {
        openOffer.setSplitOutputTxHash(splitOutputTx == null ? null : splitOutputTx.getHash());
        openOffer.setSplitOutputTxFee(splitOutputTx == null ? 0l : splitOutputTx.getFee().longValueExact());
        openOffer.setScheduledTxHashes(splitOutputTx == null ? null : Arrays.asList(splitOutputTx.getHash()));
        openOffer.setScheduledAmount(splitOutputTx == null ? null : openOffer.getOffer().getAmountNeeded().toString());
        if (!openOffer.isCanceled()) openOffer.setState(OpenOffer.State.PENDING);
    }

    private void scheduleWithEarliestTxs(List<OpenOffer> openOffers, OpenOffer openOffer) {

        // get earliest available or pending txs with sufficient spendable amount
        BigInteger offerReserveAmount = openOffer.getOffer().getAmountNeeded();
        BigInteger scheduledAmount = BigInteger.ZERO;
        Set<MoneroTxWallet> scheduledTxs = new HashSet<MoneroTxWallet>();
        for (MoneroTxWallet tx : xmrWalletService.getTxs()) {

            // get unscheduled spendable amount
            BigInteger spendableAmount = getUnscheduledSpendableAmount(tx, openOffers);

            // skip if no spendable amount
            if (spendableAmount.equals(BigInteger.ZERO)) continue;

            // schedule tx
            scheduledAmount = scheduledAmount.add(spendableAmount);
            scheduledTxs.add(tx);

            // break if sufficient funds
            if (scheduledAmount.compareTo(offerReserveAmount) >= 0) break;
        }
        if (scheduledAmount.compareTo(offerReserveAmount) < 0) throw new RuntimeException("Not enough funds to create offer");

        // schedule txs
        openOffer.setScheduledTxHashes(scheduledTxs.stream().map(tx -> tx.getHash()).collect(Collectors.toList()));
        openOffer.setScheduledAmount(scheduledAmount.toString());
        openOffer.setState(OpenOffer.State.PENDING);
    }

    private BigInteger getUnscheduledSpendableAmount(MoneroTxWallet tx, List<OpenOffer> openOffers) {
        if (isScheduledWithUnknownAmount(tx, openOffers)) return BigInteger.ZERO;
        return getSpendableAmount(tx).subtract(getSplitAmount(tx, openOffers)).max(BigInteger.ZERO);
    }

    private boolean isScheduledWithUnknownAmount(MoneroTxWallet tx, List<OpenOffer> openOffers) {
        for (OpenOffer openOffer : openOffers) {
            if (openOffer.getScheduledTxHashes() == null) continue;
            if (openOffer.getScheduledTxHashes().contains(tx.getHash()) && !tx.getHash().equals(openOffer.getSplitOutputTxHash())) {
                return true;
            }
        }
        return false;
    }

    private BigInteger getSplitAmount(MoneroTxWallet tx, List<OpenOffer> openOffers) {
        for (OpenOffer openOffer : openOffers) {
            if (openOffer.getSplitOutputTxHash() == null) continue;
            if (!openOffer.getSplitOutputTxHash().equals(tx.getHash())) continue;
            return openOffer.getOffer().getAmountNeeded();
        }
        return BigInteger.ZERO;
    }

    private BigInteger getSpendableAmount(MoneroTxWallet tx) {

        // compute spendable amount from outputs if confirmed
        if (tx.isConfirmed()) {
            BigInteger spendableAmount = BigInteger.ZERO;
            if (tx.getOutputsWallet() != null) {
                for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                    if (!output.isSpent() && !output.isFrozen() && output.getAccountIndex() == 0) {
                        spendableAmount = spendableAmount.add(output.getAmount());
                    }
                }
            }
            return spendableAmount;
        }

        // funds sent to self always show 0 incoming amount, so compute from destinations manually
        // TODO: this excludes change output, so change is missing from spendable amount until confirmed
        BigInteger sentToSelfAmount = xmrWalletService.getAmountSentToSelf(tx);
        if (sentToSelfAmount.compareTo(BigInteger.ZERO) > 0) return sentToSelfAmount;

        // if not confirmed and not sent to self, return incoming amount
        return tx.getIncomingAmount() == null ? BigInteger.ZERO : tx.getIncomingAmount();
    }

    private boolean hasSpendableAmount(MoneroTxWallet tx) {
        return getSpendableAmount(tx).compareTo(BigInteger.ZERO) > 0;
    }

    private boolean isTxScheduledByOtherOffer(List<OpenOffer> openOffers, OpenOffer openOffer, String txHash) {
        for (OpenOffer otherOffer : openOffers) {
            if (otherOffer == openOffer) continue;
            if (otherOffer.getState() != OpenOffer.State.PENDING) continue;
            if (txHash.equals(otherOffer.getSplitOutputTxHash())) return true;
            if (otherOffer.getScheduledTxHashes() != null) {
                for (String scheduledTxHash : otherOffer.getScheduledTxHashes()) {
                    if (txHash.equals(scheduledTxHash)) return true;
                }
            }
        }
        return false;
    }

    private void signAndPostOffer(OpenOffer openOffer,
                                  boolean useSavingsWallet, // TODO: remove this?
                                  TransactionResultHandler resultHandler,
                                  ErrorMessageHandler errorMessageHandler) {
        log.info("Signing and posting offer " + openOffer.getId());

        // create model
        PlaceOfferModel model = new PlaceOfferModel(openOffer,
                openOffer.getOffer().getAmountNeeded(),
                useSavingsWallet,
                p2PService,
                btcWalletService,
                xmrWalletService,
                tradeWalletService,
                offerBookService,
                arbitratorManager,
                mediatorManager,
                tradeStatisticsManager,
                user,
                keyRing,
                filterManager,
                accountAgeWitnessService,
                this);

        // create protocol
        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(model,
                transaction -> {

                    // set offer state
                    openOffer.setScheduledTxHashes(null);
                    openOffer.setScheduledAmount(null);
                    requestPersistence();

                    if (!stopped) {
                        startPeriodicRepublishOffersTimer();
                        startPeriodicRefreshOffersTimer();
                    } else {
                        log.debug("We have stopped already. We ignore that placeOfferProtocol.placeOffer.onResult call.");
                    }
                    resultHandler.handleResult(transaction);
                },
                errorMessageHandler);

        // run protocol
        synchronized (placeOfferProtocols) {
            placeOfferProtocols.put(openOffer.getOffer().getId(), placeOfferProtocol);
        }
        placeOfferProtocol.placeOffer();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Arbitrator Signs Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleSignOfferRequest(SignOfferRequest request, NodeAddress peer) {
        log.info("Received SignOfferRequest from {} with offerId {} and uid {}",
                peer, request.getOfferId(), request.getUid());

        boolean result = false;
        String errorMessage = null;
        try {

            // verify this node is an arbitrator
            Arbitrator thisArbitrator = user.getRegisteredArbitrator();
            NodeAddress thisAddress = p2PService.getNetworkNode().getNodeAddress();
            if (thisArbitrator == null || !thisArbitrator.getNodeAddress().equals(thisAddress)) {
              errorMessage = "Cannot sign offer because we are not a registered arbitrator";
              log.warn(errorMessage);
              sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
              return;
            }

            // verify arbitrator is signer of offer payload
            if (!thisAddress.equals(request.getOfferPayload().getArbitratorSigner())) {
                errorMessage = "Cannot sign offer because offer payload is for a different arbitrator";
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // private offers must have challenge hash
            Offer offer = new Offer(request.getOfferPayload());
            if (offer.isPrivateOffer() && (offer.getChallengeHash() == null || offer.getChallengeHash().length() == 0)) {
                errorMessage = "Private offer must have challenge hash for offer " + request.offerId;
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // verify max length of extra info
            if (offer.getOfferPayload().getExtraInfo() != null && offer.getOfferPayload().getExtraInfo().length() > Restrictions.getMaxExtraInfoLength()) {
                errorMessage = "Extra info is too long for offer " + request.offerId + ". Max length is " + Restrictions.getMaxExtraInfoLength() + " but got " + offer.getOfferPayload().getExtraInfo().length();
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // verify the trade protocol version
            if (request.getOfferPayload().getProtocolVersion() != Version.TRADE_PROTOCOL_VERSION) {
                errorMessage = "Unsupported protocol version: " + request.getOfferPayload().getProtocolVersion();
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // verify the min version number
            if (filterManager.getDisableTradeBelowVersion() != null) {
                if (Version.compare(request.getOfferPayload().getVersionNr(), filterManager.getDisableTradeBelowVersion()) < 0) {
                    errorMessage = "Offer version number is too low: " + request.getOfferPayload().getVersionNr() + " < " + filterManager.getDisableTradeBelowVersion();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }
            }

            // verify market price margin
            double marketPriceMarginPct = request.getOfferPayload().getMarketPriceMarginPct();
            if (marketPriceMarginPct <= -1 || marketPriceMarginPct >= 1) {
                errorMessage = "Market price margin must be greater than -100% and less than 100% but was " + (marketPriceMarginPct * 100) + "%";
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // verify maker and taker fees
            boolean hasBuyerAsTakerWithoutDeposit = offer.getDirection() == OfferDirection.SELL && offer.isPrivateOffer() && offer.getChallengeHash() != null && offer.getChallengeHash().length() > 0 && offer.getTakerFeePct() == 0;
            if (hasBuyerAsTakerWithoutDeposit) {

                // verify maker's trade fee
                double makerFeePct = HavenoUtils.getMakerFeePct(request.getOfferPayload().getCounterCurrencyCode(), hasBuyerAsTakerWithoutDeposit);
                if (offer.getMakerFeePct() != makerFeePct) {
                    errorMessage = "Wrong maker fee for offer " + request.offerId + ". Expected " + makerFeePct + " but got " + offer.getMakerFeePct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify taker's trade fee
                if (offer.getTakerFeePct() != 0) {
                    errorMessage = "Wrong taker fee for offer " + request.offerId + ". Expected 0 but got " + offer.getTakerFeePct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify maker security deposit
                if (offer.getSellerSecurityDepositPct() != Restrictions.getMinSecurityDepositPct()) {
                    errorMessage = "Wrong seller security deposit for offer " + request.offerId + ". Expected " + Restrictions.getMinSecurityDepositPct() + " but got " + offer.getSellerSecurityDepositPct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify taker's security deposit
                if (offer.getBuyerSecurityDepositPct() != 0) {
                    errorMessage = "Wrong buyer security deposit for offer " + request.offerId + ". Expected 0 but got " + offer.getBuyerSecurityDepositPct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }
            } else {

                // verify public offer (remove to generally allow private offers)
                if (offer.isPrivateOffer() || offer.getChallengeHash() != null) {
                    errorMessage = "Private offer " + request.offerId + " is not valid. It must have direction SELL, taker fee of 0, and a challenge hash.";
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify maker's trade fee
                double makerFeePct = HavenoUtils.getMakerFeePct(request.getOfferPayload().getCounterCurrencyCode(), hasBuyerAsTakerWithoutDeposit);
                if (offer.getMakerFeePct() != makerFeePct) {
                    errorMessage = "Wrong maker fee for offer " + request.offerId + ". Expected " + makerFeePct + " but got " + offer.getMakerFeePct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify taker's trade fee
                double takerFeePct = HavenoUtils.getTakerFeePct(request.getOfferPayload().getCounterCurrencyCode(), hasBuyerAsTakerWithoutDeposit);
                if (offer.getTakerFeePct() != takerFeePct) {
                    errorMessage = "Wrong taker fee for offer " + request.offerId + ". Expected " + takerFeePct + " but got " + offer.getTakerFeePct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify seller's security deposit
                if (offer.getSellerSecurityDepositPct() < Restrictions.getMinSecurityDepositPct()) {
                    errorMessage = "Insufficient seller security deposit for offer " + request.offerId + ". Expected at least " + Restrictions.getMinSecurityDepositPct() + " but got " + offer.getSellerSecurityDepositPct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // verify buyer's security deposit
                if (offer.getBuyerSecurityDepositPct() < Restrictions.getMinSecurityDepositPct()) {
                    errorMessage = "Insufficient buyer security deposit for offer " + request.offerId + ". Expected at least " + Restrictions.getMinSecurityDepositPct() + " but got " + offer.getBuyerSecurityDepositPct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }

                // security deposits must be equal
                if (offer.getBuyerSecurityDepositPct() != offer.getSellerSecurityDepositPct()) {
                    errorMessage = "Buyer and seller security deposits are not equal for offer " + request.offerId + ": " + offer.getSellerSecurityDepositPct() + " vs " + offer.getBuyerSecurityDepositPct();
                    log.warn(errorMessage);
                    sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                    return;
                }
            }

            // verify penalty fee
            if (offer.getPenaltyFeePct() != HavenoUtils.PENALTY_FEE_PCT) {
                errorMessage = "Wrong penalty fee percent for offer " + request.offerId + ". Expected " + HavenoUtils.PENALTY_FEE_PCT + " but got " + offer.getPenaltyFeePct();
                log.warn(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }

            // verify maker's reserve tx (double spend, trade fee, trade amount, mining fee)
            double makerFeePct = HavenoUtils.getMakerFeePct(request.getOfferPayload().getCounterCurrencyCode(), hasBuyerAsTakerWithoutDeposit);
            BigInteger maxTradeFee = HavenoUtils.multiply(offer.getAmount(), makerFeePct);
            BigInteger sendTradeAmount =  offer.getDirection() == OfferDirection.BUY ? BigInteger.ZERO : offer.getAmount();
            BigInteger securityDeposit = offer.getDirection() == OfferDirection.BUY ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit();
            BigInteger penaltyFee = HavenoUtils.multiply(securityDeposit, HavenoUtils.PENALTY_FEE_PCT);
            MoneroTx verifiedTx = xmrWalletService.verifyReserveTx(
                    offer.getId(),
                    penaltyFee,
                    maxTradeFee,
                    sendTradeAmount,
                    securityDeposit,
                    request.getPayoutAddress(),
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKey(),
                    request.getReserveTxKeyImages());

            // arbitrator signs offer to certify they have valid reserve tx
            byte[] signature = HavenoUtils.signOffer(request.getOfferPayload(), keyRing);
            OfferPayload signedOfferPayload = request.getOfferPayload();
            signedOfferPayload.setArbitratorSignature(signature);

            // create record of signed offer
            SignedOffer signedOffer = new SignedOffer(
                    System.currentTimeMillis(),
                    signedOfferPayload.getPubKeyRing().hashCode(), // trader id
                    signedOfferPayload.getId(),
                    offer.getAmount().longValueExact(),
                    penaltyFee.longValueExact(),
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKeyImages(),
                    verifiedTx.getFee().longValueExact(),
                    signature); // TODO (woodser): no need for signature to be part of SignedOffer?
            UserThread.execute(() -> addSignedOffer(signedOffer));
            requestPersistence();

            // send response with signature
            SignOfferResponse response = new SignOfferResponse(request.getOfferId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    signedOfferPayload);
            p2PService.sendEncryptedDirectMessage(peer,
                    request.getPubKeyRing(),
                    response,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}",
                                    response.getClass().getSimpleName(),
                                    response.getOfferId(),
                                    response.getUid());
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}",
                                    response.getClass().getSimpleName(),
                                    response.getUid(),
                                    peer,
                                    errorMessage);
                        }
                    });
            result = true;
        } catch (Exception e) {
            errorMessage = "Exception at handleSignOfferRequest " + e.getMessage();
            log.error(errorMessage + "\n", e);
        } finally {
            if (result == false && errorMessage == null) {
                log.warn("Arbitrator is NACKing SignOfferRequest for unknown reason with offerId={}. That should never happen", request.getOfferId());
                log.warn("Printing stacktrace:");
                Thread.dumpStack();
            }
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), result, errorMessage);
        }
    }

    private void handleSignOfferResponse(SignOfferResponse response, NodeAddress peer) {
        log.info("Received SignOfferResponse from {} with offerId {} and uid {}",
                peer, response.getOfferId(), response.getUid());

        // get previously created protocol
        PlaceOfferProtocol protocol;
        synchronized (placeOfferProtocols) {
            protocol = placeOfferProtocols.get(response.getOfferId());
            if (protocol == null) {
                log.warn("No place offer protocol created for offer " + response.getOfferId());
                return;
            }
        }

        // handle response
        protocol.handleSignOfferResponse(response, peer);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // OfferPayload Availability
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleOfferAvailabilityRequest(OfferAvailabilityRequest request, NodeAddress peer) {
        log.info("Received OfferAvailabilityRequest from {} with offerId {} and uid {}",
                peer, request.getOfferId(), request.getUid());

        boolean result = false;
        String errorMessage = null;

        if (!p2PService.isBootstrapped()) {
            errorMessage = "We got a handleOfferAvailabilityRequest but we have not bootstrapped yet.";
            log.info(errorMessage);
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
            return;
        }

        // Don't allow trade start if Monero node is not fully synced
        if (!xmrConnectionService.isSyncedWithinTolerance()) {
            errorMessage = "We got a handleOfferAvailabilityRequest but our chain is not synced.";
            log.info(errorMessage);
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
            return;
        }

        // Don't allow trade start if not connected to Monero node
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) {
            errorMessage = "We got a handleOfferAvailabilityRequest but we are not connected to a Monero node.";
            log.info(errorMessage);
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
            return;
        }

        if (stopped) {
            errorMessage = "We have stopped already. We ignore that handleOfferAvailabilityRequest call.";
            log.debug(errorMessage);
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
            return;
        }

        try {
            Validator.nonEmptyStringOf(request.offerId);
            checkNotNull(request.getPubKeyRing());
        } catch (Throwable t) {
            errorMessage = "Message validation failed. Error=" + t.toString() + ", Message=" + request.toString();
            log.warn(errorMessage);
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
            return;
        }

        try {
            Optional<OpenOffer> openOfferOptional = getOpenOffer(request.offerId);
            AvailabilityResult availabilityResult;
            byte[] makerSignature = null;
            if (openOfferOptional.isPresent()) {
                OpenOffer openOffer = openOfferOptional.get();
                if (!apiUserDeniedByOffer(request)) {
                    if (!takerDeniedByMaker(request)) {
                        if (openOffer.getState() == OpenOffer.State.AVAILABLE) {
                            Offer offer = openOffer.getOffer();
                            if (preferences.getIgnoreTradersList().stream().noneMatch(fullAddress -> fullAddress.equals(peer.getFullAddress()))) {

                                // maker signs taker's request
                                String tradeRequestAsJson = JsonUtil.objectToJson(request.getTradeRequest());
                                makerSignature = HavenoUtils.sign(keyRing, tradeRequestAsJson);

                                try {
                                    // Check also tradePrice to avoid failures after taker fee is paid caused by a too big difference
                                    // in trade price between the peers. Also here poor connectivity might cause market price API connection
                                    // losses and therefore an outdated market price.
                                    offer.verifyTradePrice(request.getTakersTradePrice());
                                    availabilityResult = AvailabilityResult.AVAILABLE;
                                } catch (TradePriceOutOfToleranceException e) {
                                    log.warn("Trade price check failed because takers price is outside out tolerance.");
                                    availabilityResult = AvailabilityResult.PRICE_OUT_OF_TOLERANCE;
                                } catch (MarketPriceNotAvailableException e) {
                                    log.warn(e.getMessage());
                                    availabilityResult = AvailabilityResult.MARKET_PRICE_NOT_AVAILABLE;
                                } catch (Throwable e) {
                                    log.warn("Trade price check failed. " + e.getMessage());
                                    if (coreContext.isApiUser())
                                        // Give api user something more than 'unknown_failure'.
                                        availabilityResult = AvailabilityResult.PRICE_CHECK_FAILED;
                                    else
                                        availabilityResult = AvailabilityResult.UNKNOWN_FAILURE;
                                }
                            } else {
                                availabilityResult = AvailabilityResult.USER_IGNORED;
                            }
                        } else {
                            availabilityResult = AvailabilityResult.OFFER_TAKEN;
                        }
                    } else {
                        availabilityResult = AvailabilityResult.MAKER_DENIED_TAKER;
                    }
                } else {
                    availabilityResult = AvailabilityResult.MAKER_DENIED_API_USER;
                }
            } else {
                log.warn("handleOfferAvailabilityRequest: openOffer not found.");
                availabilityResult = AvailabilityResult.OFFER_TAKEN;
            }

            OfferAvailabilityResponse offerAvailabilityResponse = new OfferAvailabilityResponse(request.offerId,
                    availabilityResult,
                    makerSignature);
            log.info("Send {} with offerId {}, uid {}, and result {} to peer {}",
                    offerAvailabilityResponse.getClass().getSimpleName(), offerAvailabilityResponse.getOfferId(),
                    offerAvailabilityResponse.getUid(),
                    availabilityResult,
                    peer);
            p2PService.sendEncryptedDirectMessage(peer,
                    request.getPubKeyRing(),
                    offerAvailabilityResponse,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}",
                                    offerAvailabilityResponse.getClass().getSimpleName(),
                                    offerAvailabilityResponse.getOfferId(),
                                    offerAvailabilityResponse.getUid());
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}",
                                    offerAvailabilityResponse.getClass().getSimpleName(),
                                    offerAvailabilityResponse.getUid(),
                                    peer,
                                    errorMessage);
                        }
                    });
            result = true;
        } catch (Throwable t) {
            errorMessage = "Exception at handleRequestIsOfferAvailableMessage " + t.getMessage();
            log.error(errorMessage + "\n", t);
        } finally {
            sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), result, errorMessage);
        }
    }

    private boolean apiUserDeniedByOffer(OfferAvailabilityRequest request) {
        return preferences.isDenyApiTaker() && request.isTakerApiUser();
    }

    private boolean takerDeniedByMaker(OfferAvailabilityRequest request) {
        if (request.getTradeRequest() == null) return true;
        return false; // TODO (woodser): implement taker verification here, doing work of ApplyFilter and VerifyPeersAccountAgeWitness
    }

    private void sendAckMessage(Class<?> reqClass,
                                NodeAddress sender,
                                PubKeyRing senderPubKeyRing,
                                String offerId,
                                String uid,
                                boolean result,
                                String errorMessage) {
        String sourceUid = uid;
        AckMessage ackMessage = new AckMessage(p2PService.getNetworkNode().getNodeAddress(),
                AckMessageSourceType.OFFER_MESSAGE,
                reqClass.getSimpleName(),
                sourceUid,
                offerId,
                result,
                errorMessage);

        if (ackMessage.isSuccess()) {
            log.info("Send AckMessage for {} to peer {} with offerId {} and sourceUid {}",
                    reqClass.getSimpleName(), sender, offerId, ackMessage.getSourceUid());
        } else {
            log.warn("Sending NACK for {} to peer {} with offerId {} and sourceUid {}, errorMessage={}",
                    reqClass.getSimpleName(), sender, offerId, ackMessage.getSourceUid(), errorMessage);
        }

        p2PService.sendEncryptedDirectMessage(
                sender,
                senderPubKeyRing,
                ackMessage,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for {} arrived at sender {}. offerId={}, sourceUid={}",
                                reqClass.getSimpleName(), sender, offerId, ackMessage.getSourceUid());
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for {} failed. AckMessage={}, sender={}, errorMessage={}",
                                reqClass.getSimpleName(), ackMessage, sender, errorMessage);
                    }
                }
        );
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Update persisted offer if a new capability is required after a software update
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void maybeUpdatePersistedOffers() {

        // update open offers
        List<OpenOffer> updatedOpenOffers = new ArrayList<>();
        getOpenOffers().forEach(originalOpenOffer -> {
            Offer originalOffer = originalOpenOffer.getOffer();

            OfferPayload originalOfferPayload = originalOffer.getOfferPayload();
            // We added CAPABILITIES with entry for Capability.MEDIATION in v1.1.6 and
            // Capability.REFUND_AGENT in v1.2.0 and want to rewrite a
            // persisted offer after the user has updated to 1.2.0 so their offer will be accepted by the network.

            if (originalOfferPayload.getProtocolVersion() < Version.TRADE_PROTOCOL_VERSION ||
                    !OfferRestrictions.hasOfferMandatoryCapability(originalOffer, Capability.MEDIATION) ||
                    !OfferRestrictions.hasOfferMandatoryCapability(originalOffer, Capability.REFUND_AGENT) ||
                    !originalOfferPayload.getOwnerNodeAddress().equals(p2PService.getAddress())) {

                // - Capabilities changed?
                // We rewrite our offer with the additional capabilities entry
                Map<String, String> updatedExtraDataMap = new HashMap<>();
                if (!OfferRestrictions.hasOfferMandatoryCapability(originalOffer, Capability.MEDIATION) ||
                        !OfferRestrictions.hasOfferMandatoryCapability(originalOffer, Capability.REFUND_AGENT)) {
                    Map<String, String> originalExtraDataMap = originalOfferPayload.getExtraDataMap();

                    if (originalExtraDataMap != null) {
                        updatedExtraDataMap.putAll(originalExtraDataMap);
                    }

                    // We overwrite any entry with our current capabilities
                    updatedExtraDataMap.put(OfferPayload.CAPABILITIES, Capabilities.app.toStringList());

                    log.info("Converted offer to support new Capability.MEDIATION and Capability.REFUND_AGENT capability. id={}", originalOffer.getId());
                } else {
                    updatedExtraDataMap = originalOfferPayload.getExtraDataMap();
                }

                // - Protocol version changed?
                int protocolVersion = originalOfferPayload.getProtocolVersion();
                if (protocolVersion < Version.TRADE_PROTOCOL_VERSION) {
                    // We update the trade protocol version
                    protocolVersion = Version.TRADE_PROTOCOL_VERSION;
                    log.info("Updated the protocol version of offer id={}", originalOffer.getId());
                }

                // - node address changed? (due to a faulty tor dir)
                NodeAddress ownerNodeAddress = originalOfferPayload.getOwnerNodeAddress();
                if (!ownerNodeAddress.equals(p2PService.getAddress())) {
                    ownerNodeAddress = p2PService.getAddress();
                    log.info("Updated the owner nodeaddress of offer id={}", originalOffer.getId());
                }

                long normalizedPrice = originalOffer.isInverted() ? PriceUtil.invertLongPrice(originalOfferPayload.getPrice(), originalOffer.getCounterCurrencyCode()) : originalOfferPayload.getPrice();
                OfferPayload updatedPayload = new OfferPayload(originalOfferPayload.getId(),
                        originalOfferPayload.getDate(),
                        ownerNodeAddress,
                        originalOfferPayload.getPubKeyRing(),
                        originalOfferPayload.getDirection(),
                        normalizedPrice,
                        originalOfferPayload.getMarketPriceMarginPct(),
                        originalOfferPayload.isUseMarketBasedPrice(),
                        originalOfferPayload.getAmount(),
                        originalOfferPayload.getMinAmount(),
                        originalOfferPayload.getMakerFeePct(),
                        originalOfferPayload.getTakerFeePct(),
                        HavenoUtils.PENALTY_FEE_PCT,
                        originalOfferPayload.getBuyerSecurityDepositPct(),
                        originalOfferPayload.getSellerSecurityDepositPct(),
                        originalOffer.getBaseCurrencyCode(),
                        originalOffer.getCounterCurrencyCode(),
                        originalOfferPayload.getPaymentMethodId(),
                        originalOfferPayload.getMakerPaymentAccountId(),
                        originalOfferPayload.getCountryCode(),
                        originalOfferPayload.getAcceptedCountryCodes(),
                        originalOfferPayload.getBankId(),
                        originalOfferPayload.getAcceptedBankIds(),
                        Version.VERSION,
                        originalOfferPayload.getBlockHeightAtOfferCreation(),
                        originalOfferPayload.getMaxTradeLimit(),
                        originalOfferPayload.getMaxTradePeriod(),
                        originalOfferPayload.isUseAutoClose(),
                        originalOfferPayload.isUseReOpenAfterAutoClose(),
                        originalOfferPayload.getLowerClosePrice(),
                        originalOfferPayload.getUpperClosePrice(),
                        originalOfferPayload.isPrivateOffer(),
                        originalOfferPayload.getChallengeHash(),
                        updatedExtraDataMap,
                        protocolVersion,
                        null,
                        null,
                        null,
                        originalOfferPayload.getExtraInfo());

                // cancel old offer
                log.info("Canceling outdated offer id={}", originalOffer.getId());
                doCancelOffer(originalOpenOffer, false);

                // create new offer
                Offer updatedOffer = new Offer(updatedPayload);
                updatedOffer.setPriceFeedService(priceFeedService);
                long normalizedTriggerPrice = originalOffer.isInverted() ? PriceUtil.invertLongPrice(originalOpenOffer.getTriggerPrice(), originalOffer.getCounterCurrencyCode()) : originalOpenOffer.getTriggerPrice();
                OpenOffer updatedOpenOffer = new OpenOffer(updatedOffer, normalizedTriggerPrice, originalOpenOffer.isReserveExactAmount(), originalOpenOffer.getGroupId());
                updatedOpenOffer.setChallenge(originalOpenOffer.getChallenge());
                updatedOpenOffers.add(updatedOpenOffer);
            }
        });

        // add updated open offers
        updatedOpenOffers.forEach(updatedOpenOffer -> {
            addOpenOffer(updatedOpenOffer);
            requestPersistence();
            log.info("Updating offer completed. id={}", updatedOpenOffer.getId());
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // RepublishOffers, refreshOffers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void republishOffers() {
        if (stopped) {
            return;
        }

        stopPeriodicRefreshOffersTimer();

        ThreadUtils.execute(() -> {
            processListForRepublishOffers(new ArrayList<>(getOpenOffers())); // list will be modified
        }, THREAD_ID);
    }

    // modifies the given list
    private void processListForRepublishOffers(List<OpenOffer> list) {
        if (list.isEmpty()) {
            return;
        }

        OpenOffer openOffer = list.remove(0);
        boolean contained = false;
        synchronized (openOffers.getList()) {
            contained = openOffers.contains(openOffer);
        }
        if (contained) {
            // TODO It is not clear yet if it is better for the node and the network to send out all add offer
            //  messages in one go or to spread it over a delay. With power users who have 100-200 offers that can have
            //  some significant impact to user experience and the network
            maybeRepublishOffer(openOffer, () -> processListForRepublishOffers(list));

            /* republishOffer(openOffer,
                    () -> UserThread.runAfter(() -> processListForRepublishOffers(list),
                            30, TimeUnit.MILLISECONDS));*/
        } else {
            // If the offer was removed in the meantime or if its deactivated we skip and call
            // processListForRepublishOffers again with the list where we removed the offer already.
            processListForRepublishOffers(list);
        }
    }

    private void maybeRepublishOffer(OpenOffer openOffer, @Nullable Runnable completeHandler) {
        ThreadUtils.execute(() -> {

            // skip if prevented from publishing
            if (preventedFromPublishing(openOffer, false)) {
                if (completeHandler != null) completeHandler.run();
                return;
            }

            // reprocess offer then publish
            synchronized (processOffersLock) {
                CountDownLatch latch = new CountDownLatch(1);
                processOffer(getOpenOffers(), openOffer, (transaction) -> {
                    requestPersistence();
                    latch.countDown();

                    // skip if prevented from publishing
                    if (preventedFromPublishing(openOffer, true)) {
                        if (completeHandler != null) completeHandler.run();
                        return;
                    }
                    
                    // publish offer to books
                    offerBookService.addOffer(openOffer.getOffer(),
                            () -> {
                                if (!stopped) {

                                    // refresh means we send only the data needed to refresh the TTL (hash, signature and sequence no.)
                                    if (periodicRefreshOffersTimer == null) {
                                        startPeriodicRefreshOffersTimer();
                                    }
                                    if (completeHandler != null) {
                                        completeHandler.run();
                                    }
                                }
                            },
                            errorMessage -> {
                                if (!stopped) {
                                    log.error("Adding offer to P2P network failed. " + errorMessage);
                                    stopRetryRepublishOffersTimer();
                                    retryRepublishOffersTimer = UserThread.runAfter(OpenOfferManager.this::republishOffers,
                                            RETRY_REPUBLISH_DELAY_SEC);
                                    if (completeHandler != null) completeHandler.run();
                                }
                            });
                }, (errorMessage) -> {
                    log.warn("Error republishing offer {}: {}", openOffer.getId(), errorMessage);
                    latch.countDown();
                    if (completeHandler != null) completeHandler.run();
                });
                HavenoUtils.awaitLatch(latch);
            }
        }, THREAD_ID);
    }

    private boolean preventedFromPublishing(OpenOffer openOffer, boolean checkSignature) {
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) return true;
        return openOffer.isDeactivated() ||
                openOffer.isCanceled() ||
                (checkSignature && openOffer.getOffer().getOfferPayload().getArbitratorSigner() == null) ||
                hasConflictingClone(openOffer);
    }

    private void startPeriodicRepublishOffersTimer() {
        stopped = false;
        if (periodicRepublishOffersTimer == null) {
            periodicRepublishOffersTimer = UserThread.runPeriodically(() -> {
                        if (!stopped) {
                            republishOffers();
                        }
                    },
                    REPUBLISH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void startPeriodicRefreshOffersTimer() {
        stopped = false;
        // refresh sufficiently before offer would expire
        if (periodicRefreshOffersTimer == null)
            periodicRefreshOffersTimer = UserThread.runPeriodically(() -> {
                        if (!stopped) {
                            log.info("Refreshing my open offers");
                            synchronized (openOffers.getList()) {
                                int size = openOffers.size();
                                //we clone our list as openOffers might change during our delayed call
                                final ArrayList<OpenOffer> openOffersList = new ArrayList<>(openOffers.getList());
                                for (int i = 0; i < size; i++) {
                                    // we delay to avoid reaching throttle limits
                                    // roughly 4 offers per second
    
                                    long delay = 300;
                                    final long minDelay = (i + 1) * delay;
                                    final long maxDelay = (i + 2) * delay;
                                    final OpenOffer openOffer = openOffersList.get(i);
                                    UserThread.runAfterRandomDelay(() -> {
                                        // we need to check if in the meantime the offer has been removed
                                        boolean contained = false;
                                        synchronized (openOffers.getList()) {
                                            contained = openOffers.contains(openOffer);
                                        }
                                        if (contained) maybeRefreshOffer(openOffer, 0, 1);
                                    }, minDelay, maxDelay, TimeUnit.MILLISECONDS);
                                }
                            }
                        } else {
                            log.debug("We have stopped already. We ignore that periodicRefreshOffersTimer.run call.");
                        }
                    },
                    REFRESH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        else
            log.trace("periodicRefreshOffersTimer already stated");
    }

    private void maybeRefreshOffer(OpenOffer openOffer, int numAttempts, int maxAttempts) {
        if (preventedFromPublishing(openOffer, true)) return;
        offerBookService.refreshTTL(openOffer.getOffer().getOfferPayload(),
                () -> log.debug("Successful refreshed TTL for offer"),
                (errorMessage) -> {
                    log.warn(errorMessage);
                    if (numAttempts + 1 < maxAttempts) {
                        UserThread.runAfter(() -> maybeRefreshOffer(openOffer, numAttempts + 1, maxAttempts), 10);
                    }
                });
    }

    private void restart() {
        log.debug("Restart after connection loss");
        if (retryRepublishOffersTimer == null)
            retryRepublishOffersTimer = UserThread.runAfter(() -> {
                stopped = false;
                stopRetryRepublishOffersTimer();
                republishOffers();
            }, RETRY_REPUBLISH_DELAY_SEC);

        startPeriodicRepublishOffersTimer();
    }

    private void requestPersistence() {
        persistenceManager.requestPersistence();
        signedOfferPersistenceManager.requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void stopPeriodicRefreshOffersTimer() {
        if (periodicRefreshOffersTimer != null) {
            periodicRefreshOffersTimer.stop();
            periodicRefreshOffersTimer = null;
        }
    }

    private void stopPeriodicRepublishOffersTimer() {
        if (periodicRepublishOffersTimer != null) {
            periodicRepublishOffersTimer.stop();
            periodicRepublishOffersTimer = null;
        }
    }

    private void stopRetryRepublishOffersTimer() {
        if (retryRepublishOffersTimer != null) {
            retryRepublishOffersTimer.stop();
            retryRepublishOffersTimer = null;
        }
    }
}
