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
import common.utils.GenUtils;
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
import haveno.core.trade.Trade.Phase;
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
import monero.daemon.model.MoneroTx;
import org.bitcoinj.core.Coin;
import org.bouncycastle.crypto.params.KeyParameter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
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

        xmrWalletService.setTradeManager(this);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
                    tradableList.setAll(persisted.getList());
                    tradableList.stream()
                            .filter(trade -> trade.getOffer() != null)
                            .forEach(trade -> trade.getOffer().setPriceFeedService(priceFeedService));
                    completeHandler.run();
                },
                completeHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey message, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = message.getNetworkEnvelope();
        if (!(networkEnvelope instanceof TradeMessage)) return;
        String tradeId = ((TradeMessage) networkEnvelope).getTradeId();
        ThreadUtils.execute(() -> {
            if (networkEnvelope instanceof InitTradeRequest) {
                handleInitTradeRequest((InitTradeRequest) networkEnvelope, peer);
            } else if (networkEnvelope instanceof InitMultisigRequest) {
                handleInitMultisigRequest((InitMultisigRequest) networkEnvelope, peer);
            } else if (networkEnvelope instanceof SignContractRequest) {
                handleSignContractRequest((SignContractRequest) networkEnvelope, peer);
            } else if (networkEnvelope instanceof SignContractResponse) {
                handleSignContractResponse((SignContractResponse) networkEnvelope, peer);
            } else if (networkEnvelope instanceof DepositRequest) {
                handleDepositRequest((DepositRequest) networkEnvelope, peer);
            } else if (networkEnvelope instanceof DepositResponse) {
                handleDepositResponse((DepositResponse) networkEnvelope, peer);
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
                public void onUpdatedDataReceived() {
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
                log.warn("Error notifying {} {} that shut down started {}", getClass().getSimpleName(), trade.getId());
                e.printStackTrace();
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
                log.warn("Error closing {} {}", trade.getClass().getSimpleName(), trade.getId());
                e.printStackTrace();
            }
        });
        try {
            ThreadUtils.awaitTasks(tasks);
        } catch (Exception e) {
            log.warn("Error shutting down trades: {}", e.getMessage());
            e.printStackTrace();
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
            Set<Trade> tradesToMaybeRemoveOnError = new HashSet<Trade>();
            for (Trade trade : trades) {
                tasks.add(() -> {
                    try {

                        // check for duplicate uid
                        if (!uids.add(trade.getUid())) {
                            log.warn("Found trade with duplicate uid, skipping. That should never happen. {} {}, uid={}", trade.getClass().getSimpleName(), trade.getId(), trade.getUid());
                            tradesToSkip.add(trade);
                            return;
                        }

                        // initialize trade
                        initPersistedTrade(trade);

                        // remove trade if protocol didn't initialize
                        if (getOpenTradeByUid(trade.getUid()).isPresent() && !trade.isDepositsPublished()) {
                            tradesToMaybeRemoveOnError.add(trade);
                        }
                    } catch (Exception e) {
                        if (!isShutDownStarted) {
                            e.printStackTrace();
                            log.warn("Error initializing {} {}: {}", trade.getClass().getSimpleName(), trade.getId(), e.getMessage());
                            trade.setInitError(e);
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

                // maybe remove trades on error
                for (Trade trade : tradesToMaybeRemoveOnError) {
                    maybeRemoveTradeOnError(trade);
                }

                // thaw unreserved outputs
                xmrWalletService.thawUnreservedOutputs();

                // reset any available funded address entries
                if (isShutDownStarted) return;
                xmrWalletService.getAddressEntriesForAvailableBalanceStream()
                        .filter(addressEntry -> addressEntry.getOfferId() != null)
                        .forEach(addressEntry -> {
                            log.warn("Swapping pending {} entries at startup. offerId={}", addressEntry.getContext(), addressEntry.getOfferId());
                            xmrWalletService.swapAddressEntryToAvailable(addressEntry.getOfferId(), addressEntry.getContext());
                        });
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
            tradeStatisticsManager.maybeRepublishTradeStatistics(nonFailedTrades, referralId, isTorNetworkNode);
        }).start();

        // allow execution to start
        GenUtils.waitFor(100);
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

    private void handleInitTradeRequest(InitTradeRequest request, NodeAddress sender) {
      log.info("Received InitTradeRequest from {} with tradeId {} and uid {}", sender, request.getTradeId(), request.getUid());

      try {
          Validator.nonEmptyStringOf(request.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + request.toString());
          return;
      }

      // handle request as arbitrator
      boolean isArbitrator = request.getArbitratorNodeAddress().equals(p2PService.getNetworkNode().getNodeAddress());
      if (isArbitrator) {

        // verify this node is registered arbitrator
        Arbitrator thisArbitrator = user.getRegisteredArbitrator();
        NodeAddress thisAddress = p2PService.getNetworkNode().getNodeAddress();
        if (thisArbitrator == null || !thisArbitrator.getNodeAddress().equals(thisAddress)) {
            log.warn("Ignoring InitTradeRequest from {} with tradeId {} because we are not an arbitrator", sender, request.getTradeId());
            return;
        }

        // get offer associated with trade
        Offer offer = null;
        for (Offer anOffer : offerBookService.getOffers()) {
            if (anOffer.getId().equals(request.getTradeId())) {
                offer = anOffer;
            }
        }
        if (offer == null) {
            log.warn("Ignoring InitTradeRequest from {} with tradeId {} because offer is not on the books", sender, request.getTradeId());
            return;
        }

        // verify arbitrator is payload signer unless they are offline
        // TODO (woodser): handle if payload signer differs from current arbitrator (verify signer is offline)

        // verify maker is offer owner
        // TODO (woodser): maker address might change if they disconnect and reconnect, should allow maker address to differ if pubKeyRing is same?
        if (!offer.getOwnerNodeAddress().equals(request.getMakerNodeAddress())) {
            log.warn("Ignoring InitTradeRequest from {} with tradeId {} because maker is not offer owner", sender, request.getTradeId());
            return;
        }

        // handle trade
        Trade trade;
        Optional<Trade> tradeOptional = getOpenTrade(offer.getId());
        if (tradeOptional.isPresent()) {
            trade = tradeOptional.get();

            // verify request is from maker
            if (!sender.equals(request.getMakerNodeAddress())) {

                // send nack if trade already taken
                String errMsg = "Trade is already taken, tradeId=" + request.getTradeId();
                log.warn(errMsg);
                sendAckMessage(sender, request.getPubKeyRing(), request, false, errMsg);
                return;
            }
        } else {

            // verify request is from taker
            if (!sender.equals(request.getTakerNodeAddress())) {
                log.warn("Ignoring InitTradeRequest from {} with tradeId {} because request must be from taker when trade is not initialized", sender, request.getTradeId());
                return;
            }

            // get expected taker fee
            BigInteger takerFee = HavenoUtils.getTakerFee(BigInteger.valueOf(request.getTradeAmount()));

            // create arbitrator trade
            trade = new ArbitratorTrade(offer,
                    BigInteger.valueOf(request.getTradeAmount()),
                    takerFee,
                    offer.getOfferPayload().getPrice(),
                    xmrWalletService,
                    getNewProcessModel(offer),
                    UUID.randomUUID().toString(),
                    request.getMakerNodeAddress(),
                    request.getTakerNodeAddress(),
                    request.getArbitratorNodeAddress());

            // set reserve tx hash if available
            Optional<SignedOffer> signedOfferOptional = openOfferManager.getSignedOfferById(request.getTradeId());
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
              maybeRemoveTradeOnError(trade);
          });

          requestPersistence();
      }

      // handle request as maker
      else {

          Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(request.getTradeId());
          if (!openOfferOptional.isPresent()) {
              return;
          }

          OpenOffer openOffer = openOfferOptional.get();
          if (openOffer.getState() != OpenOffer.State.AVAILABLE) {
              return;
          }

          Offer offer = openOffer.getOffer();

          // verify request is from arbitrator
          Arbitrator arbitrator = user.getAcceptedArbitratorByAddress(sender);
          if (arbitrator == null) {
              log.warn("Ignoring InitTradeRequest from {} with tradeId {} because request is not from accepted arbitrator", sender, request.getTradeId());
              return;
          }

          Optional<Trade> tradeOptional = getOpenTrade(request.getTradeId());
          if (tradeOptional.isPresent()) {
              log.warn("Maker trade already exists with id " + request.getTradeId() + ". This should never happen.");
              return;
          }

          // reserve open offer
          openOfferManager.reserveOpenOffer(openOffer);

          // get expected taker fee
          BigInteger takerFee = HavenoUtils.getTakerFee(BigInteger.valueOf(request.getTradeAmount()));

          // initialize trade
          Trade trade;
          if (offer.isBuyOffer())
              trade = new BuyerAsMakerTrade(offer,
                      BigInteger.valueOf(request.getTradeAmount()),
                      takerFee,
                      offer.getOfferPayload().getPrice(),
                      xmrWalletService,
                      getNewProcessModel(offer),
                      UUID.randomUUID().toString(),
                      request.getMakerNodeAddress(),
                      request.getTakerNodeAddress(),
                      request.getArbitratorNodeAddress());
          else
              trade = new SellerAsMakerTrade(offer,
                      BigInteger.valueOf(request.getTradeAmount()),
                      takerFee,
                      offer.getOfferPayload().getPrice(),
                      xmrWalletService,
                      getNewProcessModel(offer),
                      UUID.randomUUID().toString(),
                      request.getMakerNodeAddress(),
                      request.getTakerNodeAddress(),
                      request.getArbitratorNodeAddress());

          trade.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
          trade.getMaker().setPubKeyRing(trade.getOffer().getPubKeyRing());
          initTradeAndProtocol(trade, createTradeProtocol(trade));
          trade.getSelf().setPaymentAccountId(offer.getOfferPayload().getMakerPaymentAccountId());
          trade.getSelf().setReserveTxHash(openOffer.getReserveTxHash()); // TODO (woodser): initialize in initTradeAndProtocol?
          trade.getSelf().setReserveTxHex(openOffer.getReserveTxHex());
          trade.getSelf().setReserveTxKey(openOffer.getReserveTxKey());
          trade.getSelf().setReserveTxKeyImages(offer.getOfferPayload().getReserveTxKeyImages());
          addTrade(trade);

          // notify on phase changes
          // TODO (woodser): save subscription, bind on startup
          EasyBind.subscribe(trade.statePhaseProperty(), phase -> {
              if (phase == Phase.DEPOSITS_PUBLISHED) {
                  notificationService.sendTradeNotification(trade, "Offer Taken", "Your offer " + offer.getId() + " has been accepted"); // TODO (woodser): use language translation
              }
          });

          // process with protocol
          ((MakerProtocol) getTradeProtocol(trade)).handleInitTradeRequest(request, sender, errorMessage -> {
              log.warn("Maker error during trade initialization: " + errorMessage);
              maybeRemoveTradeOnError(trade);
          });
      }
    }

    private void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress peer) {
        log.info("Received {} for trade {} from {} with uid {}", request.getClass().getSimpleName(), request.getTradeId(), peer, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getTradeId());
        } catch (Throwable t) {
            log.warn("Invalid InitMultisigRequest " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getTradeId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getTradeId() + " at node " + P2PService.getMyNodeAddress());
            return;
        }
        Trade trade = tradeOptional.get();
        getTradeProtocol(trade).handleInitMultisigRequest(request, peer);
    }

    private void handleSignContractRequest(SignContractRequest request, NodeAddress peer) {
        log.info("Received {} for trade {} from {} with uid {}", request.getClass().getSimpleName(), request.getTradeId(), peer, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getTradeId());
        } catch (Throwable t) {
            log.warn("Invalid SignContractRequest message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getTradeId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getTradeId());
            return;
        }
        Trade trade = tradeOptional.get();
        getTradeProtocol(trade).handleSignContractRequest(request, peer);
    }

    private void handleSignContractResponse(SignContractResponse request, NodeAddress peer) {
        log.info("Received {} for trade {} from {} with uid {}", request.getClass().getSimpleName(), request.getTradeId(), peer, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getTradeId());
        } catch (Throwable t) {
            log.warn("Invalid SignContractResponse message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getTradeId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getTradeId());
            return;
        }
        Trade trade = tradeOptional.get();
        ((TraderProtocol) getTradeProtocol(trade)).handleSignContractResponse(request, peer);
    }

    private void handleDepositRequest(DepositRequest request, NodeAddress peer) {
        log.info("Received {} for trade {} from {} with uid {}", request.getClass().getSimpleName(), request.getTradeId(), peer, request.getUid());

        try {
            Validator.nonEmptyStringOf(request.getTradeId());
        } catch (Throwable t) {
            log.warn("Invalid DepositRequest message " + request.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(request.getTradeId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + request.getTradeId());
            return;
        }
        Trade trade = tradeOptional.get();
        ((ArbitratorProtocol) getTradeProtocol(trade)).handleDepositRequest(request, peer);
    }

    private void handleDepositResponse(DepositResponse response, NodeAddress peer) {
        log.info("Received {} for trade {} from {} with uid {}", response.getClass().getSimpleName(), response.getTradeId(), peer, response.getUid());

        try {
            Validator.nonEmptyStringOf(response.getTradeId());
        } catch (Throwable t) {
            log.warn("Invalid DepositResponse message " + response.toString());
            return;
        }

        Optional<Trade> tradeOptional = getOpenTrade(response.getTradeId());
        if (!tradeOptional.isPresent()) {
            log.warn("No trade with id " + response.getTradeId());
            return;
        }
        Trade trade = tradeOptional.get();
        ((TraderProtocol) getTradeProtocol(trade)).handleDepositResponse(response, peer);
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
                            BigInteger takerFee,
                            BigInteger fundsNeededForTrade,
                            Offer offer,
                            String paymentAccountId,
                            boolean useSavingsWallet,
                            boolean isTakerApiUser,
                            TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {

        checkArgument(!wasOfferAlreadyUsedInTrade(offer.getId()));

        // validate inputs
        if (amount.compareTo(offer.getAmount()) > 0) throw new RuntimeException("Trade amount exceeds offer amount");
        if (amount.compareTo(offer.getMinAmount()) < 0) throw new RuntimeException("Trade amount is less than minimum offer amount");

        OfferAvailabilityModel model = getOfferAvailabilityModel(offer, isTakerApiUser, paymentAccountId, amount);
        offer.checkOfferAvailability(model,
                () -> {
                    if (offer.getState() == Offer.State.AVAILABLE) {
                        Trade trade;
                        if (offer.isBuyOffer()) {
                            trade = new SellerAsTakerTrade(offer,
                                    amount,
                                    takerFee,
                                    model.getTradeRequest().getTradePrice(),
                                    xmrWalletService,
                                    getNewProcessModel(offer),
                                    UUID.randomUUID().toString(),
                                    model.getPeerNodeAddress(),
                                    P2PService.getMyNodeAddress(),
                                    offer.getOfferPayload().getArbitratorSigner());
                        } else {
                            trade = new BuyerAsTakerTrade(offer,
                                    amount,
                                    takerFee,
                                    model.getTradeRequest().getTradePrice(),
                                    xmrWalletService,
                                    getNewProcessModel(offer),
                                    UUID.randomUUID().toString(),
                                    model.getPeerNodeAddress(),
                                    P2PService.getMyNodeAddress(),
                                    offer.getOfferPayload().getArbitratorSigner());
                        }

                        trade.getProcessModel().setTradeMessage(model.getTradeRequest());
                        trade.getProcessModel().setMakerSignature(model.getMakerSignature());
                        trade.getProcessModel().setUseSavingsWallet(useSavingsWallet);
                        trade.getProcessModel().setFundsNeededForTrade(fundsNeededForTrade.longValueExact());
                        trade.getMaker().setPubKeyRing(trade.getOffer().getPubKeyRing());
                        trade.getSelf().setPubKeyRing(model.getPubKeyRing());
                        trade.getSelf().setPaymentAccountId(paymentAccountId);
                        trade.addInitProgressStep();

                        // ensure trade is not already open
                        Optional<Trade> tradeOptional = getOpenTrade(offer.getId());
                        if (tradeOptional.isPresent()) throw new RuntimeException("Cannot create trade protocol because trade with ID " + trade.getId() + " is already open");

                        // initialize trade protocol
                        TradeProtocol tradeProtocol = createTradeProtocol(trade);
                        addTrade(trade);

                        initTradeAndProtocol(trade, tradeProtocol);

                        // process with protocol
                        ((TakerProtocol) tradeProtocol).onTakeOffer(result -> {
                            tradeResultHandler.handleResult(trade);
                            requestPersistence();
                        }, errorMessage -> {
                            log.warn("Taker error during trade initialization: " + errorMessage);
                            xmrWalletService.resetAddressEntriesForOpenOffer(trade.getId());
                            maybeRemoveTradeOnError(trade);
                            errorMessageHandler.handleErrorMessage(errorMessage);
                        });
                        requestPersistence();
                    } else {
                        log.warn("Cannot take offer {} because it's not available, state={}", offer.getId(), offer.getState());
                    }
                },
                errorMessage -> {
                    log.warn("Taker error during check offer availability: " + errorMessage);
                    errorMessageHandler.handleErrorMessage(errorMessage);
                });

        requestPersistence();
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

        // TODO The address entry should have been removed already. Check and if its the case remove that.
        xmrWalletService.resetAddressEntriesForTrade(trade.getId());
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
            onTradeCompleted(trade);
            xmrWalletService.resetAddressEntriesForTrade(trade.getId());
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
        for (Trade trade : new ArrayList<Trade>(tradableList.getList())) {
            if (!trade.isPayoutPublished()) {
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
        removeTrade(trade);
        failedTradesManager.add(trade);
    }

    public void addFailedTradeToPendingTrades(Trade trade) {
        if (!trade.isInitialized()) {
            initPersistedTrade(trade);
        }
        addTrade(trade);
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        synchronized (tradableList) {
            return getObservableList().stream().filter(Trade::isFundsLockedIn);
        }
    }

    public Set<String> getSetOfFailedOrClosedTradeIdsFromLockedInFunds() throws TradeTxException {
        AtomicReference<TradeTxException> tradeTxException = new AtomicReference<>();
        synchronized (tradableList) {
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
                        } else {
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
            synchronized (tradableList) {
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

    public void sendAckMessage(NodeAddress peer, PubKeyRing peersPubKeyRing, TradeMessage message, boolean result, @Nullable String errorMessage) {

        // create ack message
        String tradeId = message.getTradeId();
        String sourceUid = message.getUid();
        AckMessage ackMessage = new AckMessage(P2PService.getMyNodeAddress(),
                AckMessageSourceType.TRADE_MESSAGE,
                message.getClass().getSimpleName(),
                sourceUid,
                tradeId,
                result,
                errorMessage);

        // send ack message
        log.info("Send AckMessage for {} to peer {}. tradeId={}, sourceUid={}",
                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
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
        synchronized (tradableList) {
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

    // TODO (woodser): make Optional<Trade> versus Trade return types consistent
    public Trade getTrade(String tradeId) {
        return getOpenTrade(tradeId).orElseGet(() -> getClosedTrade(tradeId).orElseGet(() -> getFailedTrade(tradeId).orElseGet(() -> null)));
    }

    public Optional<Trade> getOpenTrade(String tradeId) {
        synchronized (tradableList) {
            return tradableList.stream().filter(e -> e.getId().equals(tradeId)).findFirst();
        }
    }

    public Optional<Trade> getOpenTradeByUid(String tradeUid) {
        synchronized (tradableList) {
            return tradableList.stream().filter(e -> e.getUid().equals(tradeUid)).findFirst();
        }
    }

    public List<Trade> getAllTrades() {
        synchronized (tradableList) {
            List<Trade> trades = new ArrayList<Trade>();
            trades.addAll(tradableList.getList());
            trades.addAll(closedTradableManager.getClosedTrades());
            trades.addAll(failedTradesManager.getObservableList());
            return trades;
        }
    }

    public List<Trade> getOpenTrades() {
        synchronized (tradableList) {
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
        return closedTradableManager.getClosedTrades().stream().filter(e -> e.getId().equals(tradeId)).findFirst();
    }

    public Optional<Trade> getFailedTrade(String tradeId) {
        return failedTradesManager.getTradeById(tradeId);
    }

    private void addTrade(Trade trade) {
        UserThread.execute(() -> {
            synchronized (tradableList) {
                if (tradableList.add(trade)) {
                    requestPersistence();
                }
            }
        });
    }

    private void removeTrade(Trade trade) {
        log.info("TradeManager.removeTrade() " + trade.getId());
        synchronized (tradableList) {
            if (!tradableList.contains(trade)) return;
        }

        // remove trade
        UserThread.execute(() -> {
            synchronized (tradableList) {
                tradableList.remove(trade);
            }
        });

        // unregister and persist
        p2PService.removeDecryptedDirectMessageListener(getTradeProtocol(trade));
        requestPersistence();
    }

    private void maybeRemoveTradeOnError(Trade trade) {
        synchronized (tradableList) {
            if (trade.isDepositRequested() && !trade.isDepositFailed()) {
                listenForCleanup(trade);
            } else {
                removeTradeOnError(trade);
            }
        }
    }

    private void removeTradeOnError(Trade trade) {
        log.warn("TradeManager.removeTradeOnError() tradeId={}, state={}", trade.getId(), trade.getState());
        synchronized (tradableList) {

            // unreserve taker key images
            if (trade instanceof TakerTrade && trade.getSelf().getReserveTxKeyImages() != null) {
                xmrWalletService.thawOutputs(trade.getSelf().getReserveTxKeyImages());
                xmrWalletService.saveMainWallet();
                trade.getSelf().setReserveTxKeyImages(null);
            }

            // unreserve open offer
            Optional<OpenOffer> openOffer = openOfferManager.getOpenOfferById(trade.getId());
            if (trade instanceof MakerTrade && openOffer.isPresent()) {
                openOfferManager.unreserveOpenOffer(openOffer.get());
            }
        }

        // clear and shut down trade
        trade.clearAndShutDown();

        // remove trade from list
        removeTrade(trade);
    }

    private void listenForCleanup(Trade trade) {
        if (getOpenTrade(trade.getId()).isPresent() && trade.isDepositRequested()) {
            if (trade.isDepositsPublished()) {
                cleanupPublishedTrade(trade);
            } else {
                log.warn("Scheduling to delete open trade if unfunded for {} {}", trade.getClass().getSimpleName(), trade.getId());
                new TradeCleanupListener(trade); // TODO: better way than creating listener?
            }
        }
    }

    private void cleanupPublishedTrade(Trade trade) {
        if (trade instanceof MakerTrade && openOfferManager.getOpenOfferById(trade.getId()).isPresent()) {
            log.warn("Closing open offer as cleanup step");
            openOfferManager.closeOpenOffer(checkNotNull(trade.getOffer()));
        }
    }

    private class TradeCleanupListener {

        private static final long REMOVE_AFTER_MS = 60000;
        private static final int REMOVE_AFTER_NUM_CONFIRMATIONS = 1;
        private Long startHeight;
        private Subscription stateSubscription;
        private Subscription heightSubscription;

        public TradeCleanupListener(Trade trade) {

            // listen for deposits published to close open offer
            stateSubscription = EasyBind.subscribe(trade.stateProperty(), state -> {
                if (trade.isDepositsPublished()) {
                    cleanupPublishedTrade(trade);
                    if (stateSubscription != null) {
                        stateSubscription.unsubscribe();
                        stateSubscription = null;
                    }
                }
            });

            // listen for block confirmation to remove trade
            long startTime = System.currentTimeMillis();
            heightSubscription = EasyBind.subscribe(xmrWalletService.getConnectionService().chainHeightProperty(), lastBlockHeight -> {
                if (isShutDown) return;
                if (startHeight == null) startHeight = lastBlockHeight.longValue();
                if (lastBlockHeight.longValue() >= startHeight + REMOVE_AFTER_NUM_CONFIRMATIONS) {
                    new Thread(() -> {

                        // wait minimum time
                        GenUtils.waitFor(Math.max(0, REMOVE_AFTER_MS - (System.currentTimeMillis() - startTime)));

                        // get trade's deposit txs from daemon
                        MoneroTx makerDepositTx = trade.getMaker().getDepositTxHash() == null ? null : xmrWalletService.getDaemon().getTx(trade.getMaker().getDepositTxHash());
                        MoneroTx takerDepositTx = trade.getTaker().getDepositTxHash() == null ? null : xmrWalletService.getDaemon().getTx(trade.getTaker().getDepositTxHash());

                        // remove trade and wallet if neither deposit tx published
                        if (makerDepositTx == null && takerDepositTx == null) {
                            log.warn("Deleting {} {} after protocol error", trade.getClass().getSimpleName(), trade.getId());
                            if (trade instanceof ArbitratorTrade && (trade.getMaker().getReserveTxHash() != null || trade.getTaker().getReserveTxHash() != null)) {
                                onMoveInvalidTradeToFailedTrades(trade); // arbitrator retains trades with reserved funds for analysis and penalty
                            } else {
                                removeTradeOnError(trade);
                                failedTradesManager.removeTrade(trade);
                            }
                        } else if (!trade.isPayoutPublished()) {

                            // set error that wallet may be partially funded
                            String errorMessage = "Refusing to delete " + trade.getClass().getSimpleName() + " " + trade.getId() + " after protocol timeout because its wallet might be funded";
                            trade.prependErrorMessage(errorMessage);
                            log.warn(errorMessage);
                        }

                        // unsubscribe
                        if (heightSubscription != null) {
                            heightSubscription.unsubscribe();
                            heightSubscription = null;
                        }

                    }).start();
                }
            });
        }
    }

    // TODO Remove once tradableList is refactored to a final field
    //  (part of the persistence refactor PR)
    private void onTradesChanged() {
        this.numPendingTrades.set(getObservableList().size());
    }
}
