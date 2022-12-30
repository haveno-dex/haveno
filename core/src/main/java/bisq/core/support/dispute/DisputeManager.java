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

package bisq.core.support.dispute;

import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.api.CoreNotificationService;
import bisq.core.btc.wallet.Restrictions;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.monetary.Altcoin;
import bisq.core.monetary.Price;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.MarketPrice;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.SupportManager;
import bisq.core.support.dispute.DisputeResult.Winner;
import bisq.core.support.dispute.messages.DisputeClosedMessage;
import bisq.core.support.dispute.messages.DisputeOpenedMessage;
import bisq.core.support.messages.ChatMessage;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.ClosedTradableManager;
import bisq.core.trade.Contract;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeDataValidation;
import bisq.core.trade.TradeManager;
import bisq.core.trade.protocol.TradingPeer;
import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendMailboxMessageListener;

import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.MathUtils;
import bisq.common.util.Tuple2;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

import javafx.beans.property.IntegerProperty;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.math.BigInteger;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;



@Slf4j
public abstract class DisputeManager<T extends DisputeList<Dispute>> extends SupportManager {
    protected final TradeWalletService tradeWalletService;
    protected final XmrWalletService xmrWalletService;
    protected final TradeManager tradeManager;
    protected final ClosedTradableManager closedTradableManager;
    protected final OpenOfferManager openOfferManager;
    protected final PubKeyRing pubKeyRing;
    protected final DisputeListService<T> disputeListService;
    private final Config config;
    private final PriceFeedService priceFeedService;

    @Getter
    protected final ObservableList<TradeDataValidation.ValidationException> validationExceptions =
            FXCollections.observableArrayList();
    @Getter
    private final KeyPair signatureKeyPair;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public DisputeManager(P2PService p2PService,
                          TradeWalletService tradeWalletService,
                          XmrWalletService xmrWalletService,
                          CoreMoneroConnectionsService connectionService,
                          CoreNotificationService notificationService,
                          TradeManager tradeManager,
                          ClosedTradableManager closedTradableManager,
                          OpenOfferManager openOfferManager,
                          KeyRing keyRing,
                          DisputeListService<T> disputeListService,
                          Config config,
                          PriceFeedService priceFeedService) {
        super(p2PService, connectionService, notificationService);

        this.tradeWalletService = tradeWalletService;
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.closedTradableManager = closedTradableManager;
        this.openOfferManager = openOfferManager;
        this.pubKeyRing = keyRing.getPubKeyRing();
        signatureKeyPair = keyRing.getSignatureKeyPair();
        this.disputeListService = disputeListService;
        this.config = config;
        this.priceFeedService = priceFeedService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void requestPersistence() {
        disputeListService.requestPersistence();
    }

    @Override
    public NodeAddress getPeerNodeAddress(ChatMessage message) {
        Optional<Dispute> disputeOptional = findDispute(message);
        if (!disputeOptional.isPresent()) {
            log.warn("Could not find dispute for tradeId = {} traderId = {}",
                    message.getTradeId(), message.getTraderId());
            return null;
        }
        return getNodeAddressPubKeyRingTuple(disputeOptional.get()).first;
    }

    @Override
    public PubKeyRing getPeerPubKeyRing(ChatMessage message) {
        Optional<Dispute> disputeOptional = findDispute(message);
        if (!disputeOptional.isPresent()) {
            log.warn("Could not find dispute for tradeId = {} traderId = {}",
                    message.getTradeId(), message.getTraderId());
            return null;
        }

        return getNodeAddressPubKeyRingTuple(disputeOptional.get()).second;
    }

    @Override
    public List<ChatMessage> getAllChatMessages() {
        return getDisputeList().stream()
                .flatMap(dispute -> dispute.getChatMessages().stream())
                .collect(Collectors.toList());
    }

    @Override
    public boolean channelOpen(ChatMessage message) {
        return findDispute(message).isPresent();
    }

    @Override
    public void addAndPersistChatMessage(ChatMessage message) {
        findDispute(message).ifPresent(dispute -> {
            if (dispute.getChatMessages().stream().noneMatch(m -> m.getUid().equals(message.getUid()))) {
                dispute.addAndPersistChatMessage(message);
                requestPersistence();
            } else {
                log.warn("We got a chatMessage what we have already stored. UId = {} TradeId = {}",
                        message.getUid(), message.getTradeId());
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We get that message at both peers. The dispute object is in context of the trader
    public abstract void handleDisputeClosedMessage(DisputeClosedMessage disputeClosedMessage);

    public abstract NodeAddress getAgentNodeAddress(Dispute dispute);

    public abstract void cleanupDisputes();

    protected abstract String getDisputeInfo(Dispute dispute);

    protected abstract String getDisputeIntroForPeer(String disputeInfo);

    protected abstract String getDisputeIntroForDisputeCreator(String disputeInfo);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Delegates for disputeListService
    ///////////////////////////////////////////////////////////////////////////////////////////

    public IntegerProperty getNumOpenDisputes() {
        return disputeListService.getNumOpenDisputes();
    }

    public ObservableList<Dispute> getDisputesAsObservableList() {
        return disputeListService.getObservableList();
    }

    public String getNrOfDisputes(boolean isBuyer, Contract contract) {
        return disputeListService.getNrOfDisputes(isBuyer, contract);
    }

    protected T getDisputeList() {
        return disputeListService.getDisputeList();
    }

    public Set<String> getDisputedTradeIds() {
        return disputeListService.getDisputedTradeIds();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllServicesInitialized() {
        super.onAllServicesInitialized();
        disputeListService.onAllServicesInitialized();

        p2PService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onUpdatedDataReceived() {
                tryApplyMessages();
            }
        });

        connectionService.downloadPercentageProperty().addListener((observable, oldValue, newValue) -> {
            if (connectionService.isDownloadComplete())
                tryApplyMessages();
        });

        connectionService.numPeersProperty().addListener((observable, oldValue, newValue) -> {
            if (connectionService.hasSufficientPeersForBroadcast())
                tryApplyMessages();
        });

        tryApplyMessages();
        cleanupDisputes();

        List<Dispute> disputes = getDisputeList().getList();
        disputes.forEach(dispute -> {
            try {
                TradeDataValidation.validateDonationAddress(dispute, dispute.getDonationAddressOfDelayedPayoutTx());
                TradeDataValidation.validateNodeAddress(dispute, dispute.getContract().getBuyerNodeAddress(), config);
                TradeDataValidation.validateNodeAddress(dispute, dispute.getContract().getSellerNodeAddress(), config);
            } catch (TradeDataValidation.AddressException | TradeDataValidation.NodeAddressException e) {
                log.error(e.toString());
                validationExceptions.add(e);
            }
        });

        // TODO (woodser): disabled for xmr, needed?
//        TradeDataValidation.testIfAnyDisputeTriedReplay(disputes,
//                disputeReplayException -> {
//                    log.error(disputeReplayException.toString());
//                    validationExceptions.add(disputeReplayException);
//                });
    }

    public boolean isTrader(Dispute dispute) {
        return pubKeyRing.equals(dispute.getTraderPubKeyRing());
    }

    public Optional<Dispute> findOwnDispute(String tradeId) {
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return Optional.empty();
        }
        return disputeList.stream().filter(e -> e.getTradeId().equals(tradeId)).findAny();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // trader sends message to arbitrator to open dispute
    public void sendDisputeOpenedMessage(Dispute dispute,
                                            boolean reOpen,
                                            String updatedMultisigHex,
                                            ResultHandler resultHandler,
                                            FaultHandler faultHandler) {

        // get trade
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", dispute.getTradeId());
            return;
        }

        log.info("Sending {} for {} {}, dispute {}",
                DisputeOpenedMessage.class.getSimpleName(), trade.getClass().getSimpleName(),
                dispute.getTradeId(), dispute.getId());

        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return;
        }

        synchronized (disputeList) {
            if (disputeList.contains(dispute)) {
                String msg = "We got a dispute msg what we have already stored. TradeId = " + dispute.getTradeId() + ", DisputeId = " + dispute.getId();
                log.warn(msg);
                faultHandler.handleFault(msg, new DisputeAlreadyOpenException());
                return;
            }

            Optional<Dispute> storedDisputeOptional = findDispute(dispute);
            if (!storedDisputeOptional.isPresent() || reOpen) {
                String disputeInfo = getDisputeInfo(dispute);
                String sysMsg = dispute.isSupportTicket() ?
                        Res.get("support.youOpenedTicket", disputeInfo, Version.VERSION) :
                        Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);

                ChatMessage chatMessage = new ChatMessage(
                        getSupportType(),
                        dispute.getTradeId(),
                        pubKeyRing.hashCode(),
                        false,
                        Res.get("support.systemMsg", sysMsg),
                        p2PService.getAddress());
                chatMessage.setSystemMessage(true);
                dispute.addAndPersistChatMessage(chatMessage);
                if (!reOpen) {
                    disputeList.add(dispute);
                }

                NodeAddress agentNodeAddress = getAgentNodeAddress(dispute);
                DisputeOpenedMessage disputeOpenedMessage = new DisputeOpenedMessage(dispute,
                        p2PService.getAddress(),
                        UUID.randomUUID().toString(),
                        getSupportType(),
                        updatedMultisigHex, 
                        trade.getBuyer().getPaymentSentMessage());
                log.info("Send {} to peer {}. tradeId={}, openNewDisputeMessage.uid={}, " +
                        "chatMessage.uid={}",
                        disputeOpenedMessage.getClass().getSimpleName(), agentNodeAddress,
                        disputeOpenedMessage.getTradeId(), disputeOpenedMessage.getUid(),
                        chatMessage.getUid());
                mailboxMessageService.sendEncryptedMailboxMessage(agentNodeAddress,
                        dispute.getAgentPubKeyRing(),
                        disputeOpenedMessage,
                        new SendMailboxMessageListener() {
                            @Override
                            public void onArrived() {
                                log.info("{} arrived at peer {}. tradeId={}, openNewDisputeMessage.uid={}, " +
                                        "chatMessage.uid={}",
                                        disputeOpenedMessage.getClass().getSimpleName(), agentNodeAddress,
                                        disputeOpenedMessage.getTradeId(), disputeOpenedMessage.getUid(),
                                        chatMessage.getUid());

                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setArrived(true);
                                trade.setDisputeState(Trade.DisputeState.DISPUTE_OPENED);
                                requestPersistence();
                                resultHandler.handleResult();
                            }

                            @Override
                            public void onStoredInMailbox() {
                                log.info("{} stored in mailbox for peer {}. tradeId={}, openNewDisputeMessage.uid={}, " +
                                                "chatMessage.uid={}",
                                        disputeOpenedMessage.getClass().getSimpleName(), agentNodeAddress,
                                        disputeOpenedMessage.getTradeId(), disputeOpenedMessage.getUid(),
                                        chatMessage.getUid());

                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setStoredInMailbox(true);
                                trade.setDisputeState(Trade.DisputeState.DISPUTE_OPENED);
                                requestPersistence();
                                resultHandler.handleResult();
                            }

                            @Override
                            public void onFault(String errorMessage) {
                                log.error("{} failed: Peer {}. tradeId={}, openNewDisputeMessage.uid={}, " +
                                        "chatMessage.uid={}, errorMessage={}",
                                        disputeOpenedMessage.getClass().getSimpleName(), agentNodeAddress,
                                        disputeOpenedMessage.getTradeId(), disputeOpenedMessage.getUid(),
                                        chatMessage.getUid(), errorMessage);

                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setSendMessageError(errorMessage);
                                requestPersistence();
                                faultHandler.handleFault("Sending dispute message failed: " +
                                        errorMessage, new DisputeMessageDeliveryFailedException());
                            }
                        });
            } else {
                String msg = "We got a dispute already open for that trade and trading peer.\n" +
                        "TradeId = " + dispute.getTradeId();
                log.warn(msg);
                faultHandler.handleFault(msg, new DisputeAlreadyOpenException());
            }
        }

        requestPersistence();
    }

    // arbitrator receives dispute opened message from opener, opener's peer receives from arbitrator
    protected void handleDisputeOpenedMessage(DisputeOpenedMessage message) {
        Dispute dispute = message.getDispute();
        log.info("{}.onDisputeOpenedMessage() with trade {}, dispute {}", getClass().getSimpleName(), dispute.getTradeId(), dispute.getId());

        // intialize
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return;
        }
        dispute.setSupportType(message.getSupportType());
        dispute.setState(Dispute.State.NEW); // TODO: unused, remove?
        Contract contract = dispute.getContract();

        // validate dispute
        try {
            TradeDataValidation.validatePaymentAccountPayload(dispute);
            TradeDataValidation.validateDonationAddress(dispute.getDonationAddressOfDelayedPayoutTx());
            //TradeDataValidation.testIfDisputeTriesReplay(dispute, disputeList.getList()); // TODO (woodser): disabled for xmr, needed?
            TradeDataValidation.validateNodeAddress(dispute, contract.getBuyerNodeAddress(), config);
            TradeDataValidation.validateNodeAddress(dispute, contract.getSellerNodeAddress(), config);
        } catch (TradeDataValidation.AddressException |
                TradeDataValidation.NodeAddressException |
                TradeDataValidation.InvalidPaymentAccountPayloadException e) {
            log.error(e.toString());
            validationExceptions.add(e);
        }

        // get trade
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", dispute.getTradeId());
            return;
        }

        // get sender
        PubKeyRing senderPubKeyRing = trade.isArbitrator() ? (dispute.isDisputeOpenerIsBuyer() ? contract.getBuyerPubKeyRing() : contract.getSellerPubKeyRing()) : trade.getArbitrator().getPubKeyRing();
        TradingPeer sender = trade.getTradingPeer(senderPubKeyRing);
        if (sender == null) throw new RuntimeException("Pub key ring is not from arbitrator, buyer, or seller");

        // message to trader is expected from arbitrator
        if (!trade.isArbitrator() && sender != trade.getArbitrator()) {
            throw new RuntimeException(message.getClass().getSimpleName() + " to trader is expected only from arbitrator");
        }

        // arbitrator verifies signature of payment sent message if given
        if (trade.isArbitrator() && message.getPaymentSentMessage() != null) {
            HavenoUtils.verifyPaymentSentMessage(trade, message.getPaymentSentMessage());
            trade.getBuyer().setUpdatedMultisigHex(message.getPaymentSentMessage().getUpdatedMultisigHex());
            trade.setStateIfProgress(sender == trade.getBuyer() ? Trade.State.BUYER_SENT_PAYMENT_SENT_MSG : Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG);
        }

        // update multisig hex
        if (message.getUpdatedMultisigHex() != null) sender.setUpdatedMultisigHex(message.getUpdatedMultisigHex());

        // update peer node address
        // TODO: tests can reuse the same addresses so nullify equal peer
        sender.setNodeAddress(message.getSenderNodeAddress());

        // add chat message with price info
        if (trade instanceof ArbitratorTrade) addPriceInfoMessage(dispute, 0);

        // add dispute
        String errorMessage = null;
        synchronized (disputeList) {
            if (!disputeList.contains(dispute)) {
                Optional<Dispute> storedDisputeOptional = findDispute(dispute);
                if (!storedDisputeOptional.isPresent()) {
                    disputeList.add(dispute);
                    trade.setDisputeState(Trade.DisputeState.DISPUTE_OPENED);

                    // send dispute opened message to peer if arbitrator
                    if (trade.isArbitrator()) sendDisputeOpenedMessageToPeer(dispute, contract, dispute.isDisputeOpenerIsBuyer() ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing(), trade.getSelf().getUpdatedMultisigHex());
                    tradeManager.requestPersistence();
                    errorMessage = null;
                } else {
                    // valid case if both have opened a dispute and agent was not online
                    log.debug("We got a dispute already open for that trade and trading peer. TradeId = {}",
                            dispute.getTradeId());
                }
            } else {
              errorMessage = "We got a dispute msg that we have already stored. TradeId = " + dispute.getTradeId();
              log.warn(errorMessage);
            }
        }

        // use chat message instead of open dispute message for the ack
        ObservableList<ChatMessage> messages = message.getDispute().getChatMessages();
        if (!messages.isEmpty()) {
            ChatMessage msg = messages.get(0);
            sendAckMessage(msg, senderPubKeyRing, errorMessage == null, errorMessage);
        }

        // add chat message with mediation info if applicable // TODO: not applicable in haveno
        addMediationResultMessage(dispute);

        requestPersistence();
    }

    // arbitrator sends dispute opened message to opener's peer
    private void sendDisputeOpenedMessageToPeer(Dispute disputeFromOpener,
                                              Contract contractFromOpener,
                                              PubKeyRing pubKeyRing,
                                              String updatedMultisigHex) {
        log.info("{}.sendPeerOpenedDisputeMessage() with trade {}, dispute {}", getClass().getSimpleName(), disputeFromOpener.getTradeId(), disputeFromOpener.getId());
        // We delay a bit for sending the message to the peer to allow that a openDispute message from the peer is
        // being used as the valid msg. If dispute agent was offline and both peer requested we want to see the correct
        // message and not skip the system message of the peer as it would be the case if we have created the system msg
        // from the code below.
        UserThread.runAfter(() -> doSendPeerOpenedDisputeMessage(disputeFromOpener,
                contractFromOpener,
                pubKeyRing,
                updatedMultisigHex),
                100, TimeUnit.MILLISECONDS);
    }

    private void doSendPeerOpenedDisputeMessage(Dispute disputeFromOpener,
                                                Contract contractFromOpener,
                                                PubKeyRing pubKeyRing,
                                                String updatedMultisigHex) {
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return;
        }

        Dispute dispute = new Dispute(new Date().getTime(),
                disputeFromOpener.getTradeId(),
                pubKeyRing.hashCode(),
                false,
                disputeFromOpener.isDisputeOpenerIsBuyer(),
                disputeFromOpener.isDisputeOpenerIsMaker(),
                pubKeyRing,
                disputeFromOpener.getTradeDate().getTime(),
                disputeFromOpener.getTradePeriodEnd().getTime(),
                contractFromOpener,
                disputeFromOpener.getContractHash(),
                disputeFromOpener.getDepositTxSerialized(),
                disputeFromOpener.getPayoutTxSerialized(),
                disputeFromOpener.getDepositTxId(),
                disputeFromOpener.getPayoutTxId(),
                disputeFromOpener.getContractAsJson(),
                disputeFromOpener.getMakerContractSignature(),
                disputeFromOpener.getTakerContractSignature(),
                disputeFromOpener.getMakerPaymentAccountPayload(),
                disputeFromOpener.getTakerPaymentAccountPayload(),
                disputeFromOpener.getAgentPubKeyRing(),
                disputeFromOpener.isSupportTicket(),
                disputeFromOpener.getSupportType());
        dispute.setExtraDataMap(disputeFromOpener.getExtraDataMap());
        dispute.setDelayedPayoutTxId(disputeFromOpener.getDelayedPayoutTxId());
        dispute.setDonationAddressOfDelayedPayoutTx(disputeFromOpener.getDonationAddressOfDelayedPayoutTx());

        Optional<Dispute> storedDisputeOptional = findDispute(dispute);

        // Valid case if both have opened a dispute and agent was not online.
        if (storedDisputeOptional.isPresent()) {
            log.info("We got a dispute already open for that trade and trading peer. TradeId = {}", dispute.getTradeId());
            return;
        }

        String disputeInfo = getDisputeInfo(dispute);
        String disputeMessage = getDisputeIntroForPeer(disputeInfo);
        String sysMsg = dispute.isSupportTicket() ?
                Res.get("support.peerOpenedTicket", disputeInfo, Version.VERSION)
                : disputeMessage;
        ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                pubKeyRing.hashCode(),
                false,
                Res.get("support.systemMsg", sysMsg),
                p2PService.getAddress());
        chatMessage.setSystemMessage(true);
        dispute.addAndPersistChatMessage(chatMessage);

        addPriceInfoMessage(dispute, 0);

        synchronized (disputeList) {
            disputeList.add(dispute);
        }

        // get trade
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", dispute.getTradeId());
            return;
        }

        // We mirrored dispute already!
        Contract contract = dispute.getContract();
        PubKeyRing peersPubKeyRing = dispute.isDisputeOpenerIsBuyer() ? contract.getSellerPubKeyRing() : contract.getBuyerPubKeyRing();
        NodeAddress peersNodeAddress = dispute.isDisputeOpenerIsBuyer() ? contract.getSellerNodeAddress() : contract.getBuyerNodeAddress();
        DisputeOpenedMessage peerOpenedDisputeMessage = new DisputeOpenedMessage(dispute,
                p2PService.getAddress(),
                UUID.randomUUID().toString(),
                getSupportType(),
                updatedMultisigHex,
                trade.getSelf().getPaymentSentMessage());

        log.info("Send {} to peer {}. tradeId={}, peerOpenedDisputeMessage.uid={}, chatMessage.uid={}",
                peerOpenedDisputeMessage.getClass().getSimpleName(), peersNodeAddress,
                peerOpenedDisputeMessage.getTradeId(), peerOpenedDisputeMessage.getUid(),
                chatMessage.getUid());
        mailboxMessageService.sendEncryptedMailboxMessage(peersNodeAddress,
                peersPubKeyRing,
                peerOpenedDisputeMessage,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at peer {}. tradeId={}, peerOpenedDisputeMessage.uid={}, " +
                                        "chatMessage.uid={}",
                                peerOpenedDisputeMessage.getClass().getSimpleName(), peersNodeAddress,
                                peerOpenedDisputeMessage.getTradeId(), peerOpenedDisputeMessage.getUid(),
                                chatMessage.getUid());

                        // We use the chatMessage wrapped inside the peerOpenedDisputeMessage for
                        // the state, as that is displayed to the user and we only persist that msg
                        chatMessage.setArrived(true);
                        requestPersistence();
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("{} stored in mailbox for peer {}. tradeId={}, peerOpenedDisputeMessage.uid={}, " +
                                        "chatMessage.uid={}",
                                peerOpenedDisputeMessage.getClass().getSimpleName(), peersNodeAddress,
                                peerOpenedDisputeMessage.getTradeId(), peerOpenedDisputeMessage.getUid(),
                                chatMessage.getUid());

                        // We use the chatMessage wrapped inside the peerOpenedDisputeMessage for
                        // the state, as that is displayed to the user and we only persist that msg
                        chatMessage.setStoredInMailbox(true);
                        requestPersistence();
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("{} failed: Peer {}. tradeId={}, peerOpenedDisputeMessage.uid={}, " +
                                        "chatMessage.uid={}, errorMessage={}",
                                peerOpenedDisputeMessage.getClass().getSimpleName(), peersNodeAddress,
                                peerOpenedDisputeMessage.getTradeId(), peerOpenedDisputeMessage.getUid(),
                                chatMessage.getUid(), errorMessage);

                        // We use the chatMessage wrapped inside the peerOpenedDisputeMessage for
                        // the state, as that is displayed to the user and we only persist that msg
                        chatMessage.setSendMessageError(errorMessage);
                        requestPersistence();
                    }
                }
        );
        requestPersistence();
    }

    // arbitrator sends result to trader when their dispute is closed
    public void closeDisputeTicket(DisputeResult disputeResult, Dispute dispute, String summaryText, MoneroTxWallet payoutTx, ResultHandler resultHandler, FaultHandler faultHandler) {
        try {

            // get trade
            Trade trade = tradeManager.getTrade(dispute.getTradeId());
            if (trade == null) throw new RuntimeException("Dispute trade " + dispute.getTradeId() + " does not exist");

            // create dispute payout tx if not given
            if (payoutTx == null) payoutTx = createDisputePayoutTx(trade, dispute, disputeResult); // can be null if already published or we don't have receiver's multisig hex

            // persist result in dispute's chat message
            ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                dispute.getTraderPubKeyRing().hashCode(),
                false,
                summaryText,
                p2PService.getAddress());
            disputeResult.setChatMessage(chatMessage);
            dispute.addAndPersistChatMessage(chatMessage);

            // create dispute closed message
            TradingPeer receiver = trade.getTradingPeer(dispute.getTraderPubKeyRing());
            String unsignedPayoutTxHex = payoutTx == null ? null : payoutTx.getTxSet().getMultisigTxHex();
            TradingPeer receiverPeer = receiver == trade.getBuyer() ? trade.getSeller() : trade.getBuyer();
            boolean deferPublishPayout = unsignedPayoutTxHex != null && receiverPeer.getUpdatedMultisigHex() != null && trade.getDisputeState().ordinal() >= Trade.DisputeState.ARBITRATOR_SAW_ARRIVED_DISPUTE_CLOSED_MSG.ordinal() ;
            DisputeClosedMessage disputeClosedMessage = new DisputeClosedMessage(disputeResult,
                    p2PService.getAddress(),
                    UUID.randomUUID().toString(),
                    getSupportType(),
                    trade.getSelf().getUpdatedMultisigHex(),
                    trade.isPayoutPublished() ? null : unsignedPayoutTxHex, // include dispute payout tx if unpublished and arbitrator has their updated multisig info
                    deferPublishPayout); // instruct trader to defer publishing payout tx because peer is expected to publish imminently

            // send dispute closed message
            log.info("Send {} to trader {}. tradeId={}, {}.uid={}, chatMessage.uid={}",
                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                    disputeClosedMessage.getClass().getSimpleName(), disputeClosedMessage.getTradeId(),
                    disputeClosedMessage.getUid(), chatMessage.getUid());
            mailboxMessageService.sendEncryptedMailboxMessage(receiver.getNodeAddress(),
                    dispute.getTraderPubKeyRing(),
                    disputeClosedMessage,
                    new SendMailboxMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at trader {}. tradeId={}, disputeClosedMessage.uid={}, " +
                                            "chatMessage.uid={}",
                                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                                    disputeClosedMessage.getTradeId(), disputeClosedMessage.getUid(),
                                    chatMessage.getUid());

                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            chatMessage.setArrived(true);
                            trade.setDisputeStateIfProgress(Trade.DisputeState.ARBITRATOR_SAW_ARRIVED_DISPUTE_CLOSED_MSG);
                            trade.syncWalletNormallyForMs(30000);
                            requestPersistence();
                            resultHandler.handleResult();
                        }

                        @Override
                        public void onStoredInMailbox() {
                            log.info("{} stored in mailbox for trader {}. tradeId={}, DisputeClosedMessage.uid={}, " +
                                            "chatMessage.uid={}",
                                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                                    disputeClosedMessage.getTradeId(), disputeClosedMessage.getUid(),
                                    chatMessage.getUid());

                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            chatMessage.setStoredInMailbox(true);
                            Trade trade = tradeManager.getTrade(dispute.getTradeId());
                            trade.setDisputeStateIfProgress(Trade.DisputeState.ARBITRATOR_STORED_IN_MAILBOX_DISPUTE_CLOSED_MSG);
                            requestPersistence();
                            resultHandler.handleResult();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("{} failed: Trader {}. tradeId={}, DisputeClosedMessage.uid={}, " +
                                            "chatMessage.uid={}, errorMessage={}",
                                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                                    disputeClosedMessage.getTradeId(), disputeClosedMessage.getUid(),
                                    chatMessage.getUid(), errorMessage);

                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            chatMessage.setSendMessageError(errorMessage);
                            trade.setDisputeStateIfProgress(Trade.DisputeState.ARBITRATOR_SEND_FAILED_DISPUTE_CLOSED_MSG);
                            requestPersistence();
                            faultHandler.handleFault(errorMessage, new RuntimeException(errorMessage));
                        }
                    }
            );

            // save state
            if (payoutTx != null) {
                trade.setPayoutTx(payoutTx);
                trade.setPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
            }
            trade.setDisputeStateIfProgress(Trade.DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG);
            requestPersistence();
        } catch (Exception e) {
            faultHandler.handleFault(e.getMessage(), e);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MoneroTxWallet createDisputePayoutTx(Trade trade, Dispute dispute, DisputeResult disputeResult) {

        // sync and save wallet
        trade.syncWallet();
        trade.saveWallet();

        // create unsigned dispute payout tx if not already published and arbitrator has trader's updated multisig info
        TradingPeer receiver = trade.getTradingPeer(dispute.getTraderPubKeyRing());
        if (!trade.isPayoutPublished() && receiver.getUpdatedMultisigHex() != null) {

            // import multisig hex
            MoneroWallet multisigWallet = trade.getWallet();
            List<String> updatedMultisigHexes = new ArrayList<String>();
            if (trade.getBuyer().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getBuyer().getUpdatedMultisigHex());
            if (trade.getSeller().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getSeller().getUpdatedMultisigHex());
            if (!updatedMultisigHexes.isEmpty()) {
                multisigWallet.importMultisigHex(updatedMultisigHexes.toArray(new String[0])); // TODO (monero-project): fails if multisig hex imported individually
            }

            // sync and save wallet
            trade.syncWallet();
            trade.saveWallet();

            // create unsigned dispute payout tx
            if (!trade.isPayoutPublished()) {
                log.info("Arbitrator creating unsigned dispute payout tx for trade {}", trade.getId());
                try {

                    // trade wallet must be synced
                    if (trade.getWallet().isMultisigImportNeeded()) throw new RuntimeException("Arbitrator's wallet needs updated multisig hex to create payout tx which means a trader must have already broadcast the payout tx for trade " + dispute.getTradeId());

                    // collect winner and loser payout address and amounts
                    Contract contract = dispute.getContract();
                    String winnerPayoutAddress = disputeResult.getWinner() == Winner.BUYER ?
                            (contract.isBuyerMakerAndSellerTaker() ? contract.getMakerPayoutAddressString() : contract.getTakerPayoutAddressString()) :
                            (contract.isBuyerMakerAndSellerTaker() ? contract.getTakerPayoutAddressString() : contract.getMakerPayoutAddressString());
                    String loserPayoutAddress = winnerPayoutAddress.equals(contract.getMakerPayoutAddressString()) ? contract.getTakerPayoutAddressString() : contract.getMakerPayoutAddressString();
                    BigInteger winnerPayoutAmount = HavenoUtils.coinToAtomicUnits(disputeResult.getWinner() == Winner.BUYER ? disputeResult.getBuyerPayoutAmount() : disputeResult.getSellerPayoutAmount());
                    BigInteger loserPayoutAmount = HavenoUtils.coinToAtomicUnits(disputeResult.getWinner() == Winner.BUYER ? disputeResult.getSellerPayoutAmount() : disputeResult.getBuyerPayoutAmount());

                    // create transaction to get fee estimate
                    MoneroTxConfig txConfig = new MoneroTxConfig().setAccountIndex(0).setRelay(false);
                    if (winnerPayoutAmount.compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(winnerPayoutAddress, winnerPayoutAmount.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(10))); // reduce payment amount to get fee of similar tx
                    if (loserPayoutAmount.compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(loserPayoutAddress, loserPayoutAmount.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(10)));
                    MoneroTxWallet feeEstimateTx = trade.getWallet().createTx(txConfig);

                    // create payout tx by increasing estimated fee until successful
                    MoneroTxWallet payoutTx = null;
                    int numAttempts = 0;
                    while (payoutTx == null && numAttempts < 50) {
                        BigInteger feeEstimate = feeEstimateTx.getFee().add(feeEstimateTx.getFee().multiply(BigInteger.valueOf(numAttempts)).divide(BigInteger.valueOf(10))); // add 1/10th of fee until tx is successful
                        txConfig = new MoneroTxConfig().setAccountIndex(0).setRelay(false);
                        if (winnerPayoutAmount.compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(winnerPayoutAddress, winnerPayoutAmount.subtract(loserPayoutAmount.equals(BigInteger.ZERO) ? feeEstimate : BigInteger.ZERO)); // winner only pays fee if loser gets 0
                        if (loserPayoutAmount.compareTo(BigInteger.ZERO) > 0) {
                            if (loserPayoutAmount.compareTo(feeEstimate) < 0) throw new RuntimeException("Loser payout is too small to cover the mining fee");
                            if (loserPayoutAmount.compareTo(feeEstimate) > 0) txConfig.addDestination(loserPayoutAddress, loserPayoutAmount.subtract(feeEstimate)); // loser pays fee
                        }
                        numAttempts++;
                        try {
                            payoutTx = trade.getWallet().createTx(txConfig);
                        } catch (MoneroError e) {
                            // exception expected // TODO: better way of estimating fee?
                        }
                    }
                    if (payoutTx == null) throw new RuntimeException("Failed to generate dispute payout tx after " + numAttempts + " attempts");
                    log.info("Dispute payout transaction generated on attempt {}", numAttempts);

                    // save updated multisig hex
                    trade.getSelf().setUpdatedMultisigHex(trade.getWallet().exportMultisigHex());
                    return payoutTx;
                } catch (Exception e) {
                    if (!trade.isPayoutPublished()) throw e;
                }
            }
        }
        return null; // can be null if already published or we don't have receiver's multisig hex
    }

    private Tuple2<NodeAddress, PubKeyRing> getNodeAddressPubKeyRingTuple(Dispute dispute) {
        PubKeyRing receiverPubKeyRing = null;
        NodeAddress peerNodeAddress = null;
        if (isTrader(dispute)) {
            receiverPubKeyRing = dispute.getAgentPubKeyRing();
            peerNodeAddress = getAgentNodeAddress(dispute);
        } else if (isAgent(dispute)) {
            receiverPubKeyRing = dispute.getTraderPubKeyRing();
            Contract contract = dispute.getContract();
            if (contract.getBuyerPubKeyRing().equals(receiverPubKeyRing))
                peerNodeAddress = contract.getBuyerNodeAddress();
            else
                peerNodeAddress = contract.getSellerNodeAddress();
        } else {
            log.error("That must not happen. Trader cannot communicate to other trader.");
        }
        return new Tuple2<>(peerNodeAddress, receiverPubKeyRing);
    }

    private boolean isAgent(Dispute dispute) {
        return pubKeyRing.equals(dispute.getAgentPubKeyRing());
    }

    private Optional<Dispute> findDispute(Dispute dispute) {
        return findDispute(dispute.getTradeId(), dispute.getTraderId());
    }

    protected Optional<Dispute> findDispute(DisputeResult disputeResult) {
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        return findDispute(disputeResult.getTradeId(), disputeResult.getTraderId());
    }

    private Optional<Dispute> findDispute(ChatMessage message) {
        return findDispute(message.getTradeId(), message.getTraderId());
    }

    protected Optional<Dispute> findDispute(String tradeId, int traderId) {
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return Optional.empty();
        }
        return disputeList.stream()
                .filter(e -> e.getTradeId().equals(tradeId) && e.getTraderId() == traderId)
                .findAny();
    }

    public Optional<Dispute> findDispute(String tradeId) {
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return Optional.empty();
        }
        return disputeList.stream()
                .filter(e -> e.getTradeId().equals(tradeId))
                .findAny();
    }

    public List<Dispute> findDisputes(String tradeId) {
        T disputeList = getDisputeList();
        if (disputeList == null) return new ArrayList<Dispute>();
        return disputeList.stream()
                .filter(e -> e.getTradeId().equals(tradeId))
                .collect(Collectors.toList());
    }

    public Optional<Dispute> findDisputeById(String disputeId) {
        T disputeList = getDisputeList();
        if (disputeList == null) {
            log.warn("disputes is null");
            return Optional.empty();
        }
        return disputeList.stream()
                .filter(e -> e.getId().equals(disputeId))
                .findAny();
    }

    public Optional<Trade> findTrade(Dispute dispute) {
        Optional<Trade> retVal = tradeManager.getOpenTrade(dispute.getTradeId());
        if (!retVal.isPresent()) {
            retVal = tradeManager.getClosedTrade(dispute.getTradeId());
        }
        return retVal;
    }

    private void addMediationResultMessage(Dispute dispute) {
        // In case of refundAgent we add a message with the mediatorsDisputeSummary. Only visible for refundAgent.
        if (dispute.getMediatorsDisputeResult() != null) {
            String mediatorsDisputeResult = Res.get("support.mediatorsDisputeSummary", dispute.getMediatorsDisputeResult());
            ChatMessage mediatorsDisputeClosedMessage = new ChatMessage(
                    getSupportType(),
                    dispute.getTradeId(),
                    pubKeyRing.hashCode(),
                    false,
                    mediatorsDisputeResult,
                    p2PService.getAddress());
            mediatorsDisputeClosedMessage.setSystemMessage(true);
            dispute.addAndPersistChatMessage(mediatorsDisputeClosedMessage);
            requestPersistence();
        }
    }

    public void addMediationReOpenedMessage(Dispute dispute, boolean senderIsTrader) {
        ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                dispute.getTraderId(),
                senderIsTrader,
                Res.get("support.info.disputeReOpened"),
                p2PService.getAddress());
        chatMessage.setSystemMessage(false);
        dispute.addAndPersistChatMessage(chatMessage);
        this.sendChatMessage(chatMessage);
        requestPersistence();
    }

    // If price was going down between take offer time and open dispute time the buyer has an incentive to
    // not send the payment but to try to make a new trade with the better price. We risks to lose part of the
    // security deposit (in mediation we will always get back 0.003 BTC to keep some incentive to accept mediated
    // proposal). But if gain is larger than this loss he has economically an incentive to default in the trade.
    // We do all those calculations to give a hint to mediators to detect option trades.
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        if (!priceFeedService.hasPrices()) {
            if (counter < 3) {
                log.info("Price provider has still no data. This is expected at startup. We try again in 10 sec.");
                UserThread.runAfter(() -> addPriceInfoMessage(dispute, counter + 1), 10);
            } else {
                log.warn("Price provider still has no data after 3 repeated requests and 30 seconds delay. We give up.");
            }
            return;
        }

        Contract contract = dispute.getContract();
        OfferPayload offerPayload = contract.getOfferPayload();
        Price priceAtDisputeOpening = getPrice(offerPayload.getCurrencyCode());
        if (priceAtDisputeOpening == null) {
            log.info("Price provider did not provide a price for {}. " +
                            "This is expected if this currency is not supported by the price providers.",
                    offerPayload.getCurrencyCode());
            return;
        }

        // The amount we would get if we do a new trade with current price
        Coin potentialAmountAtDisputeOpening = priceAtDisputeOpening.getAmountByVolume(contract.getTradeVolume());
        Coin buyerSecurityDeposit = Coin.valueOf(offerPayload.getBuyerSecurityDeposit());
        Coin minRefundAtMediatedDispute = Restrictions.getMinRefundAtMediatedDispute();
        // minRefundAtMediatedDispute is always larger as buyerSecurityDeposit at mediated payout, we ignore refund agent case here as there it can be 0.
        Coin maxLossSecDeposit = buyerSecurityDeposit.subtract(minRefundAtMediatedDispute);
        Coin tradeAmount = contract.getTradeAmount();
        Coin potentialGain = potentialAmountAtDisputeOpening.subtract(tradeAmount).subtract(maxLossSecDeposit);
        String optionTradeDetails;
        // We don't translate those strings (yet) as it is only displayed to mediators/arbitrators.
        String headline;
        if (potentialGain.isPositive()) {
            headline = "This might be a potential option trade!";
            optionTradeDetails = "\nBTC amount calculated with price at dispute opening: " + potentialAmountAtDisputeOpening.toFriendlyString() +
                    "\nMax loss of security deposit is: " + maxLossSecDeposit.toFriendlyString() +
                    "\nPossible gain from an option trade is: " + potentialGain.toFriendlyString();
        } else {
            headline = "It does not appear to be an option trade.";
            optionTradeDetails = "\nBTC amount calculated with price at dispute opening: " + potentialAmountAtDisputeOpening.toFriendlyString() +
                    "\nMax loss of security deposit is: " + maxLossSecDeposit.toFriendlyString() +
                    "\nPossible loss from an option trade is: " + potentialGain.multiply(-1).toFriendlyString();
        }

        String percentagePriceDetails = offerPayload.isUseMarketBasedPrice() ?
                " (market based price was used: " + offerPayload.getMarketPriceMarginPct() * 100 + "%)" :
                " (fix price was used)";

        String priceInfoText = "System message: " + headline +
                "\n\nTrade price: " + contract.getPrice().toFriendlyString() + percentagePriceDetails +
                "\nTrade amount: " + tradeAmount.toFriendlyString() +
                "\nPrice at dispute opening: " + priceAtDisputeOpening.toFriendlyString() +
                optionTradeDetails;

        // We use the existing msg to copy over the users data
        ChatMessage priceInfoMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                pubKeyRing.hashCode(),
                false,
                priceInfoText,
                p2PService.getAddress());
        priceInfoMessage.setSystemMessage(true);
        dispute.addAndPersistChatMessage(priceInfoMessage);
        requestPersistence();
    }

    @Nullable
    private Price getPrice(String currencyCode) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        if (marketPrice != null && marketPrice.isRecentExternalPriceAvailable()) {
            double marketPriceAsDouble = marketPrice.getPrice();
            try {
                int precision = CurrencyUtil.isCryptoCurrency(currencyCode) ?
                        Altcoin.SMALLEST_UNIT_EXPONENT :
                        Fiat.SMALLEST_UNIT_EXPONENT;
                double scaled = MathUtils.scaleUpByPowerOf10(marketPriceAsDouble, precision);
                long roundedToLong = MathUtils.roundDoubleToLong(scaled);
                return Price.valueOf(currencyCode, roundedToLong);
            } catch (Exception e) {
                log.error("Exception at getPrice / parseToFiat: " + e.toString());
                return null;
            }
        } else {
            return null;
        }
    }
}
