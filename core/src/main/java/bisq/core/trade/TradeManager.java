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

package bisq.core.trade;

import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferBookService;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.offer.availability.OfferAvailabilityModel;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.trade.failed.FailedTradesManager;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.InputsForDepositTxRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.messages.UpdateMultisigRequest;
import bisq.core.trade.protocol.ArbitratorProtocol;
import bisq.core.trade.protocol.MakerProtocol;
import bisq.core.trade.protocol.ProcessModel;
import bisq.core.trade.protocol.ProcessModelServiceProvider;
import bisq.core.trade.protocol.TakerProtocol;
import bisq.core.trade.protocol.TradeProtocol;
import bisq.core.trade.protocol.TradeProtocolFactory;
import bisq.core.trade.statistics.ReferralIdService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.User;
import bisq.core.util.Validator;

import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.network.TorNetworkNode;

import bisq.common.ClockWatcher;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;

import javax.inject.Inject;
import javax.inject.Named;

import com.google.common.util.concurrent.FutureCallback;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import org.bouncycastle.crypto.params.KeyParameter;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Getter;
import lombok.Setter;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.model.MoneroTxWallet;

public class TradeManager implements PersistedDataHost, DecryptedDirectMessageListener {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    @Getter
    private final KeyRing keyRing;
    private final XmrWalletService xmrWalletService;
    private final BsqWalletService bsqWalletService;
    private final OfferBookService offerBookService;
    private final OpenOfferManager openOfferManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final TradeStatisticsManager tradeStatisticsManager;
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
    @Setter
    @Nullable
    private ErrorMessageHandler takeOfferRequestErrorMessageHandler;
    @Getter
    private final LongProperty numPendingTrades = new SimpleLongProperty();
    private final ReferralIdService referralIdService;
    private final DumpDelayedPayoutTx dumpDelayedPayoutTx;
    @Getter
    private final boolean allowFaultyDelayedTxs;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user,
                        KeyRing keyRing,
                        XmrWalletService xmrWalletService,
                        BsqWalletService bsqWalletService,
                        OfferBookService offerBookService,
                        OpenOfferManager openOfferManager,
                        ClosedTradableManager closedTradableManager,
                        FailedTradesManager failedTradesManager,
                        P2PService p2PService,
                        PriceFeedService priceFeedService,
                        TradeStatisticsManager tradeStatisticsManager,
                        TradeUtil tradeUtil,
                        ArbitratorManager arbitratorManager,
                        MediatorManager mediatorManager,
                        ProcessModelServiceProvider processModelServiceProvider,
                        ClockWatcher clockWatcher,
                        PersistenceManager<TradableList<Trade>> persistenceManager,
                        ReferralIdService referralIdService,
                        DumpDelayedPayoutTx dumpDelayedPayoutTx,
                        @Named(Config.ALLOW_FAULTY_DELAYED_TXS) boolean allowFaultyDelayedTxs) {
        this.user = user;
        this.keyRing = keyRing;
        this.xmrWalletService = xmrWalletService;
        this.bsqWalletService = bsqWalletService;
        this.offerBookService = offerBookService;
        this.openOfferManager = openOfferManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.tradeUtil = tradeUtil;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.processModelServiceProvider = processModelServiceProvider;
        this.clockWatcher = clockWatcher;
        this.referralIdService = referralIdService;
        this.dumpDelayedPayoutTx = dumpDelayedPayoutTx;
        this.allowFaultyDelayedTxs = allowFaultyDelayedTxs;
        this.persistenceManager = persistenceManager;

        this.persistenceManager.initialize(tradableList, "PendingTrades", PersistenceManager.Source.PRIVATE);

        p2PService.addDecryptedDirectMessageListener(this);

        failedTradesManager.setUnFailTradeCallback(this::unFailTrade);
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
                    dumpDelayedPayoutTx.maybeDumpDelayedPayoutTxs(tradableList, "delayed_payout_txs_pending");
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
        if (networkEnvelope instanceof InputsForDepositTxRequest) {
          //handleTakeOfferRequest(peer, (InputsForDepositTxRequest) networkEnvelope);  // ignore bisq requests
        } else if (networkEnvelope instanceof InitTradeRequest) {
            handleInitTradeRequest((InitTradeRequest) networkEnvelope, peer);
        } else if (networkEnvelope instanceof InitMultisigMessage) {
            handleMultisigMessage((InitMultisigMessage) networkEnvelope, peer);
        } else if (networkEnvelope instanceof MakerReadyToFundMultisigRequest) {
            handleMakerReadyToFundMultisigRequest((MakerReadyToFundMultisigRequest) networkEnvelope, peer);
        } else if (networkEnvelope instanceof MakerReadyToFundMultisigResponse) {
            handleMakerReadyToFundMultisigResponse((MakerReadyToFundMultisigResponse) networkEnvelope, peer);
        } else if (networkEnvelope instanceof DepositTxMessage) {
            handleDepositTxMessage((DepositTxMessage) networkEnvelope, peer);
        }  else if (networkEnvelope instanceof UpdateMultisigRequest) {
            handleUpdateMultisigRequest((UpdateMultisigRequest) networkEnvelope, peer);
        }
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

        getObservableList().addListener((ListChangeListener<Trade>) change -> onTradesChanged());
        onTradesChanged();

        xmrWalletService.getAddressEntriesForAvailableBalanceStream()
                .filter(addressEntry -> addressEntry.getOfferId() != null)
                .forEach(addressEntry -> {
                    log.warn("Swapping pending OFFER_FUNDING entries at startup. offerId={}", addressEntry.getOfferId());
                    xmrWalletService.swapTradeEntryToAvailableEntry(addressEntry.getOfferId(), XmrAddressEntry.Context.OFFER_FUNDING);
                });
    }

    public TradeProtocol getTradeProtocol(Trade trade) {
        String uid = trade.getUid();
        if (tradeProtocolByTradeId.containsKey(uid)) {
            return tradeProtocolByTradeId.get(uid);
        } else {
            TradeProtocol tradeProtocol = TradeProtocolFactory.getNewTradeProtocol(trade);
            TradeProtocol prev = tradeProtocolByTradeId.put(uid, tradeProtocol);
            if (prev != null) {
                log.error("We had already an entry with uid {}", trade.getUid());
            }

            return tradeProtocol;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Init pending trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void initPersistedTrades() {
        tradableList.forEach(this::initPersistedTrade);
        persistedTradesInitialized.set(true);

        // We do not include failed trades as they should not be counted anyway in the trade statistics
        Set<Trade> allTrades = new HashSet<>(closedTradableManager.getClosedTrades());
        allTrades.addAll(tradableList.getList());
        String referralId = referralIdService.getOptionalReferralId().orElse(null);
        boolean isTorNetworkNode = p2PService.getNetworkNode() instanceof TorNetworkNode;
        tradeStatisticsManager.maybeRepublishTradeStatistics(allTrades, referralId, isTorNetworkNode);
    }

    private void initPersistedTrade(Trade trade) {
        initTradeAndProtocol(trade, getTradeProtocol(trade));
        trade.updateDepositTxFromWallet();
        requestPersistence();
    }

    private void initTradeAndProtocol(Trade trade, TradeProtocol tradeProtocol) {
        tradeProtocol.initialize(processModelServiceProvider, this, trade.getOffer());
        trade.initialize(processModelServiceProvider);
        requestPersistence();
    }

    public void requestPersistence() {
        persistenceManager.requestPersistence();
    }

    private void handleInitTradeRequest(InitTradeRequest initTradeRequest, NodeAddress peer) {
      log.info("Received InitTradeRequest from {} with tradeId {} and uid {}", peer, initTradeRequest.getTradeId(), initTradeRequest.getUid());

      try {
          Validator.nonEmptyStringOf(initTradeRequest.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + initTradeRequest.toString());
          return;
      }

      System.out.println("RECEIVED INIT REQUEST INFO");
      System.out.println("Sender peer node address: " + initTradeRequest.getSenderNodeAddress());
      System.out.println("Maker node address: " + initTradeRequest.getMakerNodeAddress());
      System.out.println("Taker node adddress: " + initTradeRequest.getTakerNodeAddress());
      System.out.println("Arbitrator node address: " + initTradeRequest.getArbitratorNodeAddress());

      // handle request as arbitrator
      boolean isArbitrator = initTradeRequest.getArbitratorNodeAddress().equals(p2PService.getNetworkNode().getNodeAddress());
      if (isArbitrator) {

          // get offer associated with trade
          Offer offer = null;
          for (Offer anOffer : offerBookService.getOffers()) {
            if (anOffer.getId().equals(initTradeRequest.getTradeId())) {
              offer = anOffer;
            }
          }
          if (offer == null) throw new RuntimeException("No offer on the books with trade id: " + initTradeRequest.getTradeId()); // TODO (woodser): proper error handling

          Trade trade;
          Optional<Trade> tradeOptional = getTradeById(offer.getId());
          if (!tradeOptional.isPresent()) {
            trade = new ArbitratorTrade(offer,
                    Coin.valueOf(initTradeRequest.getTradeAmount()),
                    Coin.valueOf(initTradeRequest.getTxFee()),
                    Coin.valueOf(initTradeRequest.getTradeFee()),
                    initTradeRequest.getTradePrice(),
                    initTradeRequest.getMakerNodeAddress(),
                    initTradeRequest.getTakerNodeAddress(),
                    initTradeRequest.getArbitratorNodeAddress(),
                    xmrWalletService,
                    getNewProcessModel(offer),
                    UUID.randomUUID().toString());
            initTradeAndProtocol(trade, getTradeProtocol(trade));
            tradableList.add(trade);
          } else {
            trade = tradeOptional.get();
          }

          // TODO (woodser): do this for arbitrator?
//          TradeProtocol tradeProtocol = TradeProtocolFactory.getNewTradeProtocol(trade);
//          TradeProtocol prev = tradeProtocolByTradeId.put(trade.getUid(), tradeProtocol);
//          if (prev != null) {
//              log.error("We had already an entry with uid {}", trade.getUid());
//          }

          ((ArbitratorProtocol) getTradeProtocol(trade)).handleInitTradeRequest(initTradeRequest, peer, errorMessage -> {
              if (takeOfferRequestErrorMessageHandler != null)
                  takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);  // TODO (woodser): separate handler?
          });

          requestPersistence();
      }

      // handle request as maker
      else {

          Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(initTradeRequest.getTradeId());
          if (!openOfferOptional.isPresent()) {
              return;
          }

          OpenOffer openOffer = openOfferOptional.get();
          if (openOffer.getState() != OpenOffer.State.AVAILABLE) {
              return;
          }

          Offer offer = openOffer.getOffer();
          openOfferManager.reserveOpenOffer(openOffer); // TODO (woodser): reserve offer if arbitrator?

          Trade trade;
          if (offer.isBuyOffer())
              trade = new BuyerAsMakerTrade(offer,
                      Coin.valueOf(initTradeRequest.getTxFee()),
                      Coin.valueOf(initTradeRequest.getTradeFee()),
                      initTradeRequest.getMakerNodeAddress(),
                      initTradeRequest.getTakerNodeAddress(),
                      initTradeRequest.getArbitratorNodeAddress(),
                      xmrWalletService,
                      getNewProcessModel(offer),
                      UUID.randomUUID().toString());
          else
              trade = new SellerAsMakerTrade(offer,
                      Coin.valueOf(initTradeRequest.getTxFee()),
                      Coin.valueOf(initTradeRequest.getTradeFee()),
                      initTradeRequest.getMakerNodeAddress(),
                      initTradeRequest.getTakerNodeAddress(),
                      openOffer.getArbitratorNodeAddress(),
                      xmrWalletService,
                      getNewProcessModel(offer),
                      UUID.randomUUID().toString());

          TradeProtocol tradeProtocol = TradeProtocolFactory.getNewTradeProtocol(trade);
          TradeProtocol prev = tradeProtocolByTradeId.put(trade.getUid(), tradeProtocol);
          if (prev != null) {
              log.error("We had already an entry with uid {}", trade.getUid());
          }

          tradableList.add(trade);
          initTradeAndProtocol(trade, tradeProtocol);

          ((MakerProtocol) tradeProtocol).handleInitTradeRequest(initTradeRequest,  peer, errorMessage -> {
              if (takeOfferRequestErrorMessageHandler != null)
                  takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
          });

          requestPersistence();
      }
    }

    private void handleMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigRequest request, NodeAddress peer) {
      log.info("Received MakerReadyToFundMultisigResponse from {} with tradeId {} and uid {}", peer, request.getTradeId(), request.getUid());

      try {
          Validator.nonEmptyStringOf(request.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + request.toString());
          return;
      }

      Optional<Trade> tradeOptional = getTradeById(request.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + request.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      ((MakerProtocol) getTradeProtocol(trade)).handleMakerReadyToFundMultisigRequest(request, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

    private void handleMakerReadyToFundMultisigResponse(MakerReadyToFundMultisigResponse response, NodeAddress peer) {
      log.info("Received MakerReadyToFundMultisigResponse from {} with tradeId {} and uid {}", peer, response.getTradeId(), response.getUid());

      try {
          Validator.nonEmptyStringOf(response.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + response.toString());
          return;
      }

      Optional<Trade> tradeOptional = getTradeById(response.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + response.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      ((TakerProtocol) getTradeProtocol(trade)).handleMakerReadyToFundMultisigResponse(response, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

    private void handleMultisigMessage(InitMultisigMessage multisigMessage, NodeAddress peer) {
      log.info("Received InitMultisigMessage from {} with tradeId {} and uid {}", peer, multisigMessage.getTradeId(), multisigMessage.getUid());

      try {
          Validator.nonEmptyStringOf(multisigMessage.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitMultisigMessage message " + multisigMessage.toString());
          return;
      }

      Optional<Trade> tradeOptional = getTradeById(multisigMessage.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + multisigMessage.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      getTradeProtocol(trade).handleMultisigMessage(multisigMessage, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

    private void handleUpdateMultisigRequest(UpdateMultisigRequest request, NodeAddress peer) {
      log.info("Received UpdateMultisigRequest from {} with tradeId {} and uid {}", peer, request.getTradeId(), request.getUid());

      try {
          Validator.nonEmptyStringOf(request.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid UpdateMultisigRequest message " + request.toString());
          return;
      }

      Optional<Trade> tradeOptional = getTradeById(request.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + request.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      getTradeProtocol(trade).handleUpdateMultisigRequest(request, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

    private void handleDepositTxMessage(DepositTxMessage request, NodeAddress peer) {
      log.info("Received DepositTxMessage from {} with tradeId {} and uid {}", peer, request.getTradeId(), request.getUid());

      try {
          Validator.nonEmptyStringOf(request.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + request.toString());
          return;
      }

      Optional<Trade> tradeOptional = getTradeById(request.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + request.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      getTradeProtocol(trade).handleDepositTxMessage(request, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer,
                                       boolean isTakerApiUser,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        offer.checkOfferAvailability(getOfferAvailabilityModel(offer, isTakerApiUser), resultHandler, errorMessageHandler);
    }

    // First we check if offer is still available then we create the trade with the protocol
    public void onTakeOffer(Coin amount,
                            Coin txFee,
                            Coin takerFee,
                            long tradePrice,
                            Coin fundsNeededForTrade,
                            Offer offer,
                            String paymentAccountId,
                            boolean useSavingsWallet,
                            boolean isTakerApiUser,
                            TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {

        checkArgument(!wasOfferAlreadyUsedInTrade(offer.getId()));

        OfferAvailabilityModel model = getOfferAvailabilityModel(offer, isTakerApiUser);
        offer.checkOfferAvailability(model,
                () -> {
                    if (offer.getState() == Offer.State.AVAILABLE) {
                        Trade trade;
                        if (offer.isBuyOffer()) {
                            trade = new SellerAsTakerTrade(offer,
                                    amount,
                                    txFee,
                                    takerFee,
                                    tradePrice,
                                    model.getPeerNodeAddress(),
                                    P2PService.getMyNodeAddress(), // TODO (woodser): correct taker node address?
                                    model.getSelectedMediator(),   // TODO (woodser): using mediator as arbitrator which is assigned upfront
                                    xmrWalletService,
                                    getNewProcessModel(offer),
                                    UUID.randomUUID().toString());
                        } else {
                            trade = new BuyerAsTakerTrade(offer,
                                    amount,
                                    txFee,
                                    takerFee,
                                    tradePrice,
                                    model.getPeerNodeAddress(),
                                    P2PService.getMyNodeAddress(),
                                    model.getSelectedMediator(),  // TODO (woodser): using mediator as arbitrator which is assigned upfront
                                    xmrWalletService,
                                    getNewProcessModel(offer),
                                    UUID.randomUUID().toString());
                        }
                        trade.getProcessModel().setUseSavingsWallet(useSavingsWallet);
                        trade.getProcessModel().setFundsNeededForTradeAsLong(fundsNeededForTrade.value);
                        trade.setTakerPaymentAccountId(paymentAccountId);

                        TradeProtocol tradeProtocol = TradeProtocolFactory.getNewTradeProtocol(trade);
                        TradeProtocol prev = tradeProtocolByTradeId.put(trade.getUid(), tradeProtocol);
                        if (prev != null) {
                            log.error("We had already an entry with uid {}", trade.getUid());
                        }
                        tradableList.add(trade);

                        initTradeAndProtocol(trade, tradeProtocol);

                        ((TakerProtocol) tradeProtocol).onTakeOffer();
                        tradeResultHandler.handleResult(trade);
                        requestPersistence();
                    }
                },
                errorMessageHandler);

        requestPersistence();
    }

    private ProcessModel getNewProcessModel(Offer offer) {
        return new ProcessModel(checkNotNull(offer).getId(),
                processModelServiceProvider.getUser().getAccountId(),
                processModelServiceProvider.getKeyRing().getPubKeyRing());
    }

    private OfferAvailabilityModel getOfferAvailabilityModel(Offer offer, boolean isTakerApiUser) {
        return new OfferAvailabilityModel(
                offer,
                keyRing.getPubKeyRing(),
                p2PService,
                user,
                mediatorManager,
                tradeStatisticsManager,
                isTakerApiUser);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Complete trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onWithdrawRequest(String toAddress,
          Coin amount,
          Coin fee,
          KeyParameter aesKey,
          Trade trade,
          @Nullable String memo,
          ResultHandler resultHandler,
          FaultHandler faultHandler) {
      int fromAccountIdx = xmrWalletService.getOrCreateAddressEntry(trade.getId(),
          XmrAddressEntry.Context.TRADE_PAYOUT).getAccountIndex();
      FutureCallback<MoneroTxWallet> callback = new FutureCallback<MoneroTxWallet>() {
        @Override
        public void onSuccess(@javax.annotation.Nullable MoneroTxWallet transaction) {
          if (transaction != null) {
            log.debug("onWithdraw onSuccess tx ID:" + transaction.getHash());
            onTradeCompleted(trade);
            trade.setState(Trade.State.WITHDRAW_COMPLETED);
            getTradeProtocol(trade).onWithdrawCompleted();
            requestPersistence();
            resultHandler.handleResult();
          }
        }

        @Override
        public void onFailure(@NotNull Throwable t) {
          t.printStackTrace();
          log.error(t.getMessage());
          faultHandler.handleFault("An exception occurred at requestWithdraw (onFailure).", t);
        }
      };
      try {
        xmrWalletService.sendFunds(fromAccountIdx, toAddress, amount, XmrAddressEntry.Context.TRADE_PAYOUT, callback);
      } catch (AddressFormatException | InsufficientMoneyException | AddressEntryException e) {
        e.printStackTrace();
        log.error(e.getMessage());
        faultHandler.handleFault("An exception occurred at requestWithdraw.", e);
      }
    }

    // If trade was completed (closed without fault but might be closed by a dispute) we move it to the closed trades
    public void onTradeCompleted(Trade trade) {
        removeTrade(trade);
        closedTradableManager.add(trade);

        // TODO The address entry should have been removed already. Check and if its the case remove that.
        xmrWalletService.resetAddressEntriesForPendingTrade(trade.getId());
        requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void closeDisputedTrade(String tradeId, Trade.DisputeState disputeState) {
        Optional<Trade> tradeOptional = getTradeById(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            trade.setDisputeState(disputeState);
            onTradeCompleted(trade);
            xmrWalletService.swapTradeEntryToAvailableEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT);
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
        getObservableList().forEach(trade -> {
            if (!trade.isPayoutPublished()) {
                Date maxTradePeriodDate = trade.getMaxTradePeriodDate();
                Date halfTradePeriodDate = trade.getHalfTradePeriodDate();
                if (maxTradePeriodDate != null && halfTradePeriodDate != null) {
                    Date now = new Date();
                    if (now.after(maxTradePeriodDate)) {
                        trade.setTradePeriodState(Trade.TradePeriodState.TRADE_PERIOD_OVER);
                        requestPersistence();
                    } else if (now.after(halfTradePeriodDate)) {
                        trade.setTradePeriodState(Trade.TradePeriodState.SECOND_HALF);
                        requestPersistence();
                    }
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Failed trade handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // If trade is in already in critical state (if taker role: taker fee; both roles: after deposit published)
    // we move the trade to failedTradesManager
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
        return getObservableList().stream().filter(Trade::isFundsLockedIn);
    }

    public Set<String> getSetOfFailedOrClosedTradeIdsFromLockedInFunds() throws TradeTxException {
        AtomicReference<TradeTxException> tradeTxException = new AtomicReference<>();
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
                  MoneroTxWallet makerDepositTx = trade.getMakerDepositTx();
                  if (makerDepositTx != null) {
                      if (makerDepositTx.isLocked()) {
                        tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithUnconfirmedDepositTx", trade.getShortId()))); // TODO (woodser): rename to closedTradeWithLockedDepositTx
                      } else {
                        log.warn("We found a closed trade with locked up funds. " +
                                "That should never happen. trade ID=" + trade.getId());
                      }
                  } else {
                      tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithNoDepositTx", trade.getShortId())));
                  }

                  MoneroTxWallet takerDepositTx = trade.getTakerDepositTx();
                  if (takerDepositTx != null) {
                      if (!takerDepositTx.isConfirmed()) {
                        tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithUnconfirmedDepositTx", trade.getShortId())));
                      } else {
                        log.warn("We found a closed trade with locked up funds. " +
                                "That should never happen. trade ID=" + trade.getId());
                      }
                  } else {
                      tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithNoDepositTx", trade.getShortId())));
                  }
                  return trade.getId();
                })
                .collect(Collectors.toSet()));

        if (tradeTxException.get() != null)
            throw tradeTxException.get();

        return tradesIdSet;
    }

    // If trade still has funds locked up it might come back from failed trades
    // Aborts unfailing if the address entries needed are not available
    private boolean unFailTrade(Trade trade) {
        if (!recoverAddresses(trade)) {
            log.warn("Failed to recover address during unFail trade");
            return false;
        }

        initPersistedTrade(trade);

        if (!tradableList.contains(trade)) {
            tradableList.add(trade);
        }
        return true;
    }

    // The trade is added to pending trades if the associated address entries are AVAILABLE and
    // the relevant entries are changed, otherwise it's not added and no address entries are changed
    private boolean recoverAddresses(Trade trade) {
        // Find addresses associated with this trade.
        var entries = tradeUtil.getAvailableAddresses(trade);
        if (entries == null)
            return false;

        xmrWalletService.recoverAddressEntry(trade.getId(), entries.first,
                XmrAddressEntry.Context.MULTI_SIG);
        xmrWalletService.recoverAddressEntry(trade.getId(), entries.second,
                XmrAddressEntry.Context.TRADE_PAYOUT);
        return true;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters, Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableList<Trade> getObservableList() {
        return tradableList.getObservableList();
    }

    public BooleanProperty persistedTradesInitializedProperty() {
        return persistedTradesInitialized;
    }

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public boolean wasOfferAlreadyUsedInTrade(String offerId) {
        return getTradeById(offerId).isPresent() ||
                failedTradesManager.getTradeById(offerId).isPresent() ||
                closedTradableManager.getTradableById(offerId).isPresent();
    }

    public boolean isBuyer(Offer offer) {
        // If I am the maker, we use the OfferPayload.Direction, otherwise the mirrored direction
        if (isMyOffer(offer))
            return offer.isBuyOffer();
        else
            return offer.getDirection() == OfferPayload.Direction.SELL;
    }

    public Optional<Trade> getTradeById(String tradeId) {
        return tradableList.stream().filter(e -> e.getId().equals(tradeId)).findFirst();
    }

    private void removeTrade(Trade trade) {
        if (tradableList.remove(trade)) {
            requestPersistence();
        }
    }

    private void addTrade(Trade trade) {
        if (tradableList.add(trade)) {
            requestPersistence();
        }
    }

    // TODO Remove once tradableList is refactored to a final field
    //  (part of the persistence refactor PR)
    private void onTradesChanged() {
        this.numPendingTrades.set(getObservableList().size());
    }
}
