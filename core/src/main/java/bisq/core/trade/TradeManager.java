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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bisq.common.ClockWatcher;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.dao.DaoFacade;
import bisq.core.filter.FilterManager;
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
import bisq.core.support.dispute.refund.refundagent.RefundAgentManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.trade.failed.FailedTradesManager;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.InputsForDepositTxRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.messages.UpdateMultisigRequest;
import bisq.core.trade.protocol.MakerProtocol;
import bisq.core.trade.protocol.TakerProtocol;
import bisq.core.trade.statistics.ReferralIdService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.User;
import bisq.core.util.Validator;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.Setter;
import monero.wallet.model.MoneroTxWallet;

public class TradeManager implements PersistedDataHost {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    @Getter
    private final KeyRing keyRing;
    private final XmrWalletService xmrWalletService;
    private final BsqWalletService bsqWalletService;
    private final TradeWalletService tradeWalletService;
    private final OfferBookService offerBookService;
    private final OpenOfferManager openOfferManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final FilterManager filterManager;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final ReferralIdService referralIdService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;
    private final DaoFacade daoFacade;
    private final ClockWatcher clockWatcher;

    private final Storage<TradableList<Trade>> tradableListStorage;
    private TradableList<Trade> tradableList;
    private final BooleanProperty pendingTradesInitialized = new SimpleBooleanProperty();
    private List<Trade> tradesForStatistics;
    @Setter
    @Nullable
    private ErrorMessageHandler takeOfferRequestErrorMessageHandler;
    @Getter
    private final LongProperty numPendingTrades = new SimpleLongProperty();
    @Getter
    private final ObservableList<Trade> tradesWithoutDepositTx = FXCollections.observableArrayList();
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
                        TradeWalletService tradeWalletService,
                        OfferBookService offerBookService,
                        OpenOfferManager openOfferManager,
                        ClosedTradableManager closedTradableManager,
                        FailedTradesManager failedTradesManager,
                        P2PService p2PService,
                        PriceFeedService priceFeedService,
                        FilterManager filterManager,
                        TradeStatisticsManager tradeStatisticsManager,
                        ReferralIdService referralIdService,
                        AccountAgeWitnessService accountAgeWitnessService,
                        ArbitratorManager arbitratorManager,
                        MediatorManager mediatorManager,
                        RefundAgentManager refundAgentManager,
                        DaoFacade daoFacade,
                        ClockWatcher clockWatcher,
                        Storage<TradableList<Trade>> storage,
                        DumpDelayedPayoutTx dumpDelayedPayoutTx,
                        @Named(Config.ALLOW_FAULTY_DELAYED_TXS) boolean allowFaultyDelayedTxs) {
        this.user = user;
        this.keyRing = keyRing;
        this.xmrWalletService = xmrWalletService;
        this.bsqWalletService = bsqWalletService;
        this.tradeWalletService = tradeWalletService;
        this.offerBookService = offerBookService;
        this.openOfferManager = openOfferManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.referralIdService = referralIdService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.daoFacade = daoFacade;
        this.clockWatcher = clockWatcher;
        this.dumpDelayedPayoutTx = dumpDelayedPayoutTx;
        this.allowFaultyDelayedTxs = allowFaultyDelayedTxs;

        tradableListStorage = storage;

        p2PService.addDecryptedDirectMessageListener((decryptedMessageWithPubKey, peerNodeAddress) -> {
            NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();

            // Handler for incoming initial network_messages from taker
            if (networkEnvelope instanceof InputsForDepositTxRequest) {
                //handlePayDepositRequest((InputsForDepositTxRequest) networkEnvelope, peerNodeAddress);
            } else if (networkEnvelope instanceof InitTradeRequest) {
                handleInitTradeRequest((InitTradeRequest) networkEnvelope, peerNodeAddress);
            } else if (networkEnvelope instanceof InitMultisigMessage) {
                handleMultisigMessage((InitMultisigMessage) networkEnvelope, peerNodeAddress);
            } else if (networkEnvelope instanceof MakerReadyToFundMultisigRequest) {
                handleMakerReadyToFundMultisigRequest((MakerReadyToFundMultisigRequest) networkEnvelope, peerNodeAddress);
            } else if (networkEnvelope instanceof MakerReadyToFundMultisigResponse) {
                handleMakerReadyToFundMultisigResponse((MakerReadyToFundMultisigResponse) networkEnvelope, peerNodeAddress);
            } else if (networkEnvelope instanceof DepositTxMessage) {
                handleDepositTxMessage((DepositTxMessage) networkEnvelope, peerNodeAddress);
            }  else if (networkEnvelope instanceof UpdateMultisigRequest) {
                handleUpdateMultisigRequest((UpdateMultisigRequest) networkEnvelope, peerNodeAddress);
            }
        });

        // Might get called at startup after HS is published. Can be before or after initPendingTrades.
        p2PService.addDecryptedMailboxListener((decryptedMessageWithPubKey, senderNodeAddress) -> {
            NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
            if (networkEnvelope instanceof TradeMessage) {
                TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
                String tradeId = tradeMessage.getTradeId();
                Optional<Trade> tradeOptional = tradableList.stream().filter(e -> e.getId().equals(tradeId)).findAny();
                // The mailbox message will be removed inside the tasks after they are processed successfully
                tradeOptional.ifPresent(trade -> trade.addDecryptedMessageWithPubKey(decryptedMessageWithPubKey));
            } else if (networkEnvelope instanceof AckMessage) {
                AckMessage ackMessage = (AckMessage) networkEnvelope;
                if (ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE) {
                    if (ackMessage.isSuccess()) {
                        log.info("Received AckMessage for {} with tradeId {} and uid {}",
                                ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getSourceUid());
                    } else {
                        log.warn("Received AckMessage with error state for {} with tradeId {} and errorMessage={}",
                                ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getErrorMessage());
                    }
                    p2PService.removeEntryFromMailbox(decryptedMessageWithPubKey);
                }
            }
        });
        failedTradesManager.setUnfailTradeCallback(this::unfailTrade);
    }

    @Override
    public void readPersisted() {
        tradableList = new TradableList<>(tradableListStorage, "PendingTrades");
        tradableList.forEach(trade -> {
            trade.setTransientFields(tradableListStorage, xmrWalletService);
            Offer offer = trade.getOffer();
            if (offer != null)
                offer.setPriceFeedService(priceFeedService);
        });

        dumpDelayedPayoutTx.maybeDumpDelayedPayoutTxs(tradableList, "delayed_payout_txs_pending");
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        if (p2PService.isBootstrapped())
            initPendingTrades();
        else
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onUpdatedDataReceived() {
                    // Get called after onMailboxMessageAdded from initial data request
                    // The mailbox message will be removed inside the tasks after they are processed successfully
                    initPendingTrades();
                }
            });

        tradableList.getList().addListener((ListChangeListener<Trade>) change -> onTradesChanged());
        onTradesChanged();

        getAddressEntriesForAvailableBalanceStream()
                .filter(addressEntry -> addressEntry.getOfferId() != null)
                .forEach(addressEntry -> {
                    log.warn("Swapping pending OFFER_FUNDING entries at startup. offerId={}", addressEntry.getOfferId());
                    xmrWalletService.swapTradeEntryToAvailableEntry(addressEntry.getOfferId(), XmrAddressEntry.Context.OFFER_FUNDING);
                });
    }

    public void shutDown() {
    }

    private void initPendingTrades() {
        List<Trade> addTradeToFailedTradesList = new ArrayList<>();
        List<Trade> removePreparedTradeList = new ArrayList<>();
        tradesForStatistics = new ArrayList<>();
        tradableList.forEach(trade -> {
                    if (trade.isDepositPublished() ||
                            (trade.isTakerFeePublished() && !trade.hasFailed())) {
                        initPendingTrade(trade);
                    } else if (trade.isTakerFeePublished() && !trade.isFundsLockedIn()) {
                        addTradeToFailedTradesList.add(trade);
                        trade.appendErrorMessage("Invalid state: trade.isTakerFeePublished() && !trade.isFundsLockedIn()");
                    } else {
                        removePreparedTradeList.add(trade);
                    }

                    if (trade.getMakerDepositTx() == null) {  // TODO (woodser): arbitrator is currently not notified of trader deposit txs, so ignore these warnings in that case? (causes arbitrator popup to move to failed trades on startup)
                        log.warn("Maker deposit tx {} for trade with ID {} is null at initPendingTrades. " +
                                        "This can happen for valid transaction in rare cases (e.g. a chain re-org). " +
                                        "We leave it to the user to move the trade to failed trades if the problem persists.",
                                trade.getMakerDepositTxId(),
                                trade.getId());
                        tradesWithoutDepositTx.add(trade);
                    }
                    else if (trade.getTakerDepositTx() == null) {
                      log.warn("Taker deposit tx {} for trade with ID {} is null at initPendingTrades. " +
                                      "This can happen for valid transaction in rare cases (e.g. a chain re-org). " +
                                      "We leave it to the user to move the trade to failed trades if the problem persists.",
                              trade.getTakerDepositTxId(),
                              trade.getId());
                      tradesWithoutDepositTx.add(trade);
                  }

//                    try {
//                        DelayedPayoutTxValidation.validatePayoutTx(trade,
//                                trade.getDelayedPayoutTx(),
//                                daoFacade,
//                                xmrWalletService);
//                    } catch (DelayedPayoutTxValidation.DonationAddressException |
//                            DelayedPayoutTxValidation.InvalidTxException |
//                            DelayedPayoutTxValidation.InvalidLockTimeException |
//                            DelayedPayoutTxValidation.MissingDelayedPayoutTxException |
//                            DelayedPayoutTxValidation.AmountMismatchException e) {
//                        log.warn("Delayed payout tx exception, trade {}, exception {}", trade.getId(), e.getMessage());
//                        if (!allowFaultyDelayedTxs) {
//                            // We move it to failed trades so it cannot be continued.
//                            log.warn("We move the trade with ID '{}' to failed trades", trade.getId());
//                            addTradeToFailedTradesList.add(trade);
//                        }
//                    }
                }
        );

        // If we have a closed trade where the deposit tx is still not confirmed we move it to failed trades as the
        // payout tx cannot be valid as well in this case. As the trade do not progress without confirmation of the
        // deposit tx this should normally not happen. If we detect such a trade at start up (done in BisqSetup)  we
        // show a popup telling the user to do a SPV resync.
        closedTradableManager.getClosedTradables().stream()
                .filter(tradable -> tradable instanceof Trade)
                .map(tradable -> (Trade) tradable)
                .filter(Trade::isFundsLockedIn)
                .forEach(addTradeToFailedTradesList::add);

        addTradeToFailedTradesList.forEach(closedTradableManager::remove);

        addTradeToFailedTradesList.forEach(this::addTradeToFailedTrades);

        removePreparedTradeList.forEach(this::removePreparedTrade);

        cleanUpAddressEntries();

        pendingTradesInitialized.set(true);
    }

    private void initPendingTrade(Trade trade) {
        initTrade(trade, trade.getProcessModel().isUseSavingsWallet(),
                trade.getProcessModel().getFundsNeededForTradeAsLong());
        trade.updateDepositTxFromWallet();
        tradesForStatistics.add(trade);
    }

    private void onTradesChanged() {
        this.numPendingTrades.set(tradableList.getList().size());
    }

    private void cleanUpAddressEntries() {
        // We check if we have address entries which are not in our pending trades and clean up those entries.
        // They might be either from closed or failed trades or from trades we do not have at all in our data base files.
        Set<String> activeTrades = getTradableList().stream()
                .map(Tradable::getId)
                .collect(Collectors.toSet());

        xmrWalletService.getAddressEntriesForTrade().stream()
                .filter(e -> !activeTrades.contains(e.getOfferId()))
                .forEach(e -> {
                    log.warn("We found an outdated addressEntry for trade {}: entry={}", e.getOfferId(), e);
                    xmrWalletService.resetAddressEntriesForPendingTrade(e.getOfferId());
                });
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
                  tradableListStorage,
                  xmrWalletService);
          initTrade(trade, trade.getProcessModel().isUseSavingsWallet(), trade.getProcessModel().getFundsNeededForTradeAsLong());
          tradableList.add(trade);
        } else {
          trade = tradeOptional.get();
        }

        ((ArbitratorTrade) trade).handleInitTradeRequest(initTradeRequest, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
                takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);  // TODO (woodser): separate handler?
        });
        
        return;
      }

      // handle request as maker
      Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(initTradeRequest.getTradeId());
      if (openOfferOptional.isPresent() && openOfferOptional.get().getState() == OpenOffer.State.AVAILABLE) {
          OpenOffer openOffer = openOfferOptional.get();
          Offer offer = openOffer.getOffer();
          openOfferManager.reserveOpenOffer(openOffer);
          Trade trade;
          if (offer.isBuyOffer())
              trade = new BuyerAsMakerTrade(offer,
                      Coin.valueOf(initTradeRequest.getTxFee()),
                      Coin.valueOf(initTradeRequest.getTradeFee()),
                      initTradeRequest.getMakerNodeAddress(),
                      initTradeRequest.getTakerNodeAddress(),
                      initTradeRequest.getArbitratorNodeAddress(),
                      tradableListStorage,
                      xmrWalletService);
          else
              trade = new SellerAsMakerTrade(offer,
                      Coin.valueOf(initTradeRequest.getTxFee()),
                      Coin.valueOf(initTradeRequest.getTradeFee()),
                      initTradeRequest.getMakerNodeAddress(),
                      initTradeRequest.getTakerNodeAddress(),
                      openOffer.getArbitratorNodeAddress(),
                      tradableListStorage,
                      xmrWalletService);

          initTrade(trade, trade.getProcessModel().isUseSavingsWallet(), trade.getProcessModel().getFundsNeededForTradeAsLong());
          tradableList.add(trade);
          ((MakerTrade) trade).handleInitTradeRequest(initTradeRequest, peer, errorMessage -> {
              if (takeOfferRequestErrorMessageHandler != null)
                  takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
          });
      } else {
        // TODO respond
        //(RequestDepositTxInputsMessage)message.
        //  messageService.sendEncryptedMessage(peerAddress,messageWithPubKey.getMessage().);
        log.debug("We received a prepare multisig request but don't have that offer anymore.");
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
      ((MakerProtocol) trade.getTradeProtocol()).handleMakerReadyToFundMultisigRequest(request, peer, errorMessage -> {
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
      ((TakerProtocol) trade.getTradeProtocol()).handleMakerReadyToFundMultisigResponse(response, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }
    
    private void handleMultisigMessage(InitMultisigMessage multisigMessage, NodeAddress peer) {
      log.info("Received InitTradeRequest from {} with tradeId {} and uid {}", peer, multisigMessage.getTradeId(), multisigMessage.getUid());
      
      try {
          Validator.nonEmptyStringOf(multisigMessage.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + multisigMessage.toString());
          return;
      }
      
      Optional<Trade> tradeOptional = getTradeById(multisigMessage.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + multisigMessage.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      trade.getTradeProtocol().handleMultisigMessage(multisigMessage, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }
    
    private void handleUpdateMultisigRequest(UpdateMultisigRequest request, NodeAddress peer) {
      log.info("Received InitTradeRequest from {} with tradeId {} and uid {}", peer, request.getTradeId(), request.getUid());
      
      try {
          Validator.nonEmptyStringOf(request.getTradeId());
      } catch (Throwable t) {
          log.warn("Invalid InitTradeRequest message " + request.toString());
          return;
      }
      
      Optional<Trade> tradeOptional = getTradeById(request.getTradeId());
      if (!tradeOptional.isPresent()) throw new RuntimeException("No trade with id " + request.getTradeId()); // TODO (woodser): error handling
      Trade trade = tradeOptional.get();
      trade.getTradeProtocol().handleUpdateMultisigRequest(request, peer, errorMessage -> {
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
      trade.getTradeProtocol().handleDepositTxMessage(request, peer, errorMessage -> {
            if (takeOfferRequestErrorMessageHandler != null)
            takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
      });
    }

//    private void handlePayDepositRequest(InputsForDepositTxRequest inputsForDepositTxRequest, NodeAddress peer) {
//        log.info("Received PayDepositRequest from {} with tradeId {} and uid {}",
//                peer, inputsForDepositTxRequest.getTradeId(), inputsForDepositTxRequest.getUid());
//
//        try {
//            Validator.nonEmptyStringOf(inputsForDepositTxRequest.getTradeId());
//        } catch (Throwable t) {
//            log.warn("Invalid requestDepositTxInputsMessage " + inputsForDepositTxRequest.toString());
//            return;
//        }
//        
//        inputsForDepositTxRequest.get
//
//        Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(inputsForDepositTxRequest.getTradeId());
//        if (openOfferOptional.isPresent() && openOfferOptional.get().getState() == OpenOffer.State.AVAILABLE) {
//            OpenOffer openOffer = openOfferOptional.get();
//            Offer offer = openOffer.getOffer();
//            openOfferManager.reserveOpenOffer(openOffer);
//            Trade trade;
//            if (offer.isBuyOffer())
//                trade = new BuyerAsMakerTrade(offer,
//                        Coin.valueOf(inputsForDepositTxRequest.getTxFee()),
//                        Coin.valueOf(inputsForDepositTxRequest.getTakerFee()),
//                        inputsForDepositTxRequest.isCurrencyForTakerFeeBtc(),
//                        openOffer.getArbitratorNodeAddress(),
//                        openOffer.getMediatorNodeAddress(),
//                        openOffer.getRefundAgentNodeAddress(),
//                        tradableListStorage,
//                        xmrWalletService);
//            else
//                trade = new SellerAsMakerTrade(offer,
//                        Coin.valueOf(inputsForDepositTxRequest.getTxFee()),
//                        Coin.valueOf(inputsForDepositTxRequest.getTakerFee()),
//                        inputsForDepositTxRequest.isCurrencyForTakerFeeBtc(),
//                        openOffer.getArbitratorNodeAddress(),
//                        openOffer.getMediatorNodeAddress(),
//                        openOffer.getRefundAgentNodeAddress(),
//                        tradableListStorage,
//                        xmrWalletService);
//
//            initTrade(trade, trade.getProcessModel().isUseSavingsWallet(), trade.getProcessModel().getFundsNeededForTradeAsLong());
//            tradableList.add(trade);
//            ((MakerTrade) trade).handleTakeOfferRequest(inputsForDepositTxRequest, peer, errorMessage -> {
//                if (takeOfferRequestErrorMessageHandler != null)
//                    takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
//            });
//        } else {
//            // TODO respond
//            //(RequestDepositTxInputsMessage)message.
//            //  messageService.sendEncryptedMessage(peerAddress,messageWithPubKey.getMessage().);
//            log.debug("We received a take offer request but don't have that offer anymore.");
//        }
//    }

    private void initTrade(Trade trade, boolean useSavingsWallet, Coin fundsNeededForTrade) {
        trade.init(p2PService,
                xmrWalletService,
                bsqWalletService,
                tradeWalletService,
                daoFacade,
                this,
                openOfferManager,
                referralIdService,
                user,
                filterManager,
                accountAgeWitnessService,
                tradeStatisticsManager,
                arbitratorManager,
                mediatorManager,
                refundAgentManager,
                keyRing,
                useSavingsWallet,
                fundsNeededForTrade);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from Offerbook when offer gets removed from P2P network
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onOfferRemovedFromRemoteOfferBook(Offer offer) {
        offer.cancelAvailabilityRequest();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        offer.checkOfferAvailability(getOfferAvailabilityModel(offer), resultHandler, errorMessageHandler);
    }

    // When closing take offer view, we are not interested in the onCheckOfferAvailability result anymore, so remove from the map
    public void onCancelAvailabilityRequest(Offer offer) {
        offer.cancelAvailabilityRequest();
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
                            TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {
        final OfferAvailabilityModel model = getOfferAvailabilityModel(offer);
        offer.checkOfferAvailability(model,
                () -> {
                    if (offer.getState() == Offer.State.AVAILABLE)
                        createTrade(amount,
                                txFee,
                                takerFee,
                                tradePrice,
                                fundsNeededForTrade,
                                offer,
                                paymentAccountId,
                                useSavingsWallet,
                                model,
                                tradeResultHandler);
                },
                errorMessageHandler);
    }

    private void createTrade(Coin amount,
                             Coin txFee,
                             Coin takerFee,
                             long tradePrice,
                             Coin fundsNeededForTrade,
                             Offer offer,
                             String paymentAccountId,
                             boolean useSavingsWallet,
                             OfferAvailabilityModel model,
                             TradeResultHandler tradeResultHandler) {
      
        Trade trade;
        if (offer.isBuyOffer())
            trade = new SellerAsTakerTrade(offer,
                    amount,
                    txFee,
                    takerFee,
                    tradePrice,
                    model.getPeerNodeAddress(),
                    P2PService.getMyNodeAddress(),  // TODO (woodser): correct taker node address?
                    model.getSelectedMediator(),    // TODO (woodser): using mediator as arbitrator which is assigned upfront
                    tradableListStorage,
                    xmrWalletService);
        else
            trade = new BuyerAsTakerTrade(offer,
                    amount,
                    txFee,
                    takerFee,
                    tradePrice,
                    model.getPeerNodeAddress(),
                    P2PService.getMyNodeAddress(),
                    model.getSelectedMediator(), // TODO (woodser): using mediator as arbitrator which is assigned upfront
                    tradableListStorage,
                    xmrWalletService);

        trade.setTakerPaymentAccountId(paymentAccountId);
        initTrade(trade, useSavingsWallet, fundsNeededForTrade);
        tradableList.add(trade);
        
        // initialize trade among peers on take offer // TODO: allow peer to be offline and queue take offer?
        ((TakerTrade) trade).takeAvailableOffer(() -> {
          
          // ensure take fee tx was published
          if (trade.getTakerFeeTxId() == null) throw new RuntimeException("Taker fee transaction was not published");  // TODO (woodser) proper error handling

          // trade is considered handled when initiated (e.g. for UI)
          tradeResultHandler.handleResult(trade);
        });
    }

    private OfferAvailabilityModel getOfferAvailabilityModel(Offer offer) {
        return new OfferAvailabilityModel(
                offer,
                keyRing.getPubKeyRing(),
                p2PService,
                user,
                mediatorManager,
                tradeStatisticsManager);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onWithdrawRequest(String toAddress, Coin amount, Coin fee,
                                  Trade trade, ResultHandler resultHandler, FaultHandler faultHandler) {
        int fromAccountIdx = xmrWalletService.getOrCreateAddressEntry(trade.getId(),
                XmrAddressEntry.Context.TRADE_PAYOUT).getAccountIndex();
        try {
            String txHash = xmrWalletService.sendFunds(fromAccountIdx, toAddress, amount, XmrAddressEntry.Context.TRADE_PAYOUT);
            
            log.debug("onWithdraw onSuccess tx ID:" + txHash);
            addTradeToClosedTrades(trade);
            trade.setState(Trade.State.WITHDRAW_COMPLETED);
            resultHandler.handleResult();
        } catch (AddressFormatException | InsufficientMoneyException | AddressEntryException e) {
            e.printStackTrace();
            log.error(e.getMessage());
            faultHandler.handleFault("An exception occurred at requestWithdraw.", e);
        }
    }

    // If trade was completed (closed without fault but might be closed by a dispute) we move it to the closed trades
    public void addTradeToClosedTrades(Trade trade) {
        removeTrade(trade);
        closedTradableManager.add(trade);

        cleanUpAddressEntries();
    }

    // If trade is in already in critical state (if taker role: taker fee; both roles: after deposit published)
    // we move the trade to failedTradesManager
    public void addTradeToFailedTrades(Trade trade) {
        removeTrade(trade);
        failedTradesManager.add(trade);

        cleanUpAddressEntries();
    }

    // If trade still has funds locked up it might come back from failed trades
    // Aborts unfailing if the address entries needed are not available
    private boolean unfailTrade(Trade trade) {
        if (!recoverAddresses(trade)) {
            log.warn("Failed to recover address during unfail trade");
            return false;
        }

        initPendingTrade(trade);

        if (!tradableList.contains(trade)) {
            tradableList.add(trade);
        }
        return true;
    }

    // The trade is added to pending trades if the associated address entries are AVAILABLE and
    // the relevant entries are changed, otherwise it's not added and no address entries are changed
    private boolean recoverAddresses(Trade trade) {
        // Find addresses associated with this trade.
        var entries = TradeUtils.getAvailableAddresses(trade, xmrWalletService, keyRing);
        if (entries == null)
            return false;

        xmrWalletService.recoverAddressEntry(trade.getId(), entries.first,
                XmrAddressEntry.Context.MULTI_SIG);
        xmrWalletService.recoverAddressEntry(trade.getId(), entries.second,
                XmrAddressEntry.Context.TRADE_PAYOUT);
        return true;
    }


    // If trade is in preparation (if taker role: before taker fee is paid; both roles: before deposit published)
    // we just remove the trade from our list. We don't store those trades.
    public void removePreparedTrade(Trade trade) {
        removeTrade(trade);

        cleanUpAddressEntries();
    }

    private void removeTrade(Trade trade) {
        tradableList.remove(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void closeDisputedTrade(String tradeId, Trade.DisputeState disputeState) {
        Optional<Trade> tradeOptional = getTradeById(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            trade.setDisputeState(disputeState);
            addTradeToClosedTrades(trade);
            xmrWalletService.swapTradeEntryToAvailableEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableList<Trade> getTradableList() {
        return tradableList.getList();
    }

    public BooleanProperty pendingTradesInitializedProperty() {
        return pendingTradesInitialized;
    }

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
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

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> availableOrPayout = Stream.concat(xmrWalletService.getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT)
                .stream(), xmrWalletService.getFundedAvailableAddressEntries().stream());
        Stream<XmrAddressEntry> available = Stream.concat(availableOrPayout,
                xmrWalletService.getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, xmrWalletService.getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> xmrWalletService.getBalanceForAccount(addressEntry.getAccountIndex()).isPositive());
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        return getTradableList().stream()
                .filter(Trade::isFundsLockedIn);
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
        tradableList.getList().forEach(trade -> {
            if (!trade.isPayoutPublished()) {
                Date maxTradePeriodDate = trade.getMaxTradePeriodDate();
                Date halfTradePeriodDate = trade.getHalfTradePeriodDate();
                if (maxTradePeriodDate != null && halfTradePeriodDate != null) {
                    Date now = new Date();
                    if (now.after(maxTradePeriodDate))
                        trade.setTradePeriodState(Trade.TradePeriodState.TRADE_PERIOD_OVER);
                    else if (now.after(halfTradePeriodDate))
                        trade.setTradePeriodState(Trade.TradePeriodState.SECOND_HALF);
                }
            }
        });
    }

    public void persistTrades() {
        tradableList.persist();
    }
}
