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

package haveno.core.trade;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import haveno.common.ClockWatcher;
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.FaultHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.offer.SignedOffer;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.trade.Trade.DisputeState;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.DepositResponse;
import haveno.core.trade.messages.DepositsConfirmedMessage;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.ArbitratorProtocol;
import haveno.core.trade.protocol.MakerProtocol;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.trade.protocol.TakerProtocol;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.trade.protocol.TradeProtocolFactory;
import haveno.core.trade.protocol.TraderProtocol;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.util.Validator;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendMailboxMessageListener;
import haveno.network.p2p.mailbox.MailboxMessage;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.network.TorNetworkNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import monero.daemon.model.MoneroTx;
import org.bitcoinj.core.Coin;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TradeManager implements PersistedDataHost, DecryptedDirectMessageListener {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private boolean isShutDownStarted;
    private boolean isShutDown;
    private final User user;
    @Getter
    private final KeyRing keyRing;
    private final CoreAccountService accountService;
    private final XmrWalletService xmrWalletService;
    @Getter
    private final CoreNotificationService notificationService;
    private final OfferBookService offerBookService;
    @Getter
    private final OpenOfferManager openOfferManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final OfferUtil offerUtil;
    private final TradeUtil tradeUtil;
    @Getter
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final ProcessModelServiceProvider processModelServiceProvider;
    private final ClockWatcher clockWatcher;

    private final Map<String, TradeProtocol> tradeProtocolByTradeId = new HashMap<>();
    private final PersistenceManager<TradableList<Trade>> persistenceManager;
    private final TradableList<Trade> tradableList = new TradableList<>();
    @Getter
    private final BooleanProperty persistedTradesInitialized = new SimpleBooleanProperty();
    @Getter
    private final LongProperty numPendingTrades = new SimpleLongProperty();
    private final ReferralIdService referralIdService;

    @Setter
    @Nullable
    private Consumer<String> lockedUpFundsHandler; // TODO: this is unused

    // set comparator for processing mailbox messages
    static {
        MailboxMessageService.setMailboxMessageComparator(new MailboxMessageComparator());
    }

    /**
     * Sort mailbox messages for processing.
     */
    public static class MailboxMessageComparator implements Comparator<MailboxMessage> {
        private static List<Class<? extends MailboxMessage>> messageOrder = Arrays.asList(
            AckMessage.class,
            DepositsConfirmedMessage.class,
            PaymentSentMessage.class,
            PaymentReceivedMessage.class,
            DisputeOpenedMessage.class,
            DisputeClosedMessage.class);

        @Override
        public int compare(MailboxMessage m1, MailboxMessage m2) {
            int idx1 = messageOrder.indexOf(m1.getClass());
            int idx2 = messageOrder.indexOf(m2.getClass());
            return idx1 - idx2;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user,
                        KeyRing keyRing,
                        CoreAccountService accountService,
                        XmrWalletService xmrWalletService,
                        CoreNotificationService notificationService,
                        OfferBookService offerBookService,
                        OpenOfferManager openOfferManager,
                        ClosedTradableManager closedTradableManager,
                        FailedTradesManager failedTradesManager,
                        P2PService p2PService,
                        PriceFeedService priceFeedService,
                        TradeStatisticsManager tradeStatisticsManager,
                        OfferUtil offerUtil,
                        TradeUtil tradeUtil,
                        ArbitratorManager arbitratorManager,
                        MediatorManager mediatorManager,
                        ProcessModelServiceProvider processModelServiceProvider,
                        ClockWatcher clockWatcher,
                        PersistenceManager<TradableList<Trade>> persistenceManager,
                        ReferralIdService referralIdService) {
        this.user = user;
        this.keyRing = keyRing;
        this.accountService = accountService;
        this.xmrWalletService = xmrWalletService;
        this.notificationService = notificationService;
        this.offerBookService = offerBookService;
        this.openOfferManager = openOfferManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.offerUtil = offerUtil;
        this.tradeUtil = tradeUtil;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.processModelServiceProvider = processModelServiceProvider;
        this.clockWatcher = clockWatcher;
        this.referralIdService = referralIdService;
        this.persistenceManager = persistenceManager;

        this.persistenceManager.initialize(tradableList, "PendingTrades", PersistenceManager.Source.PRIVATE);

        p2PService.addDecryptedDirectMessageListener(this);

        failedTradesManager.setUnFailTradeCallback(this::unFailTrade);

        // TODO: better way to set references
        xmrWalletService.setTradeManager(this); // TODO: set reference in HavenoUtils for consistency
        HavenoUtils.notificationService = notificationService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
            synchronized (persisted.getList()) {
                tradableList.setAll(persisted.getList());
                tradableList.stream()
                        .filter(trade -> trade.getOffer() != null)
                        .forEach(trade -> trade.getOffer().setPriceFeedService(priceFeedService));
            }
            completeHandler.run();
        },
        completeHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey message, NodeAddress sender) {
        NetworkEnvelope networkEnvelope = message.getNetworkEnvelope();
        if (!(networkEnvelope instanceof TradeMessage)) return;
        TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
        String tradeId = tradeMessage.getOfferId();
        log.info("TradeManager received {} for tradeId={}, sender={}, uid={}", networkEnvelope.getClass().getSimpleName(), tradeId, sender, tradeMessage.getUid());
        ThreadUtils.execute(() -> {
            if (networkEnvelope instanceof InitTradeRequest) {
                handleInitTradeRequest((InitTradeRequest) networkEnvelope, sender);
            } else if (networkEnvelope instanceof InitMultisigRequest) {
                handleInitMultisigRequest((InitMultisigRequest) networkEnvelope, sender);
            } else if (networkEnvelope instanceof SignContractRequest) {
                handleSignContractRequest((SignContractRequest) networkEnvelope, sender);
            } else if (networkEnvelope instanceof SignContractResponse) {
                handleSignContractResponse((SignContractResponse) networkEnvelope, sender);
            } else if (networkEnvelope instanceof DepositRequest) {
                handleDepositRequest((DepositRequest) networkEnvelope, sender);
            } else if (networkEnvelope instanceof DepositResponse) {
                handleDepositResponse((DepositResponse) networkEnvelope, sender);
            }
        }, tradeId);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        if (p2PService.isBootstrapped()) {
            initPersistedTrades();
        } else {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onDataReceived() {
                    initPersistedTrades();
                }
            });
        }

        // listen for account updates
        accountService.addListener(new AccountServiceListener() {

            @Override
            public void onAccountCreated() {
                log.info(TradeManager.class + ".accountService.onAccountCreated()");
                initPersistedTrades();
            }

            @Override
            public void onAccountOpened() {
                log.info(TradeManager.class + ".accountService.onAccountOpened()");
                initPersistedTrades();
            }

            @Override
            public void onAccountClosed() {
                log.info(TradeManager.class + ".accountService.onAccountClosed()");
                closeAllTrades();
            }

            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                // handled in XmrWalletService
            }
        });
    }

    public void onShutDownStarted() {
        log.info("{}.onShutDownStarted()", getClass().getSimpleName());
        isShutDownStarted = true;

        // collect trades to prepare
        List<Trade> trades = getAllTrades();

        // prepare to shut down trades in parallel
        Set<Runnable> tasks = new HashSet<Runnable>();
        for (Trade trade : trades) tasks.add(() -> {
            try {
                trade.onShutDownStarted();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Connection reset")) return; // expected if shut down with ctrl+c
                log.warn("Error notifying {} {} that shut down started: {}\n", trade.getClass().getSimpleName(), trade.getId(), e.getMessage(), e);
            }
        });
        try {
            ThreadUtils.awaitTasks(tasks);
        } catch (Exception e) {
            log.warn("Error notifying trades that shut down started: {}", e.getMessage());
            throw e;
        }
    }

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());
        isShutDown = true;
        closeAllTrades();
    }

    private void closeAllTrades() {

        // collect trades to shutdown
        List<Trade> trades = getAllTrades();

        // shut down trades in parallel
        Set<Runnable> tasks = new HashSet<Runnable>();
        for (Trade trade : trades) tasks.add(() -> {
            try {
                trade.shutDown();
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().contains("Connection reset") || e.getMessage().contains("Connection refused"))) return; // expected if shut down with ctrl+c
                log.warn("Error closing {} {}: {}", trade.getClass().getSimpleName(), trade.getId(), e.getMessage(), e);
            }
        });
        try {
            ThreadUtils.awaitTasks(tasks);
        } catch (Exception e) {
            log.warn("Error shutting down trades: {}\n", e.getMessage(), e);
        }
    }

    public TradeProtocol getTradeProtocol(Trade trade) {
        synchronized (tradeProtocolByTradeId) {
            return tradeProtocolByTradeId.get(trade.getUid());
        }
    }

    public TradeProtocol createTradeProtocol(Trade trade) {
        synchronized (tradeProtocolByTradeId) {
            TradeProtocol tradeProtocol = TradeProtocolFactory.getNewTradeProtocol(trade);
            TradeProtocol prev = tradeProtocolByTradeId.put(trade.getUid(), tradeProtocol);
            if (prev != null) log.error("We had already an entry with uid {}", trade.getUid());
            return tradeProtocol;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Init pending trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void initPersistedTrades() {
        log.info("Initializing persisted trades");

        // initialize off main thread
        new Thread(() -> {

            // get all trades
            List<Trade> trades = getAllTrades();

            // initialize trades in parallel
            int threadPoolSize = 10;
            Set<Runnable> tasks = new HashSet<Runnable>();
            Set<String> uids = new HashSet<String>();
            Set<Trade> tradesToSkip = new HashSet<Trade>();
            Set<Trade> uninitializedTrades = new HashSet<Trade>();
            for (Trade trade : trades) {
                tasks.add(() -> {
                    try {

                        // check for duplicate uid
                        if (!uids.add(trade.getUid())) {
                            log.warn("Found trade with duplicate uid, skipping. That should never happen. {} {}, uid={}", trade.getClass().getSimpleName(), trade.getId(), trade.getUid());
                            tradesToSkip.add(trade);
                            return;
                        }

                        // skip if failed and error handling not scheduled
                        if (failedTradesManager.getObservableList().contains(trade) && !trade.isProtocolErrorHandlingScheduled()) {
                            log.warn("Skipping initialization of failed trade {} {}", trade.getClass().getSimpleName(), trade.getId());
                            tradesToSkip.add(trade);
                            return;
                        }

                        // initialize trade
                        initPersistedTrade(trade);

                        // record if protocol didn't initialize
                        if (!trade.isDepositsPublished()) {
                            uninitializedTrades.add(trade);
                        }
                    } catch (Exception e) {
                        if (!isShutDownStarted) {
                            log.warn("Error initializing {} {}: {}\n", trade.getClass().getSimpleName(), trade.getId(), e.getMessage(), e);
                            trade.setInitError(e);
                            trade.prependErrorMessage(e.getMessage());
                        }
                    }
                });
            };
            ThreadUtils.awaitTasks(tasks, threadPoolSize);
            log.info("Done initializing persisted trades");
            if (isShutDownStarted) return;

            // remove skipped trades
            trades.removeAll(tradesToSkip);

            // sync idle trades once in background after active trades
            for (Trade trade : trades) {
                if (trade.isIdling()) ThreadUtils.submitToPool(() -> trade.syncAndPollWallet());
            }

            // process after all wallets initialized
            if (!HavenoUtils.isSeedNode()) {

                // handle uninitialized trades
                for (Trade trade : uninitializedTrades) {
                    trade.onProtocolError();
                }

                // freeze or thaw outputs
                if (isShutDownStarted) return;
                xmrWalletService.fixReservedOutputs();

                // reset any available funded address entries
                if (isShutDownStarted) return;
                xmrWalletService.getAddressEntriesForAvailableBalanceStream()
                        .filter(addressEntry -> addressEntry.getOfferId() != null)
                        .forEach(addressEntry -> {
                            log.warn("Swapping pending {} entries at startup. offerId={}", addressEntry.getContext(), addressEntry.getOfferId());
                            xmrWalletService.swapAddressEntryToAvailable(addressEntry.getOfferId(), addressEntry.getContext());
                        });

                checkForLockedUpFunds();
            }

            // notify that persisted trades initialized
            if (isShutDownStarted) return;
            persistedTradesInitialized.set(true);
            getObservableList().addListener((ListChangeListener<Trade>) change -> onTradesChanged());
            onTradesChanged();

            // We do not include failed trades as they should not be counted anyway in the trade statistics
            // TODO: remove stats?
            Set<Trade> nonFailedTrades = new HashSet<>(closedTradableManager.getClosedTrades());
            nonFailedTrades.addAll(tradableList.getList());
            String referralId = referralIdService.getOptionalReferralId().orElse(null);
            boolean isTorNetworkNode = p2PService.getNetworkNode() instanceof TorNetworkNode;
            tradeStatisticsManager.maybePublishTradeStatistics(nonFailedTrades, referralId, isTorNetworkNode);
        }).start();

        // allow execution to start
        HavenoUtils.waitFor(100);
    }

    private void initPersistedTrade(Trade trade) {
        if (isShutDown) return;
        if (getTradeProtocol(trade) != null) return;
        initTradeAndProtocol(trade, createTradeProtocol(trade));
        requestPersistence();
    }

    private void initTradeAndProtocol(Trade trade, TradeProtocol tradeProtocol) {
        tradeProtocol.initialize(processModelServiceProvider, this);
        requestPersistence(); // TODO requesting persistence twice with initPersistedTrade()
    }

    public void requestPersistence() {
        persistenceManager.requestPersistence();
    }

    public void persistNow(@Nullable Runnable completeHandler) {
        persistenceManager.persistNow(completeHandler);
    }

    private void handleInitTradeRequest(InitTradeRequest request, NodeAddress sender) {
        log.info("TradeManager handling InitTradeRequest for tradeId={}, sender={}, uid={}", request.getOfferId(), sender, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid InitTradeRequest message " + request.toString());
            return;
        }

        // handle request as maker
        if (request.getMakerNodeAddress().equals(p2PService.getNetworkNode().getNodeAddress())) {

            // get open offer
            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOffer(request.getOfferId());
            if (!openOfferOptional.isPresent()) return;
            OpenOffer openOffer = openOfferOptional.get();
            Offer offer = openOffer.getOffer();

            // check availability
            if (openOffer.getState() != OpenOffer.State.AVAILABLE) {
                log.warn("Ignoring InitTradeRequest to maker because offer is not available, offerId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // validate challenge
            if (openOffer.getChallenge() != null && !HavenoUtils.getChallengeHash(openOffer.getChallenge()).equals(HavenoUtils.getChallengeHash(request.getChallenge()))) {
                log.warn("Ignoring InitTradeRequest to maker because challenge is incorrect, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }
  
            // ensure trade does not already exist
            Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
            if (tradeOptional.isPresent()) {
                log.warn("Ignoring InitTradeRequest to maker because trade already exists with id " + request.getOfferId() + ". This should never happen.");
                return;
            }
  
            // reserve open offer
            openOfferManager.reserveOpenOffer(openOffer);
  
            // initialize trade
            Trade trade;
            if (offer.isBuyOffer())
                trade = new BuyerAsMakerTrade(offer,
                        BigInteger.valueOf(request.getTradeAmount()),
                        offer.getOfferPayload().getPrice(),
                        xmrWalletService,
                        getNewProcessModel(offer),
                        UUID.randomUUID().toString(),
                        request.getMakerNodeAddress(),
                        request.getTakerNodeAddress(),
                        request.getArbitratorNodeAddress(),
                        openOffer.getChallenge());
            else
                trade = new SellerAsMakerTrade(offer,
                        BigInteger.valueOf(request.getTradeAmount()),
                        offer.getOfferPayload().getPrice(),
                        xmrWalletService,
                        getNewProcessModel(offer),
                        UUID.randomUUID().toString(),
                        request.getMakerNodeAddress(),
                        request.getTakerNodeAddress(),
                        request.getArbitratorNodeAddress(),
                        openOffer.getChallenge());
            trade.getMaker().setPaymentAccountId(trade.getOffer().getOfferPayload().getMakerPaymentAccountId());
            trade.getTaker().setPaymentAccountId(request.getTakerPaymentAccountId());
            trade.getMaker().setPubKeyRing(trade.getOffer().getPubKeyRing());
            trade.getTaker().setPubKeyRing(request.getTakerPubKeyRing());
            trade.getSelf().setPaymentAccountId(offer.getOfferPayload().getMakerPaymentAccountId());
            trade.getSelf().setReserveTxHash(openOffer.getReserveTxHash()); // TODO (woodser): initialize in initTradeAndProtocol?
            trade.getSelf().setReserveTxHex(openOffer.getReserveTxHex());
            trade.getSelf().setReserveTxKey(openOffer.getReserveTxKey());
            trade.getSelf().setReserveTxKeyImages(offer.getOfferPayload().getReserveTxKeyImages());
            initTradeAndProtocol(trade, createTradeProtocol(trade));
            addTrade(trade);
  
            // process with protocol
            ((MakerProtocol) getTradeProtocol(trade)).handleInitTradeRequest(request, sender, errorMessage -> {
                log.warn("Maker error during trade initialization: " + errorMessage);
                trade.onProtocolError();
            });
        }

        // handle request as arbitrator
        else if (request.getArbitratorNodeAddress().equals(p2PService.getNetworkNode().getNodeAddress())) {

            // verify this node is registered arbitrator
            Arbitrator thisArbitrator = user.getRegisteredArbitrator();
            NodeAddress thisAddress = p2PService.getNetworkNode().getNodeAddress();
            if (thisArbitrator == null || !thisArbitrator.getNodeAddress().equals(thisAddress)) {
                log.warn("Ignoring InitTradeRequest because we are not an arbitrator, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // get offer associated with trade
            Offer offer = null;
            for (Offer anOffer : offerBookService.getOffers()) {
                if (anOffer.getId().equals(request.getOfferId())) {
                    offer = anOffer;
                }
            }
            if (offer == null) {
                log.warn("Ignoring InitTradeRequest to arbitrator because offer is not on the books, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // verify arbitrator is payload signer unless they are offline
            // TODO (woodser): handle if payload signer differs from current arbitrator (verify signer is offline)

            // verify maker is offer owner
            // TODO (woodser): maker address might change if they disconnect and reconnect, should allow maker address to differ if pubKeyRing is same?
            if (!offer.getOwnerNodeAddress().equals(request.getMakerNodeAddress())) {
                log.warn("Ignoring InitTradeRequest to arbitrator because maker is not offer owner, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // validate challenge hash
            if (offer.getChallengeHash() != null && !offer.getChallengeHash().equals(HavenoUtils.getChallengeHash(request.getChallenge()))) {
                log.warn("Ignoring InitTradeRequest to arbitrator because challenge hash is incorrect, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // handle trade
            Trade trade;
            Optional<Trade> tradeOptional = getOpenTrade(offer.getId());
            if (tradeOptional.isPresent()) {
                trade = tradeOptional.get();

                // verify request is from taker
                if (!sender.equals(request.getTakerNodeAddress())) {
                    if (sender.equals(request.getMakerNodeAddress())) {
                        log.warn("Received InitTradeRequest from maker to arbitrator for trade that is already initializing, tradeId={}, sender={}", request.getOfferId(), sender);
                        sendAckMessage(sender, trade.getMaker().getPubKeyRing(), request, false, "Trade is already initializing for " + getClass().getSimpleName() + " " + trade.getId(), null);
                    } else {
                        log.warn("Ignoring InitTradeRequest from non-taker, tradeId={}, sender={}", request.getOfferId(), sender);
                    }
                    return;
                }
            } else {

                // verify request is from maker
                if (!sender.equals(request.getMakerNodeAddress())) {
                    log.warn("Ignoring InitTradeRequest to arbitrator because request must be from maker when trade is not initialized, tradeId={}, sender={}", request.getOfferId(), sender);
                    return;
                }

                // create arbitrator trade
                trade = new ArbitratorTrade(offer,
                        BigInteger.valueOf(request.getTradeAmount()),
                        offer.getOfferPayload().getPrice(),
                        xmrWalletService,
                        getNewProcessModel(offer),
                        UUID.randomUUID().toString(),
                        request.getMakerNodeAddress(),
                        request.getTakerNodeAddress(),
                        request.getArbitratorNodeAddress(),
                        request.getChallenge());

                // set reserve tx hash if available
                Optional<SignedOffer> signedOfferOptional = openOfferManager.getSignedOfferById(request.getOfferId());
                if (signedOfferOptional.isPresent()) {
                    SignedOffer signedOffer = signedOfferOptional.get();
                    trade.getMaker().setReserveTxHash(signedOffer.getReserveTxHash());
                }

                // initialize trade protocol
                initTradeAndProtocol(trade, createTradeProtocol(trade));
                addTrade(trade);
            }

            // process with protocol
            ((ArbitratorProtocol) getTradeProtocol(trade)).handleInitTradeRequest(request, sender, errorMessage -> {
                log.warn("Arbitrator error during trade initialization for trade {}: {}", trade.getId(), errorMessage);
                trade.onProtocolError();
            });

            requestPersistence();
        }

        // handle request as taker
        else if (request.getTakerNodeAddress().equals(p2PService.getNetworkNode().getNodeAddress())) {

            // verify request is from arbitrator
            Arbitrator arbitrator = user.getAcceptedArbitratorByAddress(sender);
            if (arbitrator == null) {
                log.warn("Ignoring InitTradeRequest to taker because request is not from accepted arbitrator, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }

            // get trade
            Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
            if (!tradeOptional.isPresent()) {
                log.warn("Ignoring InitTradeRequest to taker because trade is not initialized, tradeId={}, sender={}", request.getOfferId(), sender);
                return;
            }
            Trade trade = tradeOptional.get();

            // process with protocol
            ((TakerProtocol) getTradeProtocol(trade)).handleInitTradeRequest(request, sender);
        }
        
        // invalid sender
        else {
            log.warn("Ignoring InitTradeRequest because sender is not maker, arbitrator, or taker, tradeId={}, sender={}", request.getOfferId(), sender);
            return;
        }
    }

    private void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        log.info("TradeManager handling InitMultisigRequest for tradeId={}, sender={}, uid={}", request.getOfferId(), sender, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid InitMultisigRequest " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getOfferId() + " at node " + P2PService.getMyNodeAddress());
            return;
        }
        Trade trade = tradeOptional.get();
        getTradeProtocol(trade).handleInitMultisigRequest(request, sender);
    }

    private void handleSignContractRequest(SignContractRequest request, NodeAddress sender) {
        log.info("TradeManager handling SignContractRequest for tradeId={}, sender={}, uid={}", request.getOfferId(), sender, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid SignContractRequest message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getOfferId());
            return;
        }
        Trade trade = tradeOptional.get();
        getTradeProtocol(trade).handleSignContractRequest(request, sender);
    }

    private void handleSignContractResponse(SignContractResponse request, NodeAddress sender) {
        log.info("TradeManager handling SignContractResponse for tradeId={}, sender={}, uid={}", request.getOfferId(), sender, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid SignContractResponse message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getOfferId());
            return;
        }
        Trade trade = tradeOptional.get();
        ((TraderProtocol) getTradeProtocol(trade)).handleSignContractResponse(request, sender);
    }

    private void handleDepositRequest(DepositRequest request, NodeAddress sender) {
        log.info("TradeManager handling DepositRequest for tradeId={}, sender={}, uid={}", request.getOfferId(), sender, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid DepositRequest message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getOfferId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getOfferId());
            return;
        }
        Trade trade = tradeOptional.get();
        ((ArbitratorProtocol) getTradeProtocol(trade)).handleDepositRequest(request, sender);
    }

    private void handleDepositResponse(DepositResponse response, NodeAddress sender) {
        log.info("TradeManager handling DepositResponse for tradeId={}, sender={}, uid={}", response.getOfferId(), sender, response.getUid());

        try {
            Validator.nonEmptyStringOf(response.getOfferId());
        } catch (Throwable t) {
            log.warn("Invalid DepositResponse message " + response.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(response.getOfferId());
        if (!tradeOptional.isPresent()) {
            tradeOptional = getFailedTrade(response.getOfferId());
            if (!tradeOptional.isPresent()) {
                log.warn("No trade with id " + response.getOfferId());
                return;
            }
        }
        Trade trade = tradeOptional.get();
        ((TraderProtocol) getTradeProtocol(trade)).handleDepositResponse(response, sender);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer,
                                       boolean isTakerApiUser,
                                       String paymentAccountId,
                                       BigInteger tradeAmount,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        offer.checkOfferAvailability(getOfferAvailabilityModel(offer, isTakerApiUser, paymentAccountId, tradeAmount), resultHandler, errorMessageHandler);
    }

    // First we check if offer is still available then we create the trade with the protocol
    public void onTakeOffer(BigInteger amount,
                            BigInteger fundsNeededForTrade,
                            Offer offer,
                            String paymentAccountId,
                            boolean useSavingsWallet,
                            boolean isTakerApiUser,
                            TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
        ThreadUtils.execute(() -> {
            checkArgument(!wasOfferAlreadyUsedInTrade(offer.getId()));

            // validate inputs
            if (amount.compareTo(offer.getAmount()) > 0) throw new RuntimeException("Trade amount exceeds offer amount");
            if (amount.compareTo(offer.getMinAmount()) < 0) throw new RuntimeException("Trade amount is less than minimum offer amount");
    
            // ensure trade is not already open
            Optional<Trade> tradeOptional = getOpenTrade(offer.getId());
            if (tradeOptional.isPresent()) throw new RuntimeException("Cannot create trade protocol because trade with ID " + offer.getId() + " is already open");
    
            // create trade
            Trade trade;
            if (offer.isBuyOffer()) {
                trade = new SellerAsTakerTrade(offer,
                        amount,
                        offer.getPrice().getValue(),
                        xmrWalletService,
                        getNewProcessModel(offer),
                        UUID.randomUUID().toString(),
                        offer.getMakerNodeAddress(),
                        P2PService.getMyNodeAddress(),
                        null,
                        offer.getChallenge());
            } else {
                trade = new BuyerAsTakerTrade(offer,
                        amount,
                        offer.getPrice().getValue(),
                        xmrWalletService,
                        getNewProcessModel(offer),
                        UUID.randomUUID().toString(),
                        offer.getMakerNodeAddress(),
                        P2PService.getMyNodeAddress(),
                        null,
                        offer.getChallenge());
            }
            trade.getProcessModel().setUseSavingsWallet(useSavingsWallet);
            trade.getProcessModel().setFundsNeededForTrade(fundsNeededForTrade.longValueExact());
            trade.getMaker().setPaymentAccountId(offer.getOfferPayload().getMakerPaymentAccountId());
            trade.getMaker().setPubKeyRing(offer.getPubKeyRing());
            trade.getSelf().setPubKeyRing(keyRing.getPubKeyRing());
            trade.getSelf().setPaymentAccountId(paymentAccountId);
            trade.getSelf().setPaymentMethodId(user.getPaymentAccount(paymentAccountId).getPaymentAccountPayload().getPaymentMethodId());
    
            // initialize trade protocol
            TradeProtocol tradeProtocol = createTradeProtocol(trade);
            addTrade(trade);
    
            initTradeAndProtocol(trade, tradeProtocol);
            trade.addInitProgressStep();
    
            // process with protocol
            ((TakerProtocol) tradeProtocol).onTakeOffer(result -> {
                tradeResultHandler.handleResult(trade);
                requestPersistence();
            }, errorMessage -> {
                log.warn("Taker error during trade initialization: " + errorMessage);
                trade.onProtocolError();
                xmrWalletService.resetAddressEntriesForOpenOffer(trade.getId()); // TODO: move this into protocol error handling
                errorMessageHandler.handleErrorMessage(errorMessage);
            });
    
            requestPersistence();
        }, offer.getId());
    }

    private ProcessModel getNewProcessModel(Offer offer) {
        return new ProcessModel(checkNotNull(offer).getId(),
                processModelServiceProvider.getUser().getAccountId(),
                processModelServiceProvider.getKeyRing().getPubKeyRing());
    }

    private OfferAvailabilityModel getOfferAvailabilityModel(Offer offer, boolean isTakerApiUser, String paymentAccountId, BigInteger tradeAmount) {
        return new OfferAvailabilityModel(
                offer,
                keyRing.getPubKeyRing(),
                xmrWalletService,
                p2PService,
                user,
                mediatorManager,
                tradeStatisticsManager,
                isTakerApiUser,
                paymentAccountId,
                tradeAmount,
                offerUtil);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Complete trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): remove this function
    public void onWithdrawRequest(String toAddress,
          Coin amount,
          Coin fee,
          KeyParameter aesKey,
          Trade trade,
          @Nullable String memo,
          ResultHandler resultHandler,
          FaultHandler faultHandler) {
        throw new RuntimeException("Withdraw trade funds after payout to Haveno wallet not supported");
    }

    // If trade was completed (closed without fault but might be closed by a dispute) we move it to the closed trades
    public void onTradeCompleted(Trade trade) {
        if (trade.isCompleted()) throw new RuntimeException("Trade " + trade.getId() + " was already completed");
        closedTradableManager.add(trade);
        trade.setCompleted(true);
        removeTrade(trade);
        xmrWalletService.swapPayoutAddressEntryToAvailable(trade.getId()); // TODO The address entry should have been removed already. Check and if its the case remove that.
        requestPersistence();
    }

    public void unregisterTrade(Trade trade) {
        log.warn("Unregistering {} {}", trade.getClass().getSimpleName(), trade.getId());
        removeTrade(trade);
        removeFailedTrade(trade);
        if (!trade.isMaker()) xmrWalletService.swapPayoutAddressEntryToAvailable(trade.getId()); // TODO The address entry should have been removed already. Check and if its the case remove that.
        requestPersistence();
    }

    public void removeTrade(Trade trade) {
        log.info("TradeManager.removeTrade() " + trade.getId());
        
        // remove trade
        synchronized (tradableList.getList()) {
            if (!tradableList.remove(trade)) return;
        }
        requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void closeDisputedTrade(String tradeId, DisputeState disputeState) {
        Optional<Trade> tradeOptional = getOpenTrade(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            trade.setDisputeState(disputeState);
            xmrWalletService.swapPayoutAddressEntryToAvailable(trade.getId());
            requestPersistence();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade period state
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void applyTradePeriodState() {
        updateTradePeriodState();
        clockWatcher.addListener(new ClockWatcher.Listener() {
            @Override
            public void onSecondTick() {
            }

            @Override
            public void onMinuteTick() {
                updateTradePeriodState();
            }
        });
    }

    private void updateTradePeriodState() {
        if (isShutDownStarted) return;
        synchronized (tradableList.getList()) {
            for (Trade trade : tradableList.getList()) {
                if (!trade.isInitialized() || trade.isPayoutPublished()) continue;
                Date maxTradePeriodDate = trade.getMaxTradePeriodDate();
                Date halfTradePeriodDate = trade.getHalfTradePeriodDate();
                if (maxTradePeriodDate != null && halfTradePeriodDate != null) {
                    Date now = new Date();
                    if (now.after(maxTradePeriodDate)) {
                        trade.setPeriodState(Trade.TradePeriodState.TRADE_PERIOD_OVER);
                        requestPersistence();
                    } else if (now.after(halfTradePeriodDate)) {
                        trade.setPeriodState(Trade.TradePeriodState.SECOND_HALF);
                        requestPersistence();
                    }
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Failed trade handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // If trade is in already in critical state (if taker role: taker fee; both roles: after deposit published)
    // we move the trade to FailedTradesManager
    public void onMoveInvalidTradeToFailedTrades(Trade trade) {
        failedTradesManager.add(trade);
        removeTrade(trade);
    }

    public void onMoveFailedTradeToPendingTrades(Trade trade) {
        addTradeToPendingTrades(trade);
        failedTradesManager.removeTrade(trade);
    }

    public void onMoveClosedTradeToPendingTrades(Trade trade) {
        trade.setCompleted(false);
        addTradeToPendingTrades(trade);
        closedTradableManager.removeTrade(trade);
    }

    private void removeFailedTrade(Trade trade) {
        failedTradesManager.removeTrade(trade);
    }

    private void addTradeToPendingTrades(Trade trade) {
        if (!trade.isInitialized()) {
            try {
                initPersistedTrade(trade);
            } catch (Exception e) {
                log.warn("Error initializing {} {} on move to pending trades", trade.getClass().getSimpleName(), trade.getShortId(), e);
            }
        }
        addTrade(trade);
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        synchronized (tradableList.getList()) {
            return getObservableList().stream().filter(Trade::isFundsLockedIn);
        }
    }

    private void checkForLockedUpFunds() {
        try {
            getSetOfFailedOrClosedTradeIdsFromLockedInFunds();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Set<String> getSetOfFailedOrClosedTradeIdsFromLockedInFunds() throws TradeTxException {
        AtomicReference<TradeTxException> tradeTxException = new AtomicReference<>();
        synchronized (tradableList.getList()) {
            Set<String> tradesIdSet = getTradesStreamWithFundsLockedIn()
                    .filter(Trade::hasFailed)
                    .map(Trade::getId)
                    .collect(Collectors.toSet());
            tradesIdSet.addAll(failedTradesManager.getTradesStreamWithFundsLockedIn()
                    .filter(trade -> trade.getMakerDepositTx() != null || trade.getTakerDepositTx() != null)
                    .map(trade -> {
                        log.warn("We found a failed trade with locked up funds. " +
                                "That should never happen. trade ID=" + trade.getId());
                        return trade.getId();
                    })
                    .collect(Collectors.toSet()));
            tradesIdSet.addAll(closedTradableManager.getTradesStreamWithFundsLockedIn()
                    .map(trade -> {
                        MoneroTx makerDepositTx = trade.getMakerDepositTx();
                        if (makerDepositTx != null) {
                            if (!makerDepositTx.isConfirmed()) {
                                tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithUnconfirmedDepositTx", trade.getShortId()))); // TODO (woodser): rename to closedTradeWithLockedDepositTx
                            } else {
                                log.warn("We found a closed trade with locked up funds. " +
                                        "That should never happen. {} ID={}, state={}, payoutState={}, disputeState={}", trade.getClass().getSimpleName(), trade.getId(), trade.getState(), trade.getPayoutState(), trade.getDisputeState());
                            }
                        } else {
                            log.warn("Closed trade with locked up funds missing maker deposit tx. {} ID={}, state={}, payoutState={}, disputeState={}", trade.getClass().getSimpleName(), trade.getId(), trade.getState(), trade.getPayoutState(), trade.getDisputeState());
                            tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithNoDepositTx", trade.getShortId())));
                        }

                        MoneroTx takerDepositTx = trade.getTakerDepositTx();
                        if (takerDepositTx != null) {
                            if (!takerDepositTx.isConfirmed()) {
                                tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithUnconfirmedDepositTx", trade.getShortId())));
                            } else {
                                log.warn("We found a closed trade with locked up funds. " +
                                        "That should never happen. trade ID={} ID={}, state={}, payoutState={}, disputeState={}", trade.getClass().getSimpleName(), trade.getId(), trade.getState(), trade.getPayoutState(), trade.getDisputeState());
                            }
                        } else if (!trade.hasBuyerAsTakerWithoutDeposit()) {
                            log.warn("Closed trade with locked up funds missing taker deposit tx. {} ID={}, state={}, payoutState={}, disputeState={}", trade.getClass().getSimpleName(), trade.getId(), trade.getState(), trade.getPayoutState(), trade.getDisputeState());
                            tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithNoDepositTx", trade.getShortId())));
                        }
                        return trade.getId();
                    })
                    .collect(Collectors.toSet()));

            if (tradeTxException.get() != null)
                throw tradeTxException.get();

            return tradesIdSet;
        }
    }

    // If trade still has funds locked up it might come back from failed trades
    // Aborts unfailing if the address entries needed are not available
    private boolean unFailTrade(Trade trade) {
        if (!recoverAddresses(trade)) {
            log.warn("Failed to recover address during unFail trade");
            return false;
        }

        initPersistedTrade(trade);

        UserThread.execute(() -> {
            synchronized (tradableList.getList()) {
                if (!tradableList.contains(trade)) {
                    tradableList.add(trade);
                }
            }
        });

        return true;
    }

    // The trade is added to pending trades if the associated address entries are AVAILABLE and
    // the relevant entries are changed, otherwise it's not added and no address entries are changed
    private boolean recoverAddresses(Trade trade) {
        // Find addresses associated with this trade.
        var entries = tradeUtil.getAvailableAddresses(trade);
        if (entries == null)
            return false;

        xmrWalletService.recoverAddressEntry(trade.getId(), entries.second,
                XmrAddressEntry.Context.TRADE_PAYOUT);
        return true;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters, Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendAckMessage(NodeAddress peer, PubKeyRing peersPubKeyRing, TradeMessage message, boolean result, @Nullable String errorMessage, String updatedMultisigHex) {

        // create ack message
        String tradeId = message.getOfferId();
        String sourceUid = message.getUid();
        AckMessage ackMessage = new AckMessage(P2PService.getMyNodeAddress(),
                AckMessageSourceType.TRADE_MESSAGE,
                message.getClass().getSimpleName(),
                sourceUid,
                tradeId,
                result,
                errorMessage,
                updatedMultisigHex);

        // send ack message
        if (errorMessage != null) {
            log.warn("Sending NACK for {} to peer {}. tradeId={}, sourceUid={}, errorMessage={}, updatedMultisigHex={}",
                    ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid, errorMessage, updatedMultisigHex == null ? "null" : updatedMultisigHex.length() + " characters");
        } else {
            log.info("Sending AckMessage for {} to peer {}. tradeId={}, sourceUid={}",
                    ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
        }
        p2PService.getMailboxMessageService().sendEncryptedMailboxMessage(
                peer,
                peersPubKeyRing,
                ackMessage,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for {} arrived at peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("AckMessage for {} stored in mailbox for peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for {} failed. Peer {}. tradeId={}, sourceUid={}, errorMessage={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid, errorMessage);
                    }
                }
        );
    }

    public ObservableList<Trade> getObservableList() {
        synchronized (tradableList.getList()) {
            return tradableList.getObservableList();
        }
    }

    public BooleanProperty persistedTradesInitializedProperty() {
        return persistedTradesInitialized;
    }

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public boolean wasOfferAlreadyUsedInTrade(String offerId) {
        return getOpenTrade(offerId).isPresent() ||
                failedTradesManager.getTradeById(offerId).isPresent() ||
                closedTradableManager.getTradableById(offerId).isPresent();
    }

    public boolean isBuyer(Offer offer) {
        // If I am the maker, we use the OfferDirection, otherwise the mirrored direction
        if (isMyOffer(offer))
            return offer.isBuyOffer();
        else
            return offer.getDirection() == OfferDirection.SELL;
    }

    // TODO: make Optional<Trade> versus Trade return types consistent
    public Trade getTrade(String tradeId) {
        return getOpenTrade(tradeId).orElseGet(() -> getClosedTrade(tradeId).orElseGet(() -> getFailedTrade(tradeId).orElseGet(() -> null)));
    }

    public boolean hasTrade(String tradeId) {
        return getTrade(tradeId) != null;
    }

    public Optional<Trade> getOpenTrade(String tradeId) {
        synchronized (tradableList.getList()) {
            return tradableList.stream().filter(e -> e.getId().equals(tradeId)).findFirst();
        }
    }

    public boolean hasOpenTrade(Trade trade) {
        synchronized (tradableList.getList()) {
            return tradableList.contains(trade);
        }
    }

    public boolean hasFailedScheduledTrade(String offerId) {
        return failedTradesManager.getTradeById(offerId).isPresent() && failedTradesManager.getTradeById(offerId).get().isProtocolErrorHandlingScheduled();
    }

    public Optional<Trade> getOpenTradeByUid(String tradeUid) {
        synchronized (tradableList.getList()) {
            return tradableList.stream().filter(e -> e.getUid().equals(tradeUid)).findFirst();
        }
    }

    public List<Trade> getAllTrades() {
        synchronized (tradableList.getList()) {
            List<Trade> trades = new ArrayList<Trade>();
            synchronized (tradableList.getList()) {
                trades.addAll(tradableList.getList());
            }
            trades.addAll(closedTradableManager.getClosedTrades());
            trades.addAll(failedTradesManager.getObservableList());
            return trades;
        }
    }

    public List<Trade> getOpenTrades() {
        synchronized (tradableList.getList()) {
            return ImmutableList.copyOf(getObservableList().stream()
                    .filter(e -> e instanceof Trade)
                    .map(e -> e)
                    .collect(Collectors.toList()));
        }
    }

    public List<Trade> getClosedTrades() {
        return closedTradableManager.getClosedTrades();
    }

    public Optional<Trade> getClosedTrade(String tradeId) {
        return closedTradableManager.getTradeById(tradeId);
    }

    public Optional<Trade> getFailedTrade(String tradeId) {
        return failedTradesManager.getTradeById(tradeId);
    }

    private void addTrade(Trade trade) {
        synchronized (tradableList.getList()) {
            if (tradableList.add(trade)) {
                requestPersistence();
            }
        }
    }

    // TODO Remove once tradableList is refactored to a final field
    //  (part of the persistence refactor PR)
    private void onTradesChanged() {
        this.numPendingTrades.set(getObservableList().size());
    }
}
