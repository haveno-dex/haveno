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

package bisq.core.support.dispute.arbitration;

import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.api.CoreNotificationService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.dispute.DisputeResult.Winner;
import bisq.core.support.dispute.arbitration.messages.PeerPublishedDisputePayoutTxMessage;
import bisq.core.support.dispute.messages.ArbitratorPayoutTxRequest;
import bisq.core.support.dispute.messages.ArbitratorPayoutTxResponse;
import bisq.core.support.dispute.messages.DisputeResultMessage;
import bisq.core.support.dispute.messages.OpenNewDisputeMessage;
import bisq.core.support.dispute.messages.PeerOpenedDisputeMessage;
import bisq.core.support.messages.ChatMessage;
import bisq.core.support.messages.SupportMessage;
import bisq.core.trade.Contract;
import bisq.core.trade.Tradable;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.util.ParsingUtils;

import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import bisq.network.p2p.SendMailboxMessageListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.google.common.base.Preconditions;

import java.math.BigInteger;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.common.MoneroError;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
@Singleton
public final class ArbitrationManager extends DisputeManager<ArbitrationDisputeList> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ArbitrationManager(P2PService p2PService,
                              TradeWalletService tradeWalletService,
                              XmrWalletService walletService,
                              CoreMoneroConnectionsService connectionService,
                              CoreNotificationService notificationService,
                              TradeManager tradeManager,
                              ClosedTradableManager closedTradableManager,
                              OpenOfferManager openOfferManager,
                              KeyRing keyRing,
                              ArbitrationDisputeListService arbitrationDisputeListService,
                              Config config,
                              PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, connectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, arbitrationDisputeListService, config, priceFeedService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.ARBITRATION;
    }

    @Override
    public void onSupportMessage(SupportMessage message) {
        if (canProcessMessage(message)) {
            log.info("Received {} with tradeId {} and uid {}",
                    message.getClass().getSimpleName(), message.getTradeId(), message.getUid());

            if (message instanceof OpenNewDisputeMessage) {
                onOpenNewDisputeMessage((OpenNewDisputeMessage) message);
            } else if (message instanceof PeerOpenedDisputeMessage) {
                onPeerOpenedDisputeMessage((PeerOpenedDisputeMessage) message);
            } else if (message instanceof ChatMessage) {
                onChatMessage((ChatMessage) message);
            } else if (message instanceof DisputeResultMessage) {
                onDisputeResultMessage((DisputeResultMessage) message);
            } else if (message instanceof PeerPublishedDisputePayoutTxMessage) {
                onDisputedPayoutTxMessage((PeerPublishedDisputePayoutTxMessage) message);
            } else if (message instanceof ArbitratorPayoutTxRequest) {
                onArbitratorPayoutTxRequest((ArbitratorPayoutTxRequest) message);
            } else if (message instanceof ArbitratorPayoutTxResponse) {
                onArbitratorPayoutTxResponse((ArbitratorPayoutTxResponse) message);
            } else {
                log.warn("Unsupported message at dispatchMessage. message={}", message);
            }
        }
    }

    @Override
    public NodeAddress getAgentNodeAddress(Dispute dispute) {
        return dispute.getContract().getArbitratorNodeAddress();
    }

    @Override
    protected Trade.DisputeState getDisputeStateStartedByPeer() {
        return Trade.DisputeState.DISPUTE_STARTED_BY_PEER;
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.ARBITRATION_MESSAGE;
    }

    @Override
    public void cleanupDisputes() {
        disputeListService.cleanupDisputes(tradeId -> tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.DISPUTE_CLOSED));
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.arbitrator").toLowerCase();
        String link = "https://docs.bisq.network/trading-rules.html#legacy-arbitration";
        return Res.get("support.initialInfo", role, role, link);
    }

    @Override
    protected String getDisputeIntroForPeer(String disputeInfo) {
        return Res.get("support.peerOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected String getDisputeIntroForDisputeCreator(String disputeInfo) {
        return Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        // Arbitrator is not used anymore.
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    // We get that message at both peers. The dispute object is in context of the trader
    public void onDisputeResultMessage(DisputeResultMessage disputeResultMessage) {
        DisputeResult disputeResult = disputeResultMessage.getDisputeResult();
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        Optional<Trade> tradeOptional = tradeManager.getTradeById(disputeResult.getTradeId());

        String tradeId = disputeResult.getTradeId();
        Optional<Dispute> disputeOptional = findDispute(disputeResult);
        String uid = disputeResultMessage.getUid();
        if (!disputeOptional.isPresent()) {
            log.warn("We got a dispute result msg but we don't have a matching dispute. " +
                    "That might happen when we get the disputeResultMessage before the dispute was created. " +
                    "We try again after 2 sec. to apply the disputeResultMessage. TradeId = " + tradeId);
            if (!delayMsgMap.containsKey(uid)) {
                // We delay 2 sec. to be sure the comm. msg gets added first
                Timer timer = UserThread.runAfter(() -> onDisputeResultMessage(disputeResultMessage), 2);
                delayMsgMap.put(uid, timer);
            } else {
                log.warn("We got a dispute result msg after we already repeated to apply the message after a delay. " +
                        "That should never happen. TradeId = " + tradeId);
            }
            return;
        }
        Dispute dispute = disputeOptional.get();

        // verify that arbitrator does not get DisputeResultMessage
        if (pubKeyRing.equals(dispute.getAgentPubKeyRing())) {
          log.error("Arbitrator received disputeResultMessage. That must never happen.");
          return;
        }

        cleanupRetryMap(uid);
        if (!dispute.getChatMessages().contains(chatMessage)) {
            dispute.addAndPersistChatMessage(chatMessage);
        } else {
            log.warn("We got a dispute mail msg what we have already stored. TradeId = " + chatMessage.getTradeId());
        }
        dispute.setIsClosed();

        if (dispute.disputeResultProperty().get() != null) {
            log.warn("We already got a dispute result. That should only happen if a dispute needs to be closed " +
                    "again because the first close did not succeed. TradeId = " + tradeId);
        }

        dispute.setDisputeResult(disputeResult);
        String errorMessage = null;
        boolean success = true;
        boolean requestUpdatedPayoutTx = false;
        MoneroWallet multisigWallet = xmrWalletService.getMultisigWallet(dispute.getTradeId());
        Contract contract = dispute.getContract();
        try {
            // We need to avoid publishing the tx from both traders as it would create problems with zero confirmation withdrawals
            // There would be different transactions if both sign and publish (signers: once buyer+arb, once seller+arb)
            // The tx publisher is the winner or in case both get 50% the buyer, as the buyer has more inventive to publish the tx as he receives
            // more BTC as he has deposited
            boolean isBuyer = pubKeyRing.equals(contract.getBuyerPubKeyRing());
            DisputeResult.Winner publisher = disputeResult.getWinner();

            // Sometimes the user who receives the trade amount is never online, so we might want to
            // let the loser publish the tx. When the winner comes online he gets his funds as it was published by the other peer.
            // Default isLoserPublisher is set to false
            if (disputeResult.isLoserPublisher()) {
                // we invert the logic
                if (publisher == DisputeResult.Winner.BUYER)
                    publisher = DisputeResult.Winner.SELLER;
                else if (publisher == DisputeResult.Winner.SELLER)
                    publisher = DisputeResult.Winner.BUYER;
            }

            if ((isBuyer && publisher == DisputeResult.Winner.BUYER)
                    || (!isBuyer && publisher == DisputeResult.Winner.SELLER)) {

                MoneroTxWallet payoutTx = null;
                if (tradeOptional.isPresent()) {
                    payoutTx = tradeOptional.get().getPayoutTx();
                } else {
                    Optional<Tradable> tradableOptional = closedTradableManager.getTradableById(tradeId);
                    if (tradableOptional.isPresent() && tradableOptional.get() instanceof Trade) {
                        payoutTx = ((Trade) tradableOptional.get()).getPayoutTx();
                    }
                }


                if (payoutTx == null) {

                  // gather relevant info
                  String arbitratorSignedPayoutTxHex = disputeResult.getArbitratorSignedPayoutTxHex();

                  if (arbitratorSignedPayoutTxHex != null) {
                      if (!tradeOptional.isPresent()) throw new RuntimeException("Trade must not be null when trader signs arbitrator's payout tx");

                      try {
                        MoneroTxSet txSet = traderSignsDisputePayoutTx(tradeId, arbitratorSignedPayoutTxHex);
                        onTraderSignedDisputePayoutTx(tradeId, txSet);
                      } catch (Exception e) {
                        errorMessage = "Failed to sign dispute payout tx from arbitrator: " + e.getMessage() + ". TradeId = " + tradeId + " SignedPayoutTx = " + arbitratorSignedPayoutTxHex;
                        log.warn(errorMessage);
                        success = false;
                      }
                  } else {
                    requestUpdatedPayoutTx = true;
                  }
                } else {
                    log.warn("We already got a payout tx. That might be the case if the other peer did not get the " +
                            "payout tx and opened a dispute. TradeId = " + tradeId);
                }
            } else {
                log.trace("We don't publish the tx as we are not the winning party.");
                // Clean up tangling trades
                if (dispute.disputeResultProperty().get() != null && dispute.isClosed()) {
                    updateTradeOrOpenOfferManager(tradeId);
                }
            }
        }
//        catch (TransactionVerificationException e) {
//            errorMessage = "Error at traderSignAndFinalizeDisputedPayoutTx " + e.toString();
//            log.error(errorMessage, e);
//            success = false;
//
//            // We prefer to close the dispute in that case. If there was no deposit tx and a random tx was used
//            // we get a TransactionVerificationException. No reason to keep that dispute open...
//            updateTradeOrOpenOfferManager(tradeId);
//
//            throw new RuntimeException(errorMessage);
//        }
//        catch (AddressFormatException | WalletException e) {
        catch (Exception e) {
            errorMessage = "Error at traderSignAndFinalizeDisputedPayoutTx " + e.toString();
            log.error(errorMessage, e);
            success = false;

            // We prefer to close the dispute in that case. If there was no deposit tx and a random tx was used
            // we get a TransactionVerificationException. No reason to keep that dispute open...
            updateTradeOrOpenOfferManager(tradeId); // TODO (woodser): only close in case of verification exception?

            throw new RuntimeException(errorMessage);
        } finally {
            // We use the chatMessage as we only persist those not the disputeResultMessage.
            // If we would use the disputeResultMessage we could not lookup for the msg when we receive the AckMessage.
            sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), success, errorMessage);

            // If dispute opener's peer is co-signer, send updated multisig hex to arbitrator to receive updated payout tx
            if (requestUpdatedPayoutTx) sendArbitratorPayoutTxRequest(multisigWallet.getMultisigHex(), dispute, contract);
        }

        requestPersistence();
    }

    // Losing trader or in case of 50/50 the seller gets the tx sent from the winner or buyer
    private void onDisputedPayoutTxMessage(PeerPublishedDisputePayoutTxMessage peerPublishedDisputePayoutTxMessage) {
        String uid = peerPublishedDisputePayoutTxMessage.getUid();
        String tradeId = peerPublishedDisputePayoutTxMessage.getTradeId();
        Optional<Dispute> disputeOptional = findDispute(tradeId);
        if (!disputeOptional.isPresent()) {
            log.debug("We got a peerPublishedPayoutTxMessage but we don't have a matching dispute. TradeId = " + tradeId);
            if (!delayMsgMap.containsKey(uid)) {
                // We delay 3 sec. to be sure the close msg gets added first
                Timer timer = UserThread.runAfter(() -> onDisputedPayoutTxMessage(peerPublishedDisputePayoutTxMessage), 3);
                delayMsgMap.put(uid, timer);
            } else {
                log.warn("We got a peerPublishedPayoutTxMessage after we already repeated to apply the message after a delay. " +
                        "That should never happen. TradeId = " + tradeId);
            }
            return;
        }

        Dispute dispute = disputeOptional.get();
        Contract contract = dispute.getContract();
        boolean isBuyer = pubKeyRing.equals(contract.getBuyerPubKeyRing());
        PubKeyRing peersPubKeyRing = isBuyer ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing();

        cleanupRetryMap(uid);

        // update multisig wallet
        // TODO: multisig wallet may already be deleted if peer completed trade with arbitrator. refactor trade completion?
        MoneroWallet multisigWallet = xmrWalletService.getMultisigWallet(dispute.getTradeId());
        if (multisigWallet != null) {
            multisigWallet.importMultisigHex(Arrays.asList(peerPublishedDisputePayoutTxMessage.getUpdatedMultisigHex()));
            MoneroTxWallet parsedPayoutTx = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(peerPublishedDisputePayoutTxMessage.getPayoutTxHex())).getTxs().get(0);
            dispute.setDisputePayoutTxId(parsedPayoutTx.getHash());
            XmrWalletService.printTxs("Disputed payoutTx received from peer", parsedPayoutTx);
        }

//        System.out.println("LOSER'S VIEW OF MULTISIG WALLET (SHOULD INCLUDE PAYOUT TX):\n" + multisigWallet.getTxs());
//        if (multisigWallet.getTxs().size() != 3) throw new RuntimeException("Loser's multisig wallet does not include record of payout tx");
//        Transaction committedDisputePayoutTx = WalletService.maybeAddNetworkTxToWallet(peerPublishedDisputePayoutTxMessage.getTransaction(), btcWalletService.getWallet());

        // We can only send the ack msg if we have the peersPubKeyRing which requires the dispute
        sendAckMessage(peerPublishedDisputePayoutTxMessage, peersPubKeyRing, true, null);
        requestPersistence();
    }

    // Arbitrator receives updated multisig hex from dispute opener's peer (if co-signer) and returns updated payout tx to be signed and published
    private void onArbitratorPayoutTxRequest(ArbitratorPayoutTxRequest request) {
      String tradeId = request.getTradeId();
      Dispute dispute = findDispute(request.getDispute().getTradeId(), request.getDispute().getTraderId()).get();
      DisputeResult disputeResult = dispute.getDisputeResultProperty().get();
      Contract contract = dispute.getContract();

      // verify sender is co-signer and receiver is arbitrator
      System.out.println("Any of these null???"); // TODO (woodser): NPE if dispute opener's peer-as-cosigner's ticket is closed first
      System.out.println(disputeResult);
      System.out.println(disputeResult.getWinner());
      System.out.println(contract.getBuyerNodeAddress());
      System.out.println(contract.getSellerNodeAddress());
      boolean senderIsWinner = (disputeResult.getWinner() == Winner.BUYER && contract.getBuyerNodeAddress().equals(request.getSenderNodeAddress())) || (disputeResult.getWinner() == Winner.SELLER && contract.getSellerNodeAddress().equals(request.getSenderNodeAddress()));
      boolean senderIsCosigner = senderIsWinner || disputeResult.isLoserPublisher();
      boolean receiverIsArbitrator = pubKeyRing.equals(dispute.getAgentPubKeyRing());

      System.out.println("TESTING PUB KEY RINGS");
      System.out.println(pubKeyRing);
      System.out.println(dispute.getAgentPubKeyRing());
      System.out.println("Receiver is arbitrator: " + receiverIsArbitrator);

      if (!senderIsCosigner) {
        log.warn("Received ArbitratorPayoutTxRequest but sender is not co-signer for trade id " + tradeId);
        return;
      }
      if (!receiverIsArbitrator) {
        log.warn("Received ArbitratorPayoutTxRequest but receiver is not arbitrator for trade id " + tradeId);
        return;
      }

      // update arbitrator's multisig wallet with co-signer's multisig hex
      MoneroWallet multisigWallet = xmrWalletService.getMultisigWallet(dispute.getTradeId());
      try {
        multisigWallet.importMultisigHex(Arrays.asList(request.getUpdatedMultisigHex()));
      } catch (Exception e) {
        log.warn("Failed to import multisig hex from payout co-signer for trade id " + tradeId);
        return;
      }

      // create updated payout tx
      MoneroTxWallet payoutTx = arbitratorCreatesDisputedPayoutTx(contract, dispute, disputeResult, multisigWallet);
      System.out.println("Arbitrator created updated payout tx for co-signer!!!");
      System.out.println(payoutTx);

      // send updated payout tx to sender
      PubKeyRing senderPubKeyRing = contract.getBuyerNodeAddress().equals(request.getSenderNodeAddress()) ? contract.getBuyerPubKeyRing() : contract.getSellerPubKeyRing();
      ArbitratorPayoutTxResponse response = new ArbitratorPayoutTxResponse(
          tradeId,
          p2PService.getAddress(),
          UUID.randomUUID().toString(),
          SupportType.ARBITRATION,
          payoutTx.getTxSet().getMultisigTxHex());
      log.info("Send {} to peer {}. tradeId={}, uid={}", response.getClass().getSimpleName(), request.getSenderNodeAddress(), dispute.getTradeId(), response.getUid());
      p2PService.sendEncryptedDirectMessage(request.getSenderNodeAddress(),
          senderPubKeyRing,
          response,
          new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived at peer {}. tradeId={}, uid={}",
                          response.getClass().getSimpleName(), request.getSenderNodeAddress(), dispute.getTradeId(), response.getUid());
              }

              @Override
              public void onFault(String errorMessage) {
                  log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                          response.getClass().getSimpleName(), request.getSenderNodeAddress(), dispute.getTradeId(), response.getUid(), errorMessage);
              }
          }
      );
    }

    // Dispute opener's peer receives updated payout tx after providing updated multisig hex (if co-signer)
    private void onArbitratorPayoutTxResponse(ArbitratorPayoutTxResponse response) {

      // gather and verify trade info // TODO (woodser): verify response is from arbitrator, etc
      String tradeId = response.getTradeId();

      // verify and sign dispute payout tx
      MoneroTxSet signedPayoutTx = traderSignsDisputePayoutTx(tradeId, response.getArbitratorSignedPayoutTxHex());

      // process fully signed payout tx (publish, notify peer, etc)
      onTraderSignedDisputePayoutTx(tradeId, signedPayoutTx);
    }

    private void onTraderSignedDisputePayoutTx(String tradeId, MoneroTxSet txSet) {

      // gather trade info
      MoneroWallet multisigWallet = xmrWalletService.getMultisigWallet(tradeId);
      Optional<Dispute> disputeOptional = findDispute(tradeId);
      if (!disputeOptional.isPresent()) {
          log.warn("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + tradeId);
          return;
      }
      Dispute dispute = disputeOptional.get();
      Contract contract = dispute.getContract();
      Trade trade = tradeManager.getTradeById(tradeId).get();

      // submit fully signed payout tx to the network
      multisigWallet.submitMultisigTxHex(txSet.getMultisigTxHex());

      // update state
      trade.setPayoutTx(txSet.getTxs().get(0));   // TODO (woodser): is trade.payoutTx() mutually exclusive from dispute payout tx?
      trade.setPayoutTxId(txSet.getTxs().get(0).getHash());
      trade.setState(Trade.State.SELLER_PUBLISHED_PAYOUT_TX);
      dispute.setDisputePayoutTxId(txSet.getTxs().get(0).getHash());
      sendPeerPublishedPayoutTxMessage(multisigWallet.getMultisigHex(), txSet.getMultisigTxHex(), dispute, contract);
      updateTradeOrOpenOfferManager(tradeId);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Send messages
    ///////////////////////////////////////////////////////////////////////////////////////////

    // winner (or buyer in case of 50/50) sends tx to other peer
    private void sendPeerPublishedPayoutTxMessage(String updatedMultisigHex, String payoutTxHex, Dispute dispute, Contract contract) {
        PubKeyRing peersPubKeyRing = dispute.isDisputeOpenerIsBuyer() ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing();
        NodeAddress peersNodeAddress = dispute.isDisputeOpenerIsBuyer() ? contract.getSellerNodeAddress() : contract.getBuyerNodeAddress();
        log.trace("sendPeerPublishedPayoutTxMessage to peerAddress {}", peersNodeAddress);
        PeerPublishedDisputePayoutTxMessage message = new PeerPublishedDisputePayoutTxMessage(updatedMultisigHex,
        		payoutTxHex,
                dispute.getTradeId(),
                p2PService.getAddress(),
                UUID.randomUUID().toString(),
                getSupportType());
        log.info("Send {} to peer {}. tradeId={}, uid={}",
                message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
        mailboxMessageService.sendEncryptedMailboxMessage(peersNodeAddress,
                peersPubKeyRing,
                message,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at peer {}. tradeId={}, uid={}",
                                message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}",
                                message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                                message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
                    }
                }
        );
    }

    private void updateTradeOrOpenOfferManager(String tradeId) {
        // set state after payout as we call swapTradeEntryToAvailableEntry
        if (tradeManager.getTradeById(tradeId).isPresent()) {
            tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.DISPUTE_CLOSED);
        } else {
            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(tradeId);
            openOfferOptional.ifPresent(openOffer -> openOfferManager.closeOpenOffer(openOffer.getOffer()));
        }
    }

    // dispute opener's peer signs payout tx by sending updated multisig hex to arbitrator who returns updated payout tx
    private void sendArbitratorPayoutTxRequest(String updatedMultisigHex, Dispute dispute, Contract contract) {
        ArbitratorPayoutTxRequest request = new ArbitratorPayoutTxRequest(
                dispute,
                p2PService.getAddress(),
                UUID.randomUUID().toString(),
                SupportType.ARBITRATION,
                updatedMultisigHex);
        log.info("Send {} to peer {}. tradeId={}, uid={}",
                request.getClass().getSimpleName(), contract.getArbitratorNodeAddress(), dispute.getTradeId(), request.getUid());
        p2PService.sendEncryptedDirectMessage(contract.getArbitratorNodeAddress(),
                dispute.getAgentPubKeyRing(),
                request,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at peer {}. tradeId={}, uid={}",
                                request.getClass().getSimpleName(), contract.getArbitratorNodeAddress(), dispute.getTradeId(), request.getUid());
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                                request.getClass().getSimpleName(), contract.getArbitratorNodeAddress(), dispute.getTradeId(), request.getUid(), errorMessage);
                    }
                }
        );
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Disputed payout tx signing
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): where to move this common logic?
    public static MoneroTxWallet arbitratorCreatesDisputedPayoutTx(Contract contract, Dispute dispute, DisputeResult disputeResult, MoneroWallet multisigWallet) {

      //System.out.println("DisputeSummaryWindow.arbitratorSignsDisputedPayoutTx()");
      //System.out.println("=== DISPUTE ===");
      //System.out.println(dispute);
      //System.out.println("=== CONTRACT ===");
      //System.out.println(contract); // TODO (woodser): contract should include deposit tx hashes (pre-created then hash shared then contract signed)
      //System.out.println("=== DISPUTE RESULT ===");
      //System.out.println(disputeResult);

      // gather relevant trade info
      String buyerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getMakerPayoutAddressString() : contract.getTakerPayoutAddressString();
      String sellerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getTakerPayoutAddressString() : contract.getMakerPayoutAddressString();
      Preconditions.checkNotNull(buyerPayoutAddress, "buyerPayoutAddress must not be null");
      Preconditions.checkNotNull(sellerPayoutAddress, "sellerPayoutAddress must not be null");
      BigInteger buyerPayoutAmount = ParsingUtils.coinToAtomicUnits(disputeResult.getBuyerPayoutAmount());
      BigInteger sellerPayoutAmount = ParsingUtils.coinToAtomicUnits(disputeResult.getSellerPayoutAmount());

      //System.out.println("buyerPayoutAddress: " + buyerPayoutAddress);
      //System.out.println("buyerPayoutAmount: " + buyerPayoutAmount);

//        Offer offer = new Offer(contract.getOfferPayload());
//        System.out.println("Buyer deposit tx fee: " +

      //System.out.println("sellerPayoutAddress: " + sellerPayoutAddress);
      //System.out.println("sellerPayoutAmount: " + sellerPayoutAmount);
      //System.out.println("Multisig balance: " + multisigWallet.getBalance());
      //System.out.println("Multisig unlocked balance: " + multisigWallet.getUnlockedBalance());
      //System.out.println("Multisig txs");
      //System.out.println(multisigWallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true)));

      // create transaction to get fee estimate
      if (multisigWallet.isMultisigImportNeeded()) {
        log.info("Arbitrator's wallet needs updated multisig hex to create payout tx which means a trader must have already broadcast the payout tx");
        return null;
      }

      // TODO (woodser): include arbitration fee
      //System.out.println("Creating feeEstimateTx!");
      MoneroTxConfig txConfig = new MoneroTxConfig().setAccountIndex(0).setRelay(false);
      if (buyerPayoutAmount.compareTo(BigInteger.ZERO) == 1) txConfig.addDestination(buyerPayoutAddress, buyerPayoutAmount.multiply(BigInteger.valueOf(4)).divide(BigInteger.valueOf(5))); // reduce payment amount to compute fee of similar tx
      if (sellerPayoutAmount.compareTo(BigInteger.ZERO) == 1) txConfig.addDestination(sellerPayoutAddress, sellerPayoutAmount.multiply(BigInteger.valueOf(4)).divide(BigInteger.valueOf(5)));
      MoneroTxWallet feeEstimateTx = multisigWallet.createTx(txConfig);

      System.out.println("Created fee estimate tx!");
      System.out.println(feeEstimateTx);
      //BigInteger estimatedFee = feeEstimateTx.getFee();

      // attempt to create payout tx by increasing estimated fee until successful
      MoneroTxWallet payoutTx = null;
      int numAttempts = 0;
      int feeDivisor = 0; // adjust fee divisor based on number of payout destinations
      if (buyerPayoutAmount.compareTo(BigInteger.ZERO) == 1) feeDivisor += 1;
      if (sellerPayoutAmount.compareTo(BigInteger.ZERO) == 1) feeDivisor += 1;

      while (payoutTx == null && numAttempts < 50) {
        BigInteger feeEstimate = feeEstimateTx.getFee().add(feeEstimateTx.getFee().multiply(BigInteger.valueOf(numAttempts)).divide(BigInteger.valueOf(10))); // add 1/10 of fee until tx is successful
        txConfig = new MoneroTxConfig().setAccountIndex(0).setRelay(false);
        if (buyerPayoutAmount.compareTo(BigInteger.ZERO) == 1) txConfig.addDestination(buyerPayoutAddress, buyerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(feeDivisor)))); // split fee subtracted from each payout amount
        if (sellerPayoutAmount.compareTo(BigInteger.ZERO) == 1) txConfig.addDestination(sellerPayoutAddress, sellerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(feeDivisor))));
        try {
          numAttempts++;
          payoutTx = multisigWallet.createTx(txConfig);
        } catch (MoneroError e) {
          // exception expected // TODO: better way of estimating fee?
        }
      }

      if (payoutTx == null) throw new RuntimeException("Failed to generate dispute payout tx");
      System.out.println("DISPUTE PAYOUT TX GENERATED ON ATTEMPT " + numAttempts);
      System.out.println(payoutTx);
      return payoutTx;
    }

    private MoneroTxSet traderSignsDisputePayoutTx(String tradeId, String payoutTxHex) {

      // gather trade info
      MoneroWallet multisigWallet = xmrWalletService.getMultisigWallet(tradeId);
      Optional<Dispute> disputeOptional = findDispute(tradeId);
      if (!disputeOptional.isPresent()) throw new RuntimeException("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + tradeId);
      Dispute dispute = disputeOptional.get();
      Contract contract = dispute.getContract();
      DisputeResult disputeResult = dispute.getDisputeResultProperty().get();

//    Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
//    BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): use contract instead of trade to get deposit tx ids when contract has deposit tx ids
//    BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getTaker().getDepositTxHash() : trade.getMaker().getDepositTxHash()).getIncomingAmount();
//    BigInteger tradeAmount = BigInteger.valueOf(contract.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
      BigInteger buyerPayoutAmount = ParsingUtils.coinToAtomicUnits(disputeResult.getBuyerPayoutAmount());
      BigInteger sellerPayoutAmount = ParsingUtils.coinToAtomicUnits(disputeResult.getSellerPayoutAmount());
      System.out.println("Buyer payout amount (with multiplier): " + buyerPayoutAmount);
      System.out.println("Seller payout amount (with multiplier): " + sellerPayoutAmount);

      // parse arbitrator-signed payout tx
      MoneroTxSet parsedTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(payoutTxHex));
      if (parsedTxSet.getTxs() == null || parsedTxSet.getTxs().size() != 1) throw new RuntimeException("Bad arbitrator-signed payout tx");  // TODO (woodser): nack
      MoneroTxWallet arbitratorSignedPayoutTx = parsedTxSet.getTxs().get(0);
      System.out.println("Parsed arbitrator-signed payout tx:\n" + arbitratorSignedPayoutTx);

      // verify payout tx has 1 or 2 destinations
      int numDestinations = arbitratorSignedPayoutTx.getOutgoingTransfer() == null || arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations() == null ? 0 : arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations().size();
      if (numDestinations != 1 && numDestinations != 2) throw new RuntimeException("Buyer-signed payout tx does not have 1 or 2 destinations");

      // get buyer and seller destinations (order not preserved)
      List<MoneroDestination> destinations = arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations();
      boolean buyerFirst = destinations.get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
      MoneroDestination buyerPayoutDestination = buyerFirst ? destinations.get(0) : numDestinations == 2 ? destinations.get(1) : null;
      MoneroDestination sellerPayoutDestination = buyerFirst ? (numDestinations == 2 ? destinations.get(1) : null) : destinations.get(0);

      // verify payout addresses
      if (buyerPayoutDestination != null && !buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new RuntimeException("Buyer payout address does not match contract");
      if (sellerPayoutDestination != null && !sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new RuntimeException("Seller payout address does not match contract");

      // verify change address is multisig's primary address
      if (!arbitratorSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new RuntimeException("Change address is not multisig wallet's primary address");

      // verify sum of outputs = destination amounts + change amount
      BigInteger destinationSum = (buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount()).add(sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount());
      if (!arbitratorSignedPayoutTx.getOutputSum().equals(destinationSum.add(arbitratorSignedPayoutTx.getChangeAmount()))) throw new RuntimeException("Sum of outputs != destination amounts + change amount");

      // verify buyer destination amount is payout amount - 1/2 tx costs
      if (buyerPayoutDestination != null) {
          BigInteger txCost = arbitratorSignedPayoutTx.getFee().add(arbitratorSignedPayoutTx.getChangeAmount());
          BigInteger expectedBuyerPayout = buyerPayoutAmount.subtract(txCost.divide(BigInteger.valueOf(2)));

          System.out.println("Dispute buyer payout amount: " + buyerPayoutAmount);
          System.out.println("Tx cost: " + txCost);
          System.out.println("Buyer destination payout amount: " + buyerPayoutDestination.getAmount());
      }

      // payout amount is dispute payout amount - 1/2 tx cost - deposit tx fee

      // TODO (woodser): VERIFY PAYOUT TX AMOUNTS WHICH CONSIDERS FEE IF LONG TRADE, EXACT AMOUNT IF SHORT TRADE

  //    if (!buyerPayoutDestination.getAmount().equals(expectedBuyerPayout)) throw new RuntimeException("Buyer destination amount is not payout amount - 1/2 tx costs, " + buyerPayoutDestination.getAmount() + " vs " + expectedBuyerPayout);

      // verify seller destination amount is payout amount - 1/2 tx costs
  //    BigInteger expectedSellerPayout = sellerPayoutAmount.subtract(txCost.divide(BigInteger.valueOf(2)));
  //    if (!sellerPayoutDestination.getAmount().equals(expectedSellerPayout)) throw new RuntimeException("Seller destination amount is not payout amount - 1/2 tx costs, " + sellerPayoutDestination.getAmount() + " vs " + expectedSellerPayout);

      // TODO (woodser): verify fee is reasonable (e.g. within 2x of fee estimate tx)

      // update multisig wallet from arbitrator
      System.out.println("Updating multisig hex from arbitrator: " + disputeResult.getArbitratorUpdatedMultisigHex());
      multisigWallet.importMultisigHex(Arrays.asList(disputeResult.getArbitratorUpdatedMultisigHex()));

      // sign arbitrator-signed payout tx
      MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(payoutTxHex);

      if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing arbitrator-signed payout tx");
      String signedMultisigTxHex = result.getSignedMultisigTxHex();
      parsedTxSet.setMultisigTxHex(signedMultisigTxHex);
      return parsedTxSet;
    }
}
