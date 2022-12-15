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

package bisq.core.offer;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.api.CoreContext;
import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.exceptions.TradePriceOutOfToleranceException;
import bisq.core.filter.FilterManager;
import bisq.core.offer.messages.OfferAvailabilityRequest;
import bisq.core.offer.messages.OfferAvailabilityResponse;
import bisq.core.offer.messages.SignOfferRequest;
import bisq.core.offer.messages.SignOfferResponse;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.offer.placeoffer.PlaceOfferProtocol;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.dispute.arbitration.arbitrator.Arbitrator;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.trade.ClosedTradableManager;
import bisq.core.trade.TradableList;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.handlers.TransactionResultHandler;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.Preferences;
import bisq.core.user.User;
import bisq.core.util.JsonUtil;
import bisq.core.util.Validator;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import bisq.network.p2p.peers.Broadcaster;
import bisq.network.p2p.peers.PeerManager;
import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Capabilities;
import bisq.common.app.Capability;
import bisq.common.app.Version;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.util.Tuple2;
import org.bitcoinj.core.Coin;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Getter;
import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

public class OpenOfferManager implements PeerManager.Listener, DecryptedDirectMessageListener, PersistedDataHost {
    private static final Logger log = LoggerFactory.getLogger(OpenOfferManager.class);

    private static final long RETRY_REPUBLISH_DELAY_SEC = 10;
    private static final long REPUBLISH_AGAIN_AT_STARTUP_DELAY_SEC = 30;
    private static final long REPUBLISH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(40);
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(6);

    private final CoreContext coreContext;
    private final KeyRing keyRing;
    private final User user;
    private final P2PService p2PService;
    private final CoreMoneroConnectionsService connectionService;
    private final BtcWalletService btcWalletService;
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
    private BigInteger lastUnlockedBalance;
    private boolean stopped;
    private Timer periodicRepublishOffersTimer, periodicRefreshOffersTimer, retryRepublishOffersTimer;
    @Getter
    private final ObservableList<Tuple2<OpenOffer, String>> invalidOffers = FXCollections.observableArrayList();
    @Getter
    private final AccountAgeWitnessService accountAgeWitnessService;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OpenOfferManager(CoreContext coreContext,
                            KeyRing keyRing,
                            User user,
                            P2PService p2PService,
                            CoreMoneroConnectionsService connectionService,
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
        this.connectionService = connectionService;
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
                public void onUpdatedDataReceived() {
                    onBootstrapComplete();
                }
            });
        }

        cleanUpAddressEntries();

        // TODO: add to invalid offers on failure
//        openOffers.stream()
//                .forEach(openOffer -> OfferUtil.getInvalidMakerFeeTxErrorMessage(openOffer.getOffer(), btcWalletService)
//                        .ifPresent(errorMsg -> invalidOffers.add(new Tuple2<>(openOffer, errorMsg))));

        // process unposted offers
        processUnpostedOffers((transaction) -> {}, (errMessage) -> {
            log.warn("Error processing unposted offers on new unlocked balance: " + errMessage);
        });

        // register to process unposted offers when unlocked balance increases
        if (xmrWalletService.getWallet() != null) lastUnlockedBalance = xmrWalletService.getWallet().getUnlockedBalance(0);
        xmrWalletService.addWalletListener(new MoneroWalletListener() {
            @Override
            public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
                if (lastUnlockedBalance == null || lastUnlockedBalance.compareTo(newUnlockedBalance) < 0) {
                    processUnpostedOffers((transaction) -> {}, (errMessage) -> {
                        log.warn("Error processing unposted offers on new unlocked balance: " + errMessage);
                    });
                }
                lastUnlockedBalance = newUnlockedBalance;
            }
        });
    }

    private void cleanUpAddressEntries() {
        Set<String> openOffersIdSet = openOffers.getList().stream().map(OpenOffer::getId).collect(Collectors.toSet());
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

        stopPeriodicRefreshOffersTimer();
        stopPeriodicRepublishOffersTimer();
        stopRetryRepublishOffersTimer();

        // we remove own offers from offerbook when we go offline
        // Normally we use a delay for broadcasting to the peers, but at shut down we want to get it fast out
        int size = openOffers.size();
        log.info("Remove open offers at shutDown. Number of open offers: {}", size);
        if (offerBookService.isBootstrapped() && size > 0) {
            UserThread.execute(() -> openOffers.forEach(
                    openOffer -> offerBookService.removeOfferAtShutDown(openOffer.getOffer().getOfferPayload())
            ));

            // Force broadcaster to send out immediately, otherwise we could have a 2 sec delay until the
            // bundled messages sent out.
            broadcaster.flush();

            if (completeHandler != null) {
                // For typical number of offers we are tolerant with delay to give enough time to broadcast.
                // If number of offers is very high we limit to 3 sec. to not delay other shutdown routines.
                int delay = Math.min(3000, size * 200 + 500);
                UserThread.runAfter(completeHandler, delay, TimeUnit.MILLISECONDS);
            }
        } else {
            if (completeHandler != null)
                completeHandler.run();
        }
    }

    public void removeAllOpenOffers(@Nullable Runnable completeHandler) {
        removeOpenOffers(getObservableList(), completeHandler);
    }

    public void removeOpenOffers(List<OpenOffer> openOffers, @Nullable Runnable completeHandler) {
        int size = openOffers.size();
        // Copy list as we remove in the loop
        List<OpenOffer> openOffersList = new ArrayList<>(openOffers);
        openOffersList.forEach(openOffer -> removeOpenOffer(openOffer, () -> {
        }, errorMessage -> {
        }));
        if (completeHandler != null)
            UserThread.runAfter(completeHandler, size * 200 + 500, TimeUnit.MILLISECONDS);
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
                           TransactionResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {
        checkNotNull(offer.getMakerFee(), "makerFee must not be null");

        boolean autoSplit = false; // TODO: support in api
        
        // TODO (woodser): validate offer
        
        // create open offer
        OpenOffer openOffer = new OpenOffer(offer, triggerPrice, autoSplit);
        
        // process open offer to schedule or post
        processUnpostedOffer(openOffer, (transaction) -> {
            openOffers.add(openOffer);
            requestPersistence();
            resultHandler.handleResult(transaction);
        }, (errMessage) -> {
            onRemoved(openOffer);
            offer.setErrorMessage(errMessage);
            errorMessageHandler.handleErrorMessage(errMessage);
        });
    }

    // Remove from offerbook
    public void removeOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Optional<OpenOffer> openOfferOptional = getOpenOfferById(offer.getId());
        if (openOfferOptional.isPresent()) {
            removeOpenOffer(openOfferOptional.get(), resultHandler, errorMessageHandler);
        } else {
            log.warn("Offer was not found in our list of open offers. We still try to remove it from the offerbook.");
            errorMessageHandler.handleErrorMessage("Offer was not found in our list of open offers. " + "We still try to remove it from the offerbook.");
            offerBookService.removeOffer(offer.getOfferPayload(), () -> offer.setState(Offer.State.REMOVED), null);
        }
    }

    public void activateOpenOffer(OpenOffer openOffer,
                                  ResultHandler resultHandler,
                                  ErrorMessageHandler errorMessageHandler) {
        if (!offersToBeEdited.containsKey(openOffer.getId())) {
            Offer offer = openOffer.getOffer();
            offerBookService.activateOffer(offer,
                    () -> {
                        openOffer.setState(OpenOffer.State.AVAILABLE);
                        requestPersistence();
                        log.debug("activateOpenOffer, offerId={}", offer.getId());
                        resultHandler.handleResult();
                    },
                    errorMessageHandler);
        } else {
            errorMessageHandler.handleErrorMessage("You can't activate an offer that is currently edited.");
        }
    }

    public void deactivateOpenOffer(OpenOffer openOffer,
                                    ResultHandler resultHandler,
                                    ErrorMessageHandler errorMessageHandler) {
        Offer offer = openOffer.getOffer();
        offerBookService.deactivateOffer(offer.getOfferPayload(),
                () -> {
                    openOffer.setState(OpenOffer.State.DEACTIVATED);
                    requestPersistence();
                    log.debug("deactivateOpenOffer, offerId={}", offer.getId());
                    resultHandler.handleResult();
                },
                errorMessageHandler);
    }

    public void removeOpenOffer(OpenOffer openOffer,
                                ResultHandler resultHandler,
                                ErrorMessageHandler errorMessageHandler) {
        if (!offersToBeEdited.containsKey(openOffer.getId())) {
            if (openOffer.isDeactivated()) {
                onRemoved(openOffer);
            } else {
                offerBookService.removeOffer(openOffer.getOffer().getOfferPayload(),
                        () -> onRemoved(openOffer),
                        errorMessageHandler);
            }
        } else {
            errorMessageHandler.handleErrorMessage("You can't remove an offer that is currently edited.");
        }
    }

    public void editOpenOfferStart(OpenOffer openOffer,
                                   ResultHandler resultHandler,
                                   ErrorMessageHandler errorMessageHandler) {
        if (offersToBeEdited.containsKey(openOffer.getId())) {
            log.warn("editOpenOfferStart called for an offer which is already in edit mode.");
            resultHandler.handleResult();
            return;
        }

        offersToBeEdited.put(openOffer.getId(), openOffer);

        if (openOffer.isDeactivated()) {
            resultHandler.handleResult();
        } else {
            deactivateOpenOffer(openOffer,
                    resultHandler,
                    errorMessage -> {
                        offersToBeEdited.remove(openOffer.getId());
                        errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        }
    }

    public void editOpenOfferPublish(Offer editedOffer,
                                     long triggerPrice,
                                     OpenOffer.State originalState,
                                     ResultHandler resultHandler,
                                     ErrorMessageHandler errorMessageHandler) {
        Optional<OpenOffer> openOfferOptional = getOpenOfferById(editedOffer.getId());

        if (openOfferOptional.isPresent()) {
            OpenOffer openOffer = openOfferOptional.get();

            openOffer.getOffer().setState(Offer.State.REMOVED);
            openOffer.setState(OpenOffer.State.CANCELED);
            openOffers.remove(openOffer);

            OpenOffer editedOpenOffer = new OpenOffer(editedOffer, triggerPrice);
            editedOpenOffer.setState(originalState);

            openOffers.add(editedOpenOffer);

            if (!editedOpenOffer.isDeactivated())
                republishOffer(editedOpenOffer);

            offersToBeEdited.remove(openOffer.getId());
            requestPersistence();
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("There is no offer with this id existing to be published.");
        }
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
        } else {
            errorMessageHandler.handleErrorMessage("Editing of offer can't be canceled as it is not edited.");
        }
    }

    private void onRemoved(@NotNull OpenOffer openOffer) {
        Offer offer = openOffer.getOffer();
        if (offer.getOfferPayload().getReserveTxKeyImages() != null) {
            for (String frozenKeyImage : offer.getOfferPayload().getReserveTxKeyImages()) xmrWalletService.getWallet().thawOutput(frozenKeyImage);
            xmrWalletService.saveMainWallet();
        }
        offer.setState(Offer.State.REMOVED);
        openOffer.setState(OpenOffer.State.CANCELED);
        openOffers.remove(openOffer);
        closedTradableManager.add(openOffer);
        xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId());
        log.info("onRemoved offerId={}", offer.getId());
        requestPersistence();
    }

    // Close openOffer after deposit published
    public void closeOpenOffer(Offer offer) {
        getOpenOfferById(offer.getId()).ifPresent(openOffer -> {
            openOffers.remove(openOffer);
            openOffer.setState(OpenOffer.State.CLOSED);
            xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId());
            offerBookService.removeOffer(openOffer.getOffer().getOfferPayload(),
                    () -> log.info("Successfully removed offer {}", offer.getId()),
                    log::error);
            requestPersistence();
        });
    }

    public void reserveOpenOffer(OpenOffer openOffer) {
        openOffer.setState(OpenOffer.State.RESERVED);
        requestPersistence();
    }

    public void unreserveOpenOffer(OpenOffer openOffer) {
        openOffer.setState(OpenOffer.State.AVAILABLE);
        requestPersistence();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<OpenOffer> getObservableList() {
        return openOffers.getObservableList();
    }

    public Optional<OpenOffer> getOpenOfferById(String offerId) {
        return new ArrayList<>(openOffers.getObservableList()).stream().filter(e -> e.getId().equals(offerId)).findFirst();
    }
    
    public Optional<SignedOffer> getSignedOfferById(String offerId) {
        return new ArrayList<>(signedOffers.getObservableList()).stream().filter(e -> e.getOfferId().equals(offerId)).findFirst();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Place offer helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void processUnpostedOffers(TransactionResultHandler resultHandler, // TODO (woodser): transaction not needed with result handler
                                       ErrorMessageHandler errorMessageHandler) {
        new Thread(() -> {
            List<String> errorMessages = new ArrayList<String>();
            for (OpenOffer scheduledOffer : new ArrayList<OpenOffer>(openOffers.getObservableList())) {
                if (scheduledOffer.getState() != OpenOffer.State.SCHEDULED) continue;
                CountDownLatch latch = new CountDownLatch(1);
                processUnpostedOffer(scheduledOffer, (transaction) -> {
                    latch.countDown();
                }, errorMessage -> {
                    onRemoved(scheduledOffer);
                    errorMessages.add(errorMessage);
                    latch.countDown();
                });
                HavenoUtils.awaitLatch(latch);
            }
            requestPersistence();
            if (errorMessages.size() > 0) errorMessageHandler.handleErrorMessage(errorMessages.toString());
            else resultHandler.handleResult(null);
        }).start();
    }

    private void processUnpostedOffer(OpenOffer openOffer, TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        new Thread(() -> {
            try {

                // done processing if wallet not initialized
                if (xmrWalletService.getWallet() == null) {
                    resultHandler.handleResult(null);
                    return;
                }

                // get offer reserve amount
                Coin offerReserveAmountCoin = openOffer.getOffer().getReserveAmount();
                BigInteger offerReserveAmount = HavenoUtils.centinerosToAtomicUnits(offerReserveAmountCoin.value);

                // handle sufficient available balance
                if (xmrWalletService.getWallet().getUnlockedBalance(0).compareTo(offerReserveAmount) >= 0) {

                    // split outputs if applicable
                    boolean splitOutput = openOffer.isAutoSplit(); // TODO: determine if output needs split
                    if (splitOutput) {
                        throw new Error("Post offer with split output option not yet supported"); // TODO: support scheduling offer with split outputs
                    }

                    // otherwise sign and post offer
                    else {
                        signAndPostOffer(openOffer, offerReserveAmountCoin, true, resultHandler, errorMessageHandler);
                    }
                    return;
                }

                // handle unscheduled offer
                if (openOffer.getScheduledTxHashes() == null) {
                    log.info("Scheduling offer " + openOffer.getId());

                    // check for sufficient balance - scheduled offers amount
                    if (xmrWalletService.getWallet().getBalance(0).subtract(getScheduledAmount()).compareTo(offerReserveAmount) < 0) {
                        throw new RuntimeException("Not enough money in Haveno wallet");
                    }

                    // get locked txs
                    List<MoneroTxWallet> lockedTxs = xmrWalletService.getWallet().getTxs(new MoneroTxQuery().setIsLocked(true));

                    // get earliest unscheduled txs with sufficient incoming amount
                    List<String> scheduledTxHashes = new ArrayList<String>();
                    BigInteger scheduledAmount = new BigInteger("0");
                    for (MoneroTxWallet lockedTx : lockedTxs) {
                        if (isTxScheduled(lockedTx.getHash())) continue;
                        if (lockedTx.getIncomingTransfers() == null || lockedTx.getIncomingTransfers().isEmpty()) continue;
                        scheduledTxHashes.add(lockedTx.getHash());
                        for (MoneroIncomingTransfer transfer : lockedTx.getIncomingTransfers()) {
                            if (transfer.getAccountIndex() == 0) scheduledAmount = scheduledAmount.add(transfer.getAmount());
                        }
                        if (scheduledAmount.compareTo(offerReserveAmount) >= 0) break;
                    }
                    if (scheduledAmount.compareTo(offerReserveAmount) < 0) throw new RuntimeException("Not enough funds to schedule offer");

                    // schedule txs
                    openOffer.setScheduledTxHashes(scheduledTxHashes);
                    openOffer.setScheduledAmount(scheduledAmount.toString());
                    openOffer.setState(OpenOffer.State.SCHEDULED);
                }

                // handle result
                resultHandler.handleResult(null);
            } catch (Exception e) {
                e.printStackTrace();
                errorMessageHandler.handleErrorMessage(e.getMessage());
            }
        }).start();
    }

    private BigInteger getScheduledAmount() {
        BigInteger scheduledAmount = new BigInteger("0");
        for (OpenOffer openOffer : openOffers.getObservableList()) {
            if (openOffer.getState() != OpenOffer.State.SCHEDULED) continue;
            if (openOffer.getScheduledTxHashes() == null) continue;
            List<MoneroTxWallet> fundingTxs = xmrWalletService.getWallet().getTxs(openOffer.getScheduledTxHashes());
            for (MoneroTxWallet fundingTx : fundingTxs) {
                for (MoneroIncomingTransfer transfer : fundingTx.getIncomingTransfers()) {
                    if (transfer.getAccountIndex() == 0) scheduledAmount = scheduledAmount.add(transfer.getAmount());
                }
            }
        }
        return scheduledAmount;
    }

    private boolean isTxScheduled(String txHash) {
        for (OpenOffer openOffer : openOffers.getObservableList()) {
            if (openOffer.getState() != OpenOffer.State.SCHEDULED) continue;
            if (openOffer.getScheduledTxHashes() == null) continue;
            for (String scheduledTxHash : openOffer.getScheduledTxHashes()) {
                if (txHash.equals(scheduledTxHash)) return true;
            }
        }
        return false;
    }

    private void signAndPostOffer(OpenOffer openOffer,
                                  Coin offerReserveAmount,
                                  boolean useSavingsWallet, // TODO: remove this
                                  TransactionResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        log.info("Signing and posting offer " + openOffer.getId());

        // create model
        PlaceOfferModel model = new PlaceOfferModel(openOffer.getOffer(),
                offerReserveAmount,
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
                accountAgeWitnessService);

        // create protocol
        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(model,
                transaction -> {

                    // set reserve tx on open offer
                    openOffer.setReserveTxHash(model.getReserveTx().getHash());
                    openOffer.setReserveTxHex(model.getReserveTx().getFullHex());
                    openOffer.setReserveTxKey(model.getReserveTx().getKey());

                    // set offer state
                    openOffer.setState(OpenOffer.State.AVAILABLE);
                    requestPersistence();

                    resultHandler.handleResult(transaction);
                    if (!stopped) {
                        startPeriodicRepublishOffersTimer();
                        startPeriodicRefreshOffersTimer();
                    } else {
                        log.debug("We have stopped already. We ignore that placeOfferProtocol.placeOffer.onResult call.");
                    }
                },
                errorMessageHandler);

        // run protocol
        synchronized (placeOfferProtocols) {
            placeOfferProtocols.put(openOffer.getOffer().getId(), placeOfferProtocol);
        }
        placeOfferProtocol.placeOffer(); // TODO (woodser): if error placing offer (e.g. bad signature), remove protocol and unfreeze trade funds
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
              log.info(errorMessage);
              sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
              return;
            }
            
            // verify arbitrator is signer of offer payload
            if (!thisAddress.equals(request.getOfferPayload().getArbitratorSigner())) {
                errorMessage = "Cannot sign offer because offer payload is for a different arbitrator";
                log.info(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }
            
            // verify offer not seen before
            Optional<OpenOffer> openOfferOptional = getOpenOfferById(request.offerId);
            if (openOfferOptional.isPresent()) {
                errorMessage = "We already got a request to sign offer id " + request.offerId;
                log.info(errorMessage);
                sendAckMessage(request.getClass(), peer, request.getPubKeyRing(), request.getOfferId(), request.getUid(), false, errorMessage);
                return;
            }
            
            // verify maker's reserve tx (double spend, trade fee, trade amount, mining fee)
            Offer offer = new Offer(request.getOfferPayload());
            BigInteger tradeFee = HavenoUtils.coinToAtomicUnits(offer.getMakerFee());
            BigInteger peerAmount =  HavenoUtils.coinToAtomicUnits(offer.getDirection() == OfferDirection.BUY ? Coin.ZERO : offer.getAmount());
            BigInteger securityDeposit = HavenoUtils.coinToAtomicUnits(offer.getDirection() == OfferDirection.BUY ? offer.getBuyerSecurityDeposit() : offer.getSellerSecurityDeposit());
            xmrWalletService.verifyTradeTx(
                    tradeFee,
                    peerAmount,
                    securityDeposit,
                    request.getPayoutAddress(),
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKey(),
                    request.getReserveTxKeyImages());

            // arbitrator signs offer to certify they have valid reserve tx
            String offerPayloadAsJson = JsonUtil.objectToJson(request.getOfferPayload());
            String signature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), offerPayloadAsJson);
            OfferPayload signedOfferPayload = request.getOfferPayload();
            signedOfferPayload.setArbitratorSignature(signature);
            
            // create record of signed offer
            SignedOffer signedOffer = new SignedOffer(signedOfferPayload.getId(), request.getReserveTxHash(), request.getReserveTxHex(), signature); // TODO (woodser): no need for signature to be part of SignedOffer?
            signedOffers.add(signedOffer);
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
            e.printStackTrace();
            errorMessage = "Exception at handleSignOfferRequest " + e.getMessage();
            log.error(errorMessage);
        } finally {
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
        if (!connectionService.isChainHeightSyncedWithinTolerance()) {
            errorMessage = "We got a handleOfferAvailabilityRequest but our chain is not synced.";
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
            Optional<OpenOffer> openOfferOptional = getOpenOfferById(request.offerId);
            AvailabilityResult availabilityResult;
            String makerSignature = null;
            if (openOfferOptional.isPresent()) {
                OpenOffer openOffer = openOfferOptional.get();
                if (!apiUserDeniedByOffer(request)) {
                    if (!takerDeniedByMaker(request)) {
                        if (openOffer.getState() == OpenOffer.State.AVAILABLE) {
                            Offer offer = openOffer.getOffer();
                            if (preferences.getIgnoreTradersList().stream().noneMatch(fullAddress -> fullAddress.equals(peer.getFullAddress()))) {
                                
                                // maker signs taker's request
                                String tradeRequestAsJson = JsonUtil.objectToJson(request.getTradeRequest());
                                makerSignature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), tradeRequestAsJson);

                                try {
                                    // Check also tradePrice to avoid failures after taker fee is paid caused by a too big difference
                                    // in trade price between the peers. Also here poor connectivity might cause market price API connection
                                    // losses and therefore an outdated market price.
                                    offer.verifyTakersTradePrice(request.getTakersTradePrice());
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
            log.info("Send {} with offerId {} and uid {} to peer {}",
                    offerAvailabilityResponse.getClass().getSimpleName(), offerAvailabilityResponse.getOfferId(),
                    offerAvailabilityResponse.getUid(), peer);
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
            log.error(errorMessage);
            t.printStackTrace();
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

        log.info("Send AckMessage for {} to peer {} with offerId {} and sourceUid {}",
                reqClass.getSimpleName(), sender, offerId, ackMessage.getSourceUid());
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
    
    // TODO (woodser): arbitrator signature will be invalid if offer updated (exclude updateable fields from signature? re-sign?)

    private void maybeUpdatePersistedOffers() {
        // We need to clone to avoid ConcurrentModificationException
        ArrayList<OpenOffer> openOffersClone = new ArrayList<>(openOffers.getList());
        openOffersClone.forEach(originalOpenOffer -> {
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

                OfferPayload updatedPayload = new OfferPayload(originalOfferPayload.getId(),
                        originalOfferPayload.getDate(),
                        ownerNodeAddress,
                        originalOfferPayload.getPubKeyRing(),
                        originalOfferPayload.getDirection(),
                        originalOfferPayload.getPrice(),
                        originalOfferPayload.getMarketPriceMarginPct(),
                        originalOfferPayload.isUseMarketBasedPrice(),
                        originalOfferPayload.getAmount(),
                        originalOfferPayload.getMinAmount(),
                        originalOfferPayload.getBaseCurrencyCode(),
                        originalOfferPayload.getCounterCurrencyCode(),
                        originalOfferPayload.getPaymentMethodId(),
                        originalOfferPayload.getMakerPaymentAccountId(),
                        originalOfferPayload.getOfferFeePaymentTxId(),
                        originalOfferPayload.getCountryCode(),
                        originalOfferPayload.getAcceptedCountryCodes(),
                        originalOfferPayload.getBankId(),
                        originalOfferPayload.getAcceptedBankIds(),
                        originalOfferPayload.getVersionNr(),
                        originalOfferPayload.getBlockHeightAtOfferCreation(),
                        originalOfferPayload.getTxFee(),
                        originalOfferPayload.getMakerFee(),
                        originalOfferPayload.getBuyerSecurityDeposit(),
                        originalOfferPayload.getSellerSecurityDeposit(),
                        originalOfferPayload.getMaxTradeLimit(),
                        originalOfferPayload.getMaxTradePeriod(),
                        originalOfferPayload.isUseAutoClose(),
                        originalOfferPayload.isUseReOpenAfterAutoClose(),
                        originalOfferPayload.getLowerClosePrice(),
                        originalOfferPayload.getUpperClosePrice(),
                        originalOfferPayload.isPrivateOffer(),
                        originalOfferPayload.getHashOfChallenge(),
                        updatedExtraDataMap,
                        protocolVersion,
                        originalOfferPayload.getArbitratorSigner(),
                        originalOfferPayload.getArbitratorSignature(),
                        originalOfferPayload.getReserveTxKeyImages());

                // Save states from original data to use for the updated
                Offer.State originalOfferState = originalOffer.getState();
                OpenOffer.State originalOpenOfferState = originalOpenOffer.getState();

                // remove old offer
                originalOffer.setState(Offer.State.REMOVED);
                originalOpenOffer.setState(OpenOffer.State.CANCELED);
                openOffers.remove(originalOpenOffer);

                // Create new Offer
                Offer updatedOffer = new Offer(updatedPayload);
                updatedOffer.setPriceFeedService(priceFeedService);
                updatedOffer.setState(originalOfferState);

                OpenOffer updatedOpenOffer = new OpenOffer(updatedOffer, originalOpenOffer.getTriggerPrice());
                updatedOpenOffer.setState(originalOpenOfferState);
                openOffers.add(updatedOpenOffer);
                requestPersistence();

                log.info("Updating offer completed. id={}", originalOffer.getId());
            }
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

        List<OpenOffer> openOffersList = new ArrayList<>(openOffers.getList());
        processListForRepublishOffers(openOffersList);
    }

    private void processListForRepublishOffers(List<OpenOffer> list) {
        if (list.isEmpty()) {
            return;
        }

        OpenOffer openOffer = list.remove(0);
        if (openOffers.contains(openOffer) && !openOffer.isDeactivated()) {
            // TODO It is not clear yet if it is better for the node and the network to send out all add offer
            //  messages in one go or to spread it over a delay. With power users who have 100-200 offers that can have
            //  some significant impact to user experience and the network
            republishOffer(openOffer, () -> processListForRepublishOffers(list));

           /* republishOffer(openOffer,
                    () -> UserThread.runAfter(() -> processListForRepublishOffers(list),
                            30, TimeUnit.MILLISECONDS));*/
        } else {
            // If the offer was removed in the meantime or if its deactivated we skip and call
            // processListForRepublishOffers again with the list where we removed the offer already.
            processListForRepublishOffers(list);
        }
    }

    private void republishOffer(OpenOffer openOffer) {
        republishOffer(openOffer, null);
    }

    private void republishOffer(OpenOffer openOffer, @Nullable Runnable completeHandler) {
        offerBookService.addOffer(openOffer.getOffer(),
                () -> {
                    if (!stopped) {
                        // Refresh means we send only the data needed to refresh the TTL (hash, signature and sequence no.)
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

                        if (completeHandler != null) {
                            completeHandler.run();
                        }
                    }
                });
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
                                    if (openOffers.contains(openOffer) && !openOffer.isDeactivated())
                                        refreshOffer(openOffer);
                                }, minDelay, maxDelay, TimeUnit.MILLISECONDS);
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

    private void refreshOffer(OpenOffer openOffer) {
        offerBookService.refreshTTL(openOffer.getOffer().getOfferPayload(),
                () -> log.debug("Successful refreshed TTL for offer"),
                log::warn);
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
