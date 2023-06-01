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

package haveno.core.trade.protocol;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.taskrunner.Task;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.DepositResponse;
import haveno.core.trade.messages.DepositsConfirmedMessage;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.FluentProtocol.Condition;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.MaybeSendSignContractRequest;
import haveno.core.trade.protocol.tasks.ProcessDepositResponse;
import haveno.core.trade.protocol.tasks.ProcessDepositsConfirmedMessage;
import haveno.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import haveno.core.trade.protocol.tasks.ProcessPaymentReceivedMessage;
import haveno.core.trade.protocol.tasks.ProcessPaymentSentMessage;
import haveno.core.trade.protocol.tasks.ProcessSignContractRequest;
import haveno.core.trade.protocol.tasks.ProcessSignContractResponse;
import haveno.core.trade.protocol.tasks.RemoveOffer;
import haveno.core.trade.protocol.tasks.SellerPublishTradeStatistics;
import haveno.core.trade.protocol.tasks.MaybeResendDisputeClosedMessageWithPayout;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import haveno.core.util.Validator;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendMailboxMessageListener;
import haveno.network.p2p.mailbox.MailboxMessage;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.messaging.DecryptedMailboxListener;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Slf4j
public abstract class TradeProtocol implements DecryptedDirectMessageListener, DecryptedMailboxListener {

    public static final int TRADE_TIMEOUT = 60;
    private static final String TIMEOUT_REACHED = "Timeout reached.";

    protected final ProcessModel processModel;
    protected final Trade trade;
    protected CountDownLatch tradeLatch; // to synchronize on trade
    private Timer timeoutTimer;
    private Object timeoutTimerLock = new Object();
    protected TradeResultHandler tradeResultHandler;
    protected ErrorMessageHandler errorMessageHandler;

    private int reprocessPaymentReceivedMessageCount;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradeProtocol(Trade trade) {
        this.trade = trade;
        this.processModel = trade.getProcessModel();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatching
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void onTradeMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        log.info("Received {} as TradeMessage from {} with tradeId {} and uid {}", message.getClass().getSimpleName(), peerNodeAddress, message.getTradeId(), message.getUid());
        handle(message, peerNodeAddress);
    }

    protected void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        log.info("Received {} as MailboxMessage from {} with tradeId {} and uid {}", message.getClass().getSimpleName(), peerNodeAddress, message.getTradeId(), message.getUid());
        handle(message, peerNodeAddress);
    }

    private void handle(TradeMessage message, NodeAddress peerNodeAddress) {
        new Thread(() -> {
            if (message instanceof DepositsConfirmedMessage) {
                handle((DepositsConfirmedMessage) message, peerNodeAddress);
            } else if (message instanceof PaymentSentMessage) {
                handle((PaymentSentMessage) message, peerNodeAddress);
            } else if (message instanceof PaymentReceivedMessage) {
                handle((PaymentReceivedMessage) message, peerNodeAddress);
            }
        }).start();
    }

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (!isMyMessage(networkEnvelope)) {
            return;
        }

        if (!isPubKeyValid(decryptedMessageWithPubKey, peer)) {
            return;
        }

        if (networkEnvelope instanceof TradeMessage) {
            onTradeMessage((TradeMessage) networkEnvelope, peer);

            // notify trade listeners
            // TODO (woodser): better way to register message notifications for trade?
            if (((TradeMessage) networkEnvelope).getTradeId().equals(processModel.getOfferId())) {
              trade.onVerifiedTradeMessage((TradeMessage) networkEnvelope, peer);
            }
        } else if (networkEnvelope instanceof AckMessage) {
            onAckMessage((AckMessage) networkEnvelope, peer);
            trade.onAckMessage((AckMessage) networkEnvelope, peer); // notify trade listeners
        }
    }

    @Override
    public void onMailboxMessageAdded(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peer) {
        if (!isPubKeyValid(decryptedMessageWithPubKey, peer)) return;
        handleMailboxCollectionSkipValidation(Collections.singletonList(decryptedMessageWithPubKey));
    }

    // TODO (woodser): this method only necessary because isPubKeyValid not called with sender argument, so it's validated before
    private void handleMailboxCollectionSkipValidation(Collection<DecryptedMessageWithPubKey> collection) {
        collection.stream()
                .map(DecryptedMessageWithPubKey::getNetworkEnvelope)
                .filter(this::isMyMessage)
                .filter(e -> e instanceof MailboxMessage)
                .map(e -> (MailboxMessage) e)
                .forEach(this::handleMailboxMessage);
    }

    private void handleMailboxCollection(Collection<DecryptedMessageWithPubKey> collection) {
        collection.stream()
                .filter(this::isPubKeyValid)
                .map(DecryptedMessageWithPubKey::getNetworkEnvelope)
                .filter(this::isMyMessage)
                .filter(e -> e instanceof MailboxMessage)
                .map(e -> (MailboxMessage) e)
                .sorted(new MailboxMessageComparator())
                .forEach(this::handleMailboxMessage);
    }

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

    private void handleMailboxMessage(MailboxMessage mailboxMessage) {
        if (mailboxMessage instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) mailboxMessage;
            // We only remove here if we have already completed the trade.
            // Otherwise removal is done after successfully applied the task runner.
            if (trade.isCompleted()) {
                processModel.getP2PService().getMailboxMessageService().removeMailboxMsg(mailboxMessage);
                log.info("Remove {} from the P2P network as trade is already completed.",
                        tradeMessage.getClass().getSimpleName());
                return;
            }
            onMailboxMessage(tradeMessage, mailboxMessage.getSenderNodeAddress());
        } else if (mailboxMessage instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) mailboxMessage;
            if (!trade.isCompleted()) {
                // We only apply the msg if we have not already completed the trade
                onAckMessage(ackMessage, mailboxMessage.getSenderNodeAddress());
            }
            // In any case we remove the msg
            processModel.getP2PService().getMailboxMessageService().removeMailboxMsg(ackMessage);
            log.info("Remove {} from the P2P network.", ackMessage.getClass().getSimpleName());
        }
    }

    public void removeMailboxMessageAfterProcessing(TradeMessage tradeMessage) {
        if (tradeMessage instanceof MailboxMessage) {
            processModel.getP2PService().getMailboxMessageService().removeMailboxMsg((MailboxMessage) tradeMessage);
            log.info("Remove {} from the P2P network.", tradeMessage.getClass().getSimpleName());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public abstract Class<? extends TradeTask>[] getDepositsConfirmedTasks();

    public void initialize(ProcessModelServiceProvider serviceProvider, TradeManager tradeManager) {
        processModel.applyTransient(serviceProvider, tradeManager, trade.getOffer());
        onInitialized();
    }

    protected void onInitialized() {

        // listen for direct messages unless completed
        if (!trade.isCompleted()) processModel.getP2PService().addDecryptedDirectMessageListener(this);

        // initialize trade with lock
        synchronized (trade) {
            trade.initialize(processModel.getProvider());
        }

        // process mailbox messages
        MailboxMessageService mailboxMessageService = processModel.getP2PService().getMailboxMessageService();
        if (!trade.isCompleted()) mailboxMessageService.addDecryptedMailboxListener(this);
        handleMailboxCollection(mailboxMessageService.getMyDecryptedMailboxMessages());

        // send deposits confirmed message if applicable
        maybeSendDepositsConfirmedMessage();
    }

    private void maybeSendDepositsConfirmedMessage() {
        if (trade.isDepositsConfirmed()) {
            new Thread(() -> maybeSendDepositsConfirmedMessages()).start();
        } else {
            EasyBind.subscribe(trade.stateProperty(), state -> {
                if (trade.isDepositsConfirmed()) {
                    new Thread(() -> maybeSendDepositsConfirmedMessages()).start();
                }
            });
        }
    }

    public void maybeReprocessPaymentReceivedMessage(boolean reprocessOnError) {
        synchronized (trade) {

            // skip if no need to reprocess
            if (trade.isSeller() || trade.getProcessModel().getPaymentReceivedMessage() == null || trade.getState().ordinal() >= Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG.ordinal()) {
                return;
            }

            log.warn("Reprocessing payment received message for {} {}", trade.getClass().getSimpleName(), trade.getId());
            new Thread(() -> handle(trade.getProcessModel().getPaymentReceivedMessage(), trade.getProcessModel().getPaymentReceivedMessage().getSenderNodeAddress(), reprocessOnError)).start();
        }
    }

    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        System.out.println(getClass().getSimpleName() + ".handleInitMultisigRequest()");
        synchronized (trade) {

            // check trade
            if (trade.hasFailed()) {
                log.warn("{} {} ignoring {} from {} because trade failed with previous error: {}", trade.getClass().getSimpleName(), trade.getId(), request.getClass().getSimpleName(), sender, trade.getErrorMessage());
                return;
            }
            Validator.checkTradeId(processModel.getOfferId(), request);

            // proocess message
            latchTrade();
            processModel.setTradeMessage(request);
            expect(anyPhase(Trade.Phase.INIT)
                    .with(request)
                    .from(sender))
                    .setup(tasks(
                            ProcessInitMultisigRequest.class,
                            MaybeSendSignContractRequest.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            startTimeout(TRADE_TIMEOUT);
                            handleTaskRunnerSuccess(sender, request);
                        },
                        errorMessage -> {
                            handleTaskRunnerFault(sender, request, errorMessage);
                        }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }

    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
        System.out.println(getClass().getSimpleName() + ".handleSignContractRequest() " + trade.getId());
        synchronized (trade) {

            // check trade
            if (trade.hasFailed()) {
                log.warn("{} {} ignoring {} from {} because trade failed with previous error: {}", trade.getClass().getSimpleName(), trade.getId(), message.getClass().getSimpleName(), sender, trade.getErrorMessage());
                return;
            }
            Validator.checkTradeId(processModel.getOfferId(), message);

            // process message
            if (trade.getState() == Trade.State.MULTISIG_COMPLETED || trade.getState() == Trade.State.CONTRACT_SIGNATURE_REQUESTED) {
                latchTrade();
                Validator.checkTradeId(processModel.getOfferId(), message);
                processModel.setTradeMessage(message);
                expect(anyState(Trade.State.MULTISIG_COMPLETED, Trade.State.CONTRACT_SIGNATURE_REQUESTED)
                        .with(message)
                        .from(sender))
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessSignContractRequest.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    startTimeout(TRADE_TIMEOUT);
                                    handleTaskRunnerSuccess(sender, message);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(sender, message, errorMessage);
                                }))
                        .withTimeout(TRADE_TIMEOUT)) // extend timeout
                        .executeTasks(true);
                awaitTradeLatch();
            } else {
                // process sign contract request after multisig created
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state == Trade.State.MULTISIG_COMPLETED) new Thread(() -> handleSignContractRequest(message, sender)).start(); // process notification without trade lock
                });
            }
        }
    }

    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
        System.out.println(getClass().getSimpleName() + ".handleSignContractResponse() " + trade.getId());
        synchronized (trade) {

            // check trade
            if (trade.hasFailed()) {
                log.warn("{} {} ignoring {} from {} because trade failed with previous error: {}", trade.getClass().getSimpleName(), trade.getId(), message.getClass().getSimpleName(), sender, trade.getErrorMessage());
                return;
            }
            Validator.checkTradeId(processModel.getOfferId(), message);

            // process message
            if (trade.getState() == Trade.State.CONTRACT_SIGNED) {
                latchTrade();
                Validator.checkTradeId(processModel.getOfferId(), message);
                processModel.setTradeMessage(message);
                expect(state(Trade.State.CONTRACT_SIGNED)
                        .with(message)
                        .from(sender))
                        .setup(tasks(
                                // TODO (woodser): validate request
                                ProcessSignContractResponse.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    startTimeout(TRADE_TIMEOUT);
                                    handleTaskRunnerSuccess(sender, message);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(sender, message, errorMessage);
                                }))
                        .withTimeout(TRADE_TIMEOUT)) // extend timeout
                        .executeTasks(true);
                awaitTradeLatch();
            } else {
                // process sign contract response after contract signed
                EasyBind.subscribe(trade.stateProperty(), state -> {
                    if (state == Trade.State.CONTRACT_SIGNED) new Thread(() -> handleSignContractResponse(message, sender)).start(); // process notification without trade lock
                });
            }
        }
    }

    public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
        System.out.println(getClass().getSimpleName() + ".handleDepositResponse()");
        synchronized (trade) {

            // check trade
            if (trade.hasFailed()) {
                log.warn("{} {} ignoring {} from {} because trade failed with previous error: {}", trade.getClass().getSimpleName(), trade.getId(), response.getClass().getSimpleName(), sender, trade.getErrorMessage());
                return;
            }
            Validator.checkTradeId(processModel.getOfferId(), response);

            // process message
            latchTrade();
            processModel.setTradeMessage(response);
            expect(anyState(Trade.State.SENT_PUBLISH_DEPOSIT_TX_REQUEST, Trade.State.SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST, Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS, Trade.State.DEPOSIT_TXS_SEEN_IN_NETWORK)
                    .with(response)
                    .from(sender))
                    .setup(tasks(
                            ProcessDepositResponse.class,
                            RemoveOffer.class,
                            SellerPublishTradeStatistics.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            stopTimeout();
                            this.errorMessageHandler = null;
                            handleTaskRunnerSuccess(sender, response);
                            if (tradeResultHandler != null) tradeResultHandler.handleResult(trade); // trade is initialized
                        },
                        errorMessage -> {
                            handleTaskRunnerFault(sender, response, errorMessage);
                        }))
                    .withTimeout(TRADE_TIMEOUT))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }

    public void handle(DepositsConfirmedMessage response, NodeAddress sender) {
        System.out.println(getClass().getSimpleName() + ".handle(DepositsConfirmedMessage)");
        synchronized (trade) {
            latchTrade();
            expect(new Condition(trade)
                    .with(response)
                    .from(sender))
                    .setup(tasks(
                        ProcessDepositsConfirmedMessage.class,
                        VerifyPeersAccountAgeWitness.class,
                        MaybeResendDisputeClosedMessageWithPayout.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                handleTaskRunnerSuccess(sender, response);
                            },
                            errorMessage -> {
                                handleTaskRunnerFault(sender, response, errorMessage);
                            })))
                    .executeTasks();
            awaitTradeLatch();
        }
    }

    // received by seller and arbitrator
    protected void handle(PaymentSentMessage message, NodeAddress peer) {
        System.out.println(getClass().getSimpleName() + ".handle(PaymentSentMessage)");
        if (!(trade instanceof SellerTrade || trade instanceof ArbitratorTrade)) {
            log.warn("Ignoring PaymentSentMessage since not seller or arbitrator");
            return;
        }
        // We are more tolerant with expected phase and allow also DEPOSITS_PUBLISHED as it can be the case
        // that the wallet is still syncing and so the DEPOSITS_CONFIRMED state to yet triggered when we received
        // a mailbox message with PaymentSentMessage.
        // TODO A better fix would be to add a listener for the wallet sync state and process
        // the mailbox msg once wallet is ready and trade state set.
        synchronized (trade) {
            if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_SENT.ordinal()) {
                log.warn("Received another PaymentSentMessage which was already processed, ACKing");
                handleTaskRunnerSuccess(peer, message);
                return;
            }
            latchTrade();
            expect(anyPhase(Trade.Phase.DEPOSITS_CONFIRMED, Trade.Phase.DEPOSITS_UNLOCKED)
                    .with(message)
                    .from(peer)
                    .preCondition(trade.getPayoutTx() == null,
                            () -> {
                                log.warn("We received a PaymentSentMessage but we have already created the payout tx " +
                                        "so we ignore the message. This can happen if the ACK message to the peer did not " +
                                        "arrive and the peer repeats sending us the message. We send another ACK msg.");
                                sendAckMessage(peer, message, true, null);
                                removeMailboxMessageAfterProcessing(message);
                            }))
                    .setup(tasks(
                            ApplyFilter.class,
                            ProcessPaymentSentMessage.class,
                            VerifyPeersAccountAgeWitness.class)
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                handleTaskRunnerSuccess(peer, message);
                            },
                            (errorMessage) -> {
                                handleTaskRunnerFault(peer, message, errorMessage);
                            })))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }

    // received by buyer and arbitrator
    protected void handle(PaymentReceivedMessage message, NodeAddress peer) {
        handle(message, peer, true);
    }

    private void handle(PaymentReceivedMessage message, NodeAddress peer, boolean reprocessOnError) {
        System.out.println(getClass().getSimpleName() + ".handle(PaymentReceivedMessage)");
        if (!(trade instanceof BuyerTrade || trade instanceof ArbitratorTrade)) {
            log.warn("Ignoring PaymentReceivedMessage since not buyer or arbitrator");
            return;
        }
        synchronized (trade) {
            if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_RECEIVED.ordinal()) {
                log.warn("Received another PaymentReceivedMessage which was already processed, ACKing");
                handleTaskRunnerSuccess(peer, message);
                return;
            }
            latchTrade();
            Validator.checkTradeId(processModel.getOfferId(), message);
            processModel.setTradeMessage(message);
            expect(anyPhase(
                    trade.isBuyer() ? new Trade.Phase[] {Trade.Phase.PAYMENT_SENT, Trade.Phase.PAYMENT_RECEIVED} :
                    trade.isArbitrator() ? new Trade.Phase[] {Trade.Phase.DEPOSITS_CONFIRMED, Trade.Phase.DEPOSITS_UNLOCKED, Trade.Phase.PAYMENT_SENT} :  // arbitrator syncs slowly after deposits confirmed
                    new Trade.Phase[] {Trade.Phase.DEPOSITS_UNLOCKED, Trade.Phase.PAYMENT_SENT})
                .with(message)
                .from(peer))
                .setup(tasks(
                    ProcessPaymentReceivedMessage.class)
                    .using(new TradeTaskRunner(trade,
                        () -> {
                            handleTaskRunnerSuccess(peer, message);
                        },
                        errorMessage -> {
                            log.warn("Error processing payment received message: " + errorMessage);
                            processModel.getTradeManager().requestPersistence();

                            // schedule to reprocess message unless deleted
                            if (trade.getProcessModel().getPaymentReceivedMessage() != null) {
                                UserThread.runAfter(() -> {
                                    reprocessPaymentReceivedMessageCount++;
                                    maybeReprocessPaymentReceivedMessage(reprocessOnError);
                                }, trade.getReprocessDelayInSeconds(reprocessPaymentReceivedMessageCount));
                            } else {
                                handleTaskRunnerFault(peer, message, errorMessage); // otherwise send nack
                            }
                            unlatchTrade();
                        })))
                .executeTasks(true);
            awaitTradeLatch();
        }
    }

    public void onWithdrawCompleted() {
        log.info("Withdraw completed");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // FluentProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We log an error if condition is not met and call the protocol error handler
    protected FluentProtocol expect(FluentProtocol.Condition condition) {
        return new FluentProtocol(this)
                .condition(condition)
                .resultHandler(result -> {
                    if (!result.isValid()) {
                        log.warn(result.getInfo());
                        handleTaskRunnerFault(null,
                                null,
                                result.name(),
                                result.getInfo());
                    }
                });
    }

    // We execute only if condition is met but do not log an error.
    protected FluentProtocol given(FluentProtocol.Condition condition) {
        return new FluentProtocol(this)
                .condition(condition);
    }

    protected FluentProtocol.Condition phase(Trade.Phase expectedPhase) {
        return new FluentProtocol.Condition(trade).phase(expectedPhase);
    }

    protected FluentProtocol.Condition anyPhase(Trade.Phase... expectedPhases) {
        return new FluentProtocol.Condition(trade).anyPhase(expectedPhases);
    }

    protected FluentProtocol.Condition state(Trade.State expectedState) {
        return new FluentProtocol.Condition(trade).state(expectedState);
    }

    protected FluentProtocol.Condition anyState(Trade.State... expectedStates) {
        return new FluentProtocol.Condition(trade).anyState(expectedStates);
    }

    @SafeVarargs
    public final FluentProtocol.Setup tasks(Class<? extends Task<Trade>>... tasks) {
        return new FluentProtocol.Setup(this, trade).tasks(tasks);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ACK msg
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onAckMessage(AckMessage ackMessage, NodeAddress peer) {

        // handle ack for PaymentSentMessage, which automatically re-sends if not ACKed in a certain time
        if (ackMessage.getSourceMsgClassName().equals(PaymentSentMessage.class.getSimpleName())) {
            if (trade.getTradePeer(peer) == trade.getSeller()) {
                processModel.setPaymentSentAckMessage(ackMessage);
            } else if (!ackMessage.isSuccess()) {
                String err = "Received AckMessage with error state for " + ackMessage.getSourceMsgClassName() + " from "+ peer + " with tradeId " + trade.getId() + " and errorMessage=" + ackMessage.getErrorMessage();
                log.warn(err);
                return; // log error and ignore nack if not seller
            }
        }

        if (ackMessage.isSuccess()) {
            log.info("Received AckMessage for {} from {} with tradeId {} and uid {}",
                    ackMessage.getSourceMsgClassName(), peer, trade.getId(), ackMessage.getSourceUid());

            // handle ack for DepositsConfirmedMessage, which automatically re-sends if not ACKed in a certain time
            if (ackMessage.getSourceMsgClassName().equals(DepositsConfirmedMessage.class.getSimpleName())) {
                if (trade.getTradePeer(peer) != null) {
                    trade.getTradePeer(peer).setDepositsConfirmedMessageAcked(true);
                }
            }
        } else {
            String err = "Received AckMessage with error state for " + ackMessage.getSourceMsgClassName() + " from "+ peer + " with tradeId " + trade.getId() + " and errorMessage=" + ackMessage.getErrorMessage();
            log.warn(err);

            // set trade state on deposit request nack
            if (ackMessage.getSourceMsgClassName().equals(DepositRequest.class.getSimpleName())) {
                trade.setStateIfValidTransitionTo(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
                processModel.getTradeManager().requestPersistence();
            }

            handleError(err);
        }
    }

    protected void sendAckMessage(NodeAddress peer, TradeMessage message, boolean result, @Nullable String errorMessage) {

        // get peer's pub key ring
        PubKeyRing peersPubKeyRing = getPeersPubKeyRing(peer);
        if (peersPubKeyRing == null) {
            log.error("We cannot send the ACK message as peersPubKeyRing is null");
            return;
        }

        String tradeId = message.getTradeId();
        String sourceUid = message.getUid();
        AckMessage ackMessage = new AckMessage(processModel.getMyNodeAddress(),
                AckMessageSourceType.TRADE_MESSAGE,
                message.getClass().getSimpleName(),
                sourceUid,
                tradeId,
                result,
                errorMessage);

        log.info("Send AckMessage for {} to peer {}. tradeId={}, sourceUid={}",
                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
        processModel.getP2PService().getMailboxMessageService().sendEncryptedMailboxMessage(
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

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Timeout
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected synchronized void startTimeout(long timeoutSec) {
        synchronized (timeoutTimerLock) {
            stopTimeout();
            timeoutTimer = UserThread.runAfter(() -> {
                handleError(TIMEOUT_REACHED + " Protocol did not complete in " + timeoutSec + " sec. TradeID=" + trade.getId() + ", state=" + trade.stateProperty().get());
            }, timeoutSec);
        }
    }

    protected synchronized void stopTimeout() {
        synchronized (timeoutTimerLock) {
            if (timeoutTimer != null) {
                timeoutTimer.stop();
                timeoutTimer = null;
            }
        }
    }

    public static boolean isTimeoutError(String errorMessage) {
        return errorMessage != null && errorMessage.contains(TIMEOUT_REACHED);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Task runner
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handleTaskRunnerSuccess(NodeAddress sender, TradeMessage message) {
        handleTaskRunnerSuccess(sender, message, message.getClass().getSimpleName());
    }

    protected void handleTaskRunnerSuccess(FluentProtocol.Event event) {
        handleTaskRunnerSuccess(null, null, event.name());
    }

    protected void handleTaskRunnerFault(NodeAddress sender, TradeMessage message, String errorMessage) {
        handleTaskRunnerFault(sender, message, message.getClass().getSimpleName(), errorMessage);
    }

    protected void handleTaskRunnerFault(FluentProtocol.Event event, String errorMessage) {
        handleTaskRunnerFault(null, null, event.name(), errorMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Validation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PubKeyRing getPeersPubKeyRing(NodeAddress address) {
      trade.setMyNodeAddress(); // TODO: this is a hack to update my node address before verifying the message
      TradePeer peer = trade.getTradePeer(address);
      if (peer == null) {
        log.warn("Cannot get peer's pub key ring because peer is not maker, taker, or arbitrator. Their address might have changed: " + peer);
        return null;
      }
      return peer.getPubKeyRing();
    }

    private boolean isPubKeyValid(DecryptedMessageWithPubKey message) {
        MailboxMessage mailboxMessage = (MailboxMessage) message.getNetworkEnvelope();
        NodeAddress sender = mailboxMessage.getSenderNodeAddress();
        return isPubKeyValid(message, sender);
    }

    private boolean isPubKeyValid(DecryptedMessageWithPubKey message, NodeAddress sender) {
        if (this instanceof ArbitratorProtocol) {

            // valid if traders unknown
            if (trade.getMaker().getPubKeyRing() == null || trade.getTaker().getPubKeyRing() == null) return true;

            // valid if maker pub key
            if (message.getSignaturePubKey().equals(trade.getMaker().getPubKeyRing().getSignaturePubKey())) return true;

            // valid if taker pub key
            if (message.getSignaturePubKey().equals(trade.getTaker().getPubKeyRing().getSignaturePubKey())) return true;
        } else {

            // valid if arbitrator or peer unknown
            if (trade.getArbitrator().getPubKeyRing() == null || (trade.getTradePeer() == null || trade.getTradePeer().getPubKeyRing() == null)) return true;

            // valid if arbitrator's pub key ring
            if (message.getSignaturePubKey().equals(trade.getArbitrator().getPubKeyRing().getSignaturePubKey())) return true;

            // valid if peer's pub key ring
            if (message.getSignaturePubKey().equals(trade.getTradePeer().getPubKeyRing().getSignaturePubKey())) return true;
        }

        // invalid
        log.error("SignaturePubKey in message does not match the SignaturePubKey we have set for our arbitrator or trading peer.");
        return false;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handleTaskRunnerSuccess(NodeAddress sender, @Nullable TradeMessage message, String source) {
        log.info("TaskRunner successfully completed. Triggered from {}, tradeId={}", source, trade.getId());
        if (message != null) {
            sendAckMessage(sender, message, true, null);

            // Once a taskRunner is completed we remove the mailbox message. To not remove it directly at the task
            // adds some resilience in case of minor errors, so after a restart the mailbox message can be applied
            // again.
            removeMailboxMessageAfterProcessing(message);
        }
        unlatchTrade();
    }

    void handleTaskRunnerFault(NodeAddress ackReceiver, @Nullable TradeMessage message, String source, String errorMessage) {
        log.error("Task runner failed with error {}. Triggered from {}. Monerod={}" , errorMessage, source, trade.getXmrWalletService().getConnectionsService().getConnection());

        if (message != null) {
            sendAckMessage(ackReceiver, message, false, errorMessage);
        }

        handleError(errorMessage);
    }

    // these are not thread safe, so they must be used within a lock on the trade

    protected void handleError(String errorMessage) {
        stopTimeout();
        log.error(errorMessage);
        trade.setErrorMessage(errorMessage);
        processModel.getTradeManager().requestPersistence();
        if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage(errorMessage);
        unlatchTrade();
    }

    protected void latchTrade() {
        if (tradeLatch != null) throw new RuntimeException("Trade latch is not null. That should never happen.");
        if (trade.isShutDown()) throw new RuntimeException("Cannot latch trade " + trade.getId() + " for protocol because it's shut down");
        tradeLatch = new CountDownLatch(1);
    }

    protected void unlatchTrade() {
        CountDownLatch lastLatch = tradeLatch;
        tradeLatch = null;
        if (lastLatch != null) lastLatch.countDown();
    }

    protected void awaitTradeLatch() {
        if (tradeLatch == null) return;
        HavenoUtils.awaitLatch(tradeLatch);
    }

    private boolean isMyMessage(NetworkEnvelope message) {
        if (message instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) message;
            return tradeMessage.getTradeId().equals(trade.getId());
        } else if (message instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) message;
            return ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE && ackMessage.getSourceId().equals(trade.getId());
        } else {
            return false;
        }
    }

    public void maybeSendDepositsConfirmedMessages() {
        synchronized (trade) {
            if (trade.isDepositsConfirmedAcked()) return;
            if (!trade.isInitialized() || trade.isShutDownStarted()) return; // skip if shutting down
            latchTrade();
            expect(new Condition(trade))
                    .setup(tasks(getDepositsConfirmedTasks())
                    .using(new TradeTaskRunner(trade,
                            () -> {
                                handleTaskRunnerSuccess(null, null, "maybeSendDepositsConfirmedMessages");
                            },
                            (errorMessage) -> {
                                handleTaskRunnerFault(null, null, "maybeSendDepositsConfirmedMessages", errorMessage);
                            })))
                    .executeTasks(true);
            awaitTradeLatch();
        }
    }
}
