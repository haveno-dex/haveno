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

package haveno.core.support.dispute;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.FaultHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.util.MathUtils;
import haveno.common.util.Tuple2;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.SupportManager;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendMailboxMessageListener;
import javafx.beans.property.IntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import javax.annotation.Nullable;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.math.BigInteger;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;



@Slf4j
public abstract class DisputeManager<T extends DisputeList<Dispute>> extends SupportManager {
    protected final TradeWalletService tradeWalletService;
    protected final XmrWalletService xmrWalletService;
    protected final ClosedTradableManager closedTradableManager;
    protected final OpenOfferManager openOfferManager;
    protected final KeyRing keyRing;
    protected final DisputeListService<T> disputeListService;
    private final Config config;
    private final PriceFeedService priceFeedService;
    protected String pendingOutgoingMessage;

    @Getter
    protected final ObservableList<DisputeValidation.ValidationException> validationExceptions =
            FXCollections.observableArrayList();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public DisputeManager(P2PService p2PService,
                          TradeWalletService tradeWalletService,
                          XmrWalletService xmrWalletService,
                          XmrConnectionService xmrConnectionService,
                          CoreNotificationService notificationService,
                          TradeManager tradeManager,
                          ClosedTradableManager closedTradableManager,
                          OpenOfferManager openOfferManager,
                          KeyRing keyRing,
                          DisputeListService<T> disputeListService,
                          Config config,
                          PriceFeedService priceFeedService) {
        super(p2PService, xmrConnectionService, xmrWalletService, notificationService, tradeManager);

        this.tradeWalletService = tradeWalletService;
        this.xmrWalletService = xmrWalletService;
        this.closedTradableManager = closedTradableManager;
        this.openOfferManager = openOfferManager;
        this.keyRing = keyRing;
        this.disputeListService = disputeListService;
        this.config = config;
        this.priceFeedService = priceFeedService;
        clearPendingMessage();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public KeyPair getSignatureKeyPair() {
        return keyRing.getSignatureKeyPair();
    }

    @Override
    public void requestPersistence() {
        disputeListService.requestPersistence();
    }

    protected void requestPersistence(Trade trade) {
        trade.requestPersistence();
        disputeListService.requestPersistence();
    }

    @Override
    public NodeAddress getPeerNodeAddress(ChatMessage message) {
        Optional<Dispute> disputeOptional = findDispute(message);
        if (disputeOptional.isEmpty()) {
            log.warn("Could not find dispute for tradeId = {} traderId = {}",
                    message.getTradeId(), message.getTraderId());
            return null;
        }
        return getNodeAddressPubKeyRingTuple(disputeOptional.get()).first;
    }

    @Override
    public PubKeyRing getPeerPubKeyRing(ChatMessage message) {
        Optional<Dispute> disputeOptional = findDispute(message);
        if (disputeOptional.isEmpty()) {
            log.warn("Could not find dispute for tradeId = {} traderId = {}",
                    message.getTradeId(), message.getTraderId());
            return null;
        }

        return getNodeAddressPubKeyRingTuple(disputeOptional.get()).second;
    }

    @Override
    public List<ChatMessage> getAllChatMessages(String tradeId) {
        synchronized (getDisputeList().getObservableList()) {
            return getDisputeList().stream()
                    .filter(dispute -> dispute.getTradeId().equals(tradeId))
                    .flatMap(dispute -> dispute.getChatMessages().stream())
                    .collect(Collectors.toList());
        }
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
                log.warn("We got a chatMessage that we have already stored. UId = {} TradeId = {}",
                        message.getUid(), message.getTradeId());
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We get this message at both peers. The dispute object is in context of the trader
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
        synchronized(disputeListService.getDisputeList().getObservableList()) {
            return disputeListService.getObservableList();
        }
    }

    public String getNrOfDisputes(boolean isBuyer, Contract contract) {
        return disputeListService.getNrOfDisputes(isBuyer, contract);
    }

    protected T getDisputeList() {
        synchronized(disputeListService.getDisputeList().getObservableList()) {
            return disputeListService.getDisputeList();
        }
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
            public void onDataReceived() {
                tryApplyMessages();
            }
        });

        xmrWalletService.downloadPercentageProperty().addListener((observable, oldValue, newValue) -> {
            if (xmrWalletService.isSyncedWithinTolerance())
                tryApplyMessages();
        });

        tryApplyMessages();
        cleanupDisputes();

        List<Dispute> disputes = getDisputeList().getList();
        disputes.forEach(dispute -> {
            try {
                DisputeValidation.validateNodeAddresses(dispute, config);
            } catch (DisputeValidation.ValidationException e) {
                log.error(e.toString());
                validationExceptions.add(e);
            }
        });

        maybeClearSensitiveData();
    }

    public boolean isTrader(Dispute dispute) {
        return keyRing.getPubKeyRing().equals(dispute.getTraderPubKeyRing());
    }

    public Optional<Dispute> findOwnDispute(String tradeId) {
        synchronized (getDisputeList()) {
            T disputeList = getDisputeList();
            if (disputeList == null) {
                log.warn("disputes is null");
                return Optional.empty();
            }
            return disputeList.stream().filter(e -> e.getTradeId().equals(tradeId)).findAny();
        }
    }

    public void maybeClearSensitiveData() {
        log.info("{} checking closed disputes eligibility for having sensitive data cleared", super.getClass().getSimpleName());
        Instant safeDate = closedTradableManager.getSafeDateForSensitiveDataClearing();
        getDisputeList().getList().stream()
                .filter(e -> e.isClosed())
                .filter(e -> e.getOpeningDate().toInstant().isBefore(safeDate))
                .forEach(Dispute::maybeClearSensitiveData);
        requestPersistence();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // trader sends message to arbitrator to open dispute
    public void sendDisputeOpenedMessage(Dispute dispute,
                                            ResultHandler resultHandler,
                                            FaultHandler faultHandler) {

        // get trade
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        if (trade == null) {
            String errorMsg = "Dispute trade does not exist, tradeId=" + dispute.getTradeId();
            faultHandler.handleFault(errorMsg, new IllegalStateException(errorMsg));
            return;
        }

        // arbitrator cannot open disputes
        if (trade.isArbitrator()) {
            String errorMsg = "Arbitrators cannot open disputes.";
            faultHandler.handleFault(errorMsg, new IllegalStateException(errorMsg));
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

        synchronized (disputeList.getObservableList()) {
            if (disputeList.contains(dispute)) {
                String msg = "We got a dispute msg that we have already stored. TradeId = " + dispute.getTradeId() + ", DisputeId = " + dispute.getId();
                log.warn(msg);
                faultHandler.handleFault(msg, new DisputeAlreadyOpenException());
                return;
            }

            Optional<Dispute> storedDisputeOptional = findDispute(dispute);
            boolean reOpen = storedDisputeOptional.isPresent() && storedDisputeOptional.get().isClosed();
            if (!storedDisputeOptional.isPresent() || reOpen) {

                // add or re-open dispute
                if (reOpen) {
                    dispute = storedDisputeOptional.get();
                } else {
                    disputeList.add(dispute);
                }

                String disputeInfo = getDisputeInfo(dispute);
                String sysMsg = dispute.isSupportTicket() ?
                        Res.get("support.youOpenedTicket", disputeInfo, Version.VERSION) :
                        Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);

                ChatMessage chatMessage = new ChatMessage(
                        getSupportType(),
                        dispute.getTradeId(),
                        keyRing.getPubKeyRing().hashCode(),
                        false,
                        Res.get("support.systemMsg", sysMsg),
                        p2PService.getAddress());
                chatMessage.setSystemMessage(true);
                dispute.addAndPersistChatMessage(chatMessage);

                // create dispute opened message
                trade.exportMultisigHex();
                NodeAddress agentNodeAddress = getAgentNodeAddress(dispute);
                DisputeOpenedMessage disputeOpenedMessage = new DisputeOpenedMessage(dispute,
                        p2PService.getAddress(),
                        UUID.randomUUID().toString(),
                        getSupportType(),
                        trade.getSelf().getUpdatedMultisigHex(),
                        trade.getArbitrator().getPaymentSentMessage());
                log.info("Send {} to peer {}. tradeId={}, openNewDisputeMessage.uid={}, " +
                        "chatMessage.uid={}",
                        disputeOpenedMessage.getClass().getSimpleName(), agentNodeAddress,
                        disputeOpenedMessage.getTradeId(), disputeOpenedMessage.getUid(),
                        chatMessage.getUid());
                recordPendingMessage(disputeOpenedMessage.getClass().getSimpleName());

                // send dispute opened message
                trade.setDisputeState(Trade.DisputeState.DISPUTE_REQUESTED);
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
                                clearPendingMessage();

                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setArrived(true);
                                trade.advanceDisputeState(Trade.DisputeState.DISPUTE_REQUESTED);
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
                                clearPendingMessage();

                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setStoredInMailbox(true);
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

                                clearPendingMessage();
                                // We use the chatMessage wrapped inside the openNewDisputeMessage for
                                // the state, as that is displayed to the user and we only persist that msg
                                chatMessage.setSendMessageError(errorMessage);
                                trade.setDisputeState(Trade.DisputeState.NO_DISPUTE);
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
        Dispute msgDispute = message.getDispute();
        log.info("Processing {} with trade {}, dispute {}", message.getClass().getSimpleName(), msgDispute.getTradeId(), msgDispute.getId());

        // get trade
        Trade trade = tradeManager.getTrade(msgDispute.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", msgDispute.getTradeId());
            return;
        }
        if (trade.isPayoutPublished()) {
            log.warn("Dispute trade {} payout already published", msgDispute.getTradeId());
            return;
        }

        // find existing dispute
        Optional<Dispute> storedDisputeOptional = findDispute(msgDispute);

        // determine if re-opening dispute
        boolean reOpen = storedDisputeOptional.isPresent() && storedDisputeOptional.get().isClosed();

        // use existing dispute or create new
        Dispute dispute = reOpen ? storedDisputeOptional.get() : msgDispute;

        // process on trade thread
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                String errorMessage = null;
                PubKeyRing senderPubKeyRing = null;
                try {

                    // initialize
                    T disputeList = getDisputeList();
                    if (disputeList == null) {
                        log.warn("disputes is null");
                        return;
                    }
                    dispute.setSupportType(message.getSupportType());
                    dispute.setState(Dispute.State.NEW);
                    Contract contract = dispute.getContract();

                    // validate dispute
                    try {
                        DisputeValidation.validateDisputeData(dispute);
                        DisputeValidation.validateNodeAddresses(dispute, config);
                        DisputeValidation.validateSenderNodeAddress(dispute, message.getSenderNodeAddress(), config);
                        //DisputeValidation.testIfDisputeTriesReplay(dispute, disputeList.getList());
                    } catch (DisputeValidation.ValidationException e) {
                        log.error(ExceptionUtils.getStackTrace(e));
                        validationExceptions.add(e);
                        throw e;
                    }

                    // try to validate payment account
                    try {
                        DisputeValidation.validatePaymentAccountPayload(dispute); // TODO: add field to dispute details: valid, invalid, missing
                    } catch (Exception e) {
                        log.error(ExceptionUtils.getStackTrace(e));
                        trade.prependErrorMessage(e.getMessage());
                        throw e;
                    }

                    // get sender
                    TradePeer sender;
                    if (reOpen) { // re-open can come from either peer
                        sender = trade.isArbitrator() ? trade.getTradePeer(message.getSenderNodeAddress()) : trade.getArbitrator();
                        senderPubKeyRing = sender.getPubKeyRing();
                    } else {
                        senderPubKeyRing = trade.isArbitrator() ? (dispute.isDisputeOpenerIsBuyer() ? contract.getBuyerPubKeyRing() : contract.getSellerPubKeyRing()) : trade.getArbitrator().getPubKeyRing();
                        sender = trade.getTradePeer(senderPubKeyRing);
                    }
                    if (sender == null) throw new RuntimeException("Pub key ring is not from arbitrator, buyer, or seller");

                    // update sender node address
                    sender.setNodeAddress(message.getSenderNodeAddress());

                    // verify message to trader is expected from arbitrator
                    if (!trade.isArbitrator() && sender != trade.getArbitrator()) {
                        throw new RuntimeException(message.getClass().getSimpleName() + " to trader is expected only from arbitrator");
                    }

                    // arbitrator verifies signature of payment sent message if given
                    if (trade.isArbitrator() && message.getPaymentSentMessage() != null) {
                        HavenoUtils.verifyPaymentSentMessage(trade, message.getPaymentSentMessage());
                        trade.getBuyer().setUpdatedMultisigHex(message.getPaymentSentMessage().getUpdatedMultisigHex());
                        trade.advanceState(Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
                    }

                    // update multisig hex
                    if (message.getUpdatedMultisigHex() != null) sender.setUpdatedMultisigHex(message.getUpdatedMultisigHex());

                    // add chat message with price info
                    if (trade instanceof ArbitratorTrade) addPriceInfoMessage(dispute, 0);

                    // add or re-open dispute
                    synchronized (disputeList) {
                        if (!disputeList.contains(msgDispute)) {
                            if (!storedDisputeOptional.isPresent() || reOpen) {

                                // update trade state
                                if (reOpen) {
                                    trade.setDisputeState(Trade.DisputeState.DISPUTE_OPENED);
                                } else {
                                    disputeList.add(dispute);
                                    trade.advanceDisputeState(Trade.DisputeState.DISPUTE_OPENED);
                                }

                                // reset buyer and seller unsigned payout tx hex
                                trade.getBuyer().setUnsignedPayoutTxHex(null);
                                trade.getSeller().setUnsignedPayoutTxHex(null);

                                // send dispute opened message to other peer if arbitrator
                                if (trade.isArbitrator()) {
                                    TradePeer senderPeer = sender == trade.getMaker() ? trade.getTaker() : trade.getMaker();
                                    if (senderPeer != trade.getMaker() && senderPeer != trade.getTaker()) throw new RuntimeException("Sender peer is not maker or taker, address=" + senderPeer.getNodeAddress());
                                    sendDisputeOpenedMessageToPeer(dispute, contract, senderPeer.getPubKeyRing(), trade.getSelf().getUpdatedMultisigHex());
                                }
                                tradeManager.requestPersistence();
                                errorMessage = null;
                            } else {
                                // valid case if both have opened a dispute and agent was not online
                                log.debug("We got a dispute already open for that trade and trading peer. TradeId = {}", dispute.getTradeId());
                            }

                            // add chat message with mediation info if applicable
                            addMediationResultMessage(dispute);
                        } else {
                            throw new RuntimeException("We got a dispute msg that we have already stored. TradeId = " + msgDispute.getTradeId());
                        }
                    }
                } catch (Exception e) {
                    log.error(ExceptionUtils.getStackTrace(e));
                    errorMessage = e.getMessage();
                    if (trade != null) trade.setErrorMessage(errorMessage);
                }

                // use chat message instead of open dispute message for the ack
                ObservableList<ChatMessage> messages = message.getDispute().getChatMessages();
                if (!messages.isEmpty()) {
                    ChatMessage msg = messages.get(messages.size() - 1); // send ack to sender of last chat message
                    sendAckMessage(msg, senderPubKeyRing, errorMessage == null, errorMessage);
                }

                requestPersistence();
            }
        }, trade.getId());
    }

    // arbitrator sends dispute opened message to opener's peer
    private void sendDisputeOpenedMessageToPeer(Dispute disputeFromOpener,
                                              Contract contractFromOpener,
                                              PubKeyRing pubKeyRing,
                                              String updatedMultisigHex) {
        log.info("{} sendPeerOpenedDisputeMessage() with trade {}, dispute {}", getClass().getSimpleName(), disputeFromOpener.getTradeId(), disputeFromOpener.getId());
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

        // create mirrored dispute
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
                disputeFromOpener.getPayoutTxSerialized(),
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

        // skip if dispute already open
        Optional<Dispute> storedDisputeOptional = findDispute(dispute);
        if (storedDisputeOptional.isPresent() && !storedDisputeOptional.get().isClosed()) {
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

        // add or re-open dispute
        boolean reOpen = storedDisputeOptional.isPresent() && storedDisputeOptional.get().isClosed();
        if (reOpen) {
            dispute = storedDisputeOptional.get();
            dispute.reOpen();
        } else {
            synchronized (disputeList) {
                disputeList.add(dispute);
            }
        }

        // get trade
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", dispute.getTradeId());
            return;
        }

        // create dispute opened message with peer dispute
        TradePeer peer = trade.getTradePeer(pubKeyRing);
        PubKeyRing peersPubKeyRing = peer.getPubKeyRing();
        NodeAddress peersNodeAddress = peer.getNodeAddress();
        DisputeOpenedMessage peerOpenedDisputeMessage = new DisputeOpenedMessage(dispute,
                p2PService.getAddress(),
                UUID.randomUUID().toString(),
                getSupportType(),
                updatedMultisigHex,
                trade.getArbitrator().getPaymentSentMessage());

        log.info("Send {} to peer {}. tradeId={}, peerOpenedDisputeMessage.uid={}, chatMessage.uid={}",
                peerOpenedDisputeMessage.getClass().getSimpleName(), peersNodeAddress,
                peerOpenedDisputeMessage.getTradeId(), peerOpenedDisputeMessage.getUid(),
                chatMessage.getUid());
        recordPendingMessage(peerOpenedDisputeMessage.getClass().getSimpleName());
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

                        clearPendingMessage();
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

                        clearPendingMessage();
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

                        clearPendingMessage();
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
    public void closeDisputeTicket(DisputeResult disputeResult, Dispute dispute, String summaryText, ResultHandler resultHandler, FaultHandler faultHandler) {
        try {

            // get trade
            Trade trade = tradeManager.getTrade(dispute.getTradeId());
            if (trade == null) throw new RuntimeException("Dispute trade " + dispute.getTradeId() + " does not exist");

            // persist result in dispute's chat message once
            boolean exists = disputeResult.getChatMessage() != null && disputeResult.getChatMessage().getMessage() != null && !disputeResult.getChatMessage().getMessage().isEmpty();
            if (!exists) {
                ChatMessage chatMessage = new ChatMessage(
                        getSupportType(),
                        dispute.getTradeId(),
                        dispute.getTraderPubKeyRing().hashCode(),
                        false,
                        summaryText,
                        p2PService.getAddress());
                disputeResult.setChatMessage(chatMessage);
                dispute.addAndPersistChatMessage(chatMessage);
            }

            // create dispute payout tx
            TradePeer receiver = trade.getTradePeer(dispute.getTraderPubKeyRing());
            if (!trade.isPayoutPublished() && receiver.getUpdatedMultisigHex() != null && receiver.getUnsignedPayoutTxHex() == null) {
                createDisputePayoutTx(trade, dispute.getContract(), disputeResult, true);
            }

            // create dispute closed message
            TradePeer receiverPeer = receiver == trade.getBuyer() ? trade.getSeller() : trade.getBuyer();
            boolean deferPublishPayout = !exists && receiver.getUnsignedPayoutTxHex() != null && receiverPeer.getUpdatedMultisigHex() != null && (trade.getDisputeState() == Trade.DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG || trade.getDisputeState().ordinal() >= Trade.DisputeState.ARBITRATOR_SAW_ARRIVED_DISPUTE_CLOSED_MSG.ordinal());
            DisputeClosedMessage disputeClosedMessage = new DisputeClosedMessage(disputeResult,
                    p2PService.getAddress(),
                    UUID.randomUUID().toString(),
                    getSupportType(),
                    trade.getSelf().getUpdatedMultisigHex(),
                    receiver.getUnsignedPayoutTxHex(), // include dispute payout tx if arbitrator has their updated multisig info
                    deferPublishPayout); // instruct trader to defer publishing payout tx because peer is expected to publish imminently
            receiverPeer.setDisputeClosedMessage(disputeClosedMessage);

            // send dispute closed message
            log.info("Send {} to trader {}. tradeId={}, {}.uid={}, chatMessage.uid={}",
                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                    disputeClosedMessage.getClass().getSimpleName(), disputeClosedMessage.getTradeId(),
                    disputeClosedMessage.getUid(), disputeResult.getChatMessage().getUid());
            recordPendingMessage(disputeClosedMessage.getClass().getSimpleName());
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
                                    disputeResult.getChatMessage().getUid());

                            clearPendingMessage();
                            dispute.setIsClosed();
                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            disputeResult.getChatMessage().setArrived(true);
                            trade.advanceDisputeState(Trade.DisputeState.ARBITRATOR_SAW_ARRIVED_DISPUTE_CLOSED_MSG);
                            trade.pollWalletNormallyForMs(60000);
                            requestPersistence(trade);
                            resultHandler.handleResult();
                        }

                        @Override
                        public void onStoredInMailbox() {
                            log.info("{} stored in mailbox for trader {}. tradeId={}, DisputeClosedMessage.uid={}, " +
                                            "chatMessage.uid={}",
                                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                                    disputeClosedMessage.getTradeId(), disputeClosedMessage.getUid(),
                                    disputeResult.getChatMessage().getUid());

                            clearPendingMessage();
                            dispute.setIsClosed();
                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            disputeResult.getChatMessage().setStoredInMailbox(true);
                            Trade trade = tradeManager.getTrade(dispute.getTradeId());
                            trade.advanceDisputeState(Trade.DisputeState.ARBITRATOR_STORED_IN_MAILBOX_DISPUTE_CLOSED_MSG);
                            requestPersistence(trade);
                            resultHandler.handleResult();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("{} failed: Trader {}. tradeId={}, DisputeClosedMessage.uid={}, " +
                                            "chatMessage.uid={}, errorMessage={}",
                                    disputeClosedMessage.getClass().getSimpleName(), receiver.getNodeAddress(),
                                    disputeClosedMessage.getTradeId(), disputeClosedMessage.getUid(),
                                    disputeResult.getChatMessage().getUid(), errorMessage);

                            clearPendingMessage();
                            // We use the chatMessage wrapped inside the DisputeClosedMessage for
                            // the state, as that is displayed to the user and we only persist that msg
                            disputeResult.getChatMessage().setSendMessageError(errorMessage);
                            trade.advanceDisputeState(Trade.DisputeState.ARBITRATOR_SEND_FAILED_DISPUTE_CLOSED_MSG);
                            requestPersistence(trade);
                            faultHandler.handleFault(errorMessage, new RuntimeException(errorMessage));
                        }
                    }
            );
            trade.advanceDisputeState(Trade.DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG);
            requestPersistence(trade);
        } catch (Exception e) {
            faultHandler.handleFault(e.getMessage(), e);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MoneroTxWallet createDisputePayoutTx(Trade trade, Contract contract, DisputeResult disputeResult, boolean updateState) {

        // import multisig hex
        trade.importMultisigHex();

        // sync and poll
        trade.syncAndPollWallet();

        // create unsigned dispute payout tx if not already published
        if (!trade.isPayoutPublished()) {

            // create unsigned dispute payout tx
            if (updateState) log.info("Creating unsigned dispute payout tx for trade {}", trade.getId());
            try {

                // trade wallet must be synced
                if (trade.getWallet().isMultisigImportNeeded()) throw new RuntimeException("Arbitrator's wallet needs updated multisig hex to create payout tx which means a trader must have already broadcast the payout tx for trade " + trade.getId());

                // check amounts
                if (disputeResult.getBuyerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) < 0) throw new RuntimeException("Buyer payout cannot be negative");
                if (disputeResult.getSellerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) < 0) throw new RuntimeException("Seller payout cannot be negative");
                if (disputeResult.getBuyerPayoutAmountBeforeCost().add(disputeResult.getSellerPayoutAmountBeforeCost()).compareTo(trade.getWallet().getUnlockedBalance()) > 0) {
                    throw new RuntimeException("The payout amounts are more than the wallet's unlocked balance, unlocked balance=" + trade.getWallet().getUnlockedBalance() + " vs " + disputeResult.getBuyerPayoutAmountBeforeCost() + " + " + disputeResult.getSellerPayoutAmountBeforeCost() + " = " + (disputeResult.getBuyerPayoutAmountBeforeCost().add(disputeResult.getSellerPayoutAmountBeforeCost())));
                }

                // create dispute payout tx config
                MoneroTxConfig txConfig = new MoneroTxConfig().setAccountIndex(0);
                String buyerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getMakerPayoutAddressString() : contract.getTakerPayoutAddressString();
                String sellerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getTakerPayoutAddressString() : contract.getMakerPayoutAddressString();
                txConfig.setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY);
                if (disputeResult.getBuyerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(buyerPayoutAddress, disputeResult.getBuyerPayoutAmountBeforeCost());
                if (disputeResult.getSellerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(sellerPayoutAddress, disputeResult.getSellerPayoutAmountBeforeCost());

                // configure who pays mining fee
                BigInteger loserPayoutAmount = disputeResult.getWinner() == Winner.BUYER ? disputeResult.getSellerPayoutAmountBeforeCost() : disputeResult.getBuyerPayoutAmountBeforeCost();
                if (loserPayoutAmount.equals(BigInteger.ZERO)) txConfig.setSubtractFeeFrom(0); // winner pays fee if loser gets 0
                else {
                    switch (disputeResult.getSubtractFeeFrom()) {
                        case BUYER_AND_SELLER:
                            txConfig.setSubtractFeeFrom(0, 1);
                            break;
                        case BUYER_ONLY:
                            txConfig.setSubtractFeeFrom(0);
                            break;
                        case SELLER_ONLY:
                            txConfig.setSubtractFeeFrom(1);
                            break;
                    }
                }

                // create dispute payout tx
                MoneroTxWallet payoutTx = trade.createDisputePayoutTx(txConfig);

                // update trade state
                if (updateState) {
                    trade.getProcessModel().setUnsignedPayoutTx(payoutTx);
                    trade.updatePayout(payoutTx);
                    if (trade.getBuyer().getUpdatedMultisigHex() != null) trade.getBuyer().setUnsignedPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
                    if (trade.getSeller().getUpdatedMultisigHex() != null) trade.getSeller().setUnsignedPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
                }
                trade.requestPersistence();
                return payoutTx;
            } catch (Exception e) {
                trade.syncAndPollWallet();
                if (!trade.isPayoutPublished()) throw e;
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

    public boolean isAgent(Dispute dispute) {
        return keyRing.getPubKeyRing().equals(dispute.getAgentPubKeyRing());
    }

    public Optional<Dispute> findDispute(Dispute dispute) {
        return findDispute(dispute.getTradeId(), dispute.getTraderId());
    }

    public Optional<Dispute> findDispute(DisputeResult disputeResult) {
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        return findDispute(disputeResult.getTradeId(), disputeResult.getTraderId());
    }

    public Optional<Dispute> findDispute(ChatMessage message) {
        return findDispute(message.getTradeId(), message.getTraderId());
    }

    public Optional<Dispute> findDispute(String tradeId, int traderId) {
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
                    keyRing.getPubKeyRing().hashCode(),
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

    protected void addMediationLogsReceivedMessage(Dispute dispute, String logsIdentifier) {
        String logsReceivedMessage = Res.get("support.mediatorReceivedLogs", logsIdentifier);
        ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                keyRing.hashCode(),
                false,
                logsReceivedMessage,
                p2PService.getAddress());
        chatMessage.setSystemMessage(true);
        dispute.addAndPersistChatMessage(chatMessage);
        requestPersistence();
    }

    // If price was going down between take offer time and open dispute time the buyer has an incentive to
    // not send the payment but to try to make a new trade with the better price. We risks to lose part of the
    // security deposit (in mediation we will always get back 0.003 BTC to keep some incentive to accept mediated
    // proposal). But if gain is larger than this loss he has economically an incentive to default in the trade.
    // We do all those calculations to give a hint to mediators to detect option trades.
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        if (!priceFeedService.hasExternalPrices()) {
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
        BigInteger potentialAmountAtDisputeOpening = priceAtDisputeOpening.getAmountByVolume(contract.getTradeVolume());
        BigInteger buyerSecurityDeposit = offerPayload.getMaxBuyerSecurityDeposit();
        BigInteger minRefundAtMediatedDispute = Restrictions.getMinRefundAtMediatedDispute();
        // minRefundAtMediatedDispute is always larger as buyerSecurityDeposit at mediated payout, we ignore refund agent case here as there it can be 0.
        BigInteger maxLossSecDeposit = buyerSecurityDeposit.subtract(minRefundAtMediatedDispute);
        BigInteger tradeAmount = contract.getTradeAmount();
        BigInteger potentialGain = potentialAmountAtDisputeOpening.subtract(tradeAmount).subtract(maxLossSecDeposit);
        String optionTradeDetails;
        // We don't translate those strings (yet) as it is only displayed to mediators/arbitrators.
        String headline;
        if (potentialGain.compareTo(BigInteger.ZERO) > 0) {
            headline = "This might be a potential option trade!";
            optionTradeDetails = "\nBTC amount calculated with price at dispute opening: " + HavenoUtils.formatXmr(potentialAmountAtDisputeOpening, true) +
                    "\nMax loss of security deposit is: " + HavenoUtils.formatXmr(maxLossSecDeposit, true) +
                    "\nPossible gain from an option trade is: " + HavenoUtils.formatXmr(potentialGain, true);
        } else {
            headline = "It does not appear to be an option trade.";
            optionTradeDetails = "\nBTC amount calculated with price at dispute opening: " + HavenoUtils.formatXmr(potentialAmountAtDisputeOpening, true) +
                    "\nMax loss of security deposit is: " + HavenoUtils.formatXmr(maxLossSecDeposit, true) +
                    "\nPossible loss from an option trade is: " + HavenoUtils.formatXmr(potentialGain.multiply(BigInteger.valueOf(-1)), true);
        }

        String percentagePriceDetails = offerPayload.isUseMarketBasedPrice() ?
                " (market based price was used: " + offerPayload.getMarketPriceMarginPct() * 100 + "%)" :
                " (fix price was used)";

        String priceInfoText = "System message: " + headline +
                "\n\nTrade price: " + contract.getPrice().toFriendlyString() + percentagePriceDetails +
                "\nTrade amount: " + HavenoUtils.formatXmr(tradeAmount, true) +
                "\nPrice at dispute opening: " + priceAtDisputeOpening.toFriendlyString() +
                optionTradeDetails;

        // We use the existing msg to copy over the users data
        ChatMessage priceInfoMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                keyRing.getPubKeyRing().hashCode(),
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
                int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                        TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                        CryptoMoney.SMALLEST_UNIT_EXPONENT;
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

    public boolean hasPendingMessageAtShutdown() {
        if (pendingOutgoingMessage.length() > 0) {
            log.warn("{} has an outgoing message pending: {}", this.getClass().getSimpleName(), pendingOutgoingMessage);
            return true;
        }
        return false;
    }

    private void recordPendingMessage(String className) {
        pendingOutgoingMessage = className;
    }

    private void clearPendingMessage() {
        pendingOutgoingMessage = "";
    }
}
