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

package haveno.core.trade.protocol;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.taskrunner.Task;
import haveno.core.network.MessageState;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.TradeManager.MailboxMessageComparator;
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
import haveno.core.trade.protocol.FluentProtocol.Condition;
import haveno.core.trade.protocol.FluentProtocol.Event;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.MakerRecreateReserveTx;
import haveno.core.trade.protocol.tasks.MakerSendInitTradeRequestToArbitrator;
import haveno.core.trade.protocol.tasks.MaybeSendSignContractRequest;
import haveno.core.trade.protocol.tasks.ProcessDepositResponse;
import haveno.core.trade.protocol.tasks.ProcessDepositsConfirmedMessage;
import haveno.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import haveno.core.trade.protocol.tasks.ProcessPaymentReceivedMessage;
import haveno.core.trade.protocol.tasks.ProcessPaymentSentMessage;
import haveno.core.trade.protocol.tasks.ProcessSignContractRequest;
import haveno.core.trade.protocol.tasks.SendDepositRequest;
import haveno.core.trade.protocol.tasks.MaybeResendDisputeClosedMessageWithPayout;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import haveno.core.util.Validator;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.mailbox.MailboxMessage;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.messaging.DecryptedMailboxListener;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

@Slf4j
public abstract class TradeProtocol implements DecryptedDirectMessageListener, DecryptedMailboxListener {

    public static final int TRADE_STEP_TIMEOUT_SECONDS = Config.baseCurrencyNetwork().isTestnet() ? 60 : 180;
    private static final String TIMEOUT_REACHED = "Timeout reached.";
    public static final int MAX_ATTEMPTS = 5; // max attempts to create txs and other protocol functions
    public static final int REQUEST_CONNECTION_SWITCH_EVERY_NUM_ATTEMPTS = 2; // request connection switch on even attempts
    public static final long REPROCESS_DELAY_MS = 5000;
    public static final String LOG_HIGHLIGHT = ""; // TODO: how to highlight some logs with cyan? ("\u001B[36m")? coloring works in the terminal but prints character literals to .log files
    public static final String SEND_INIT_TRADE_REQUEST_FAILED = "Sending InitTradeRequest failed";

    protected final ProcessModel processModel;
    protected final Trade trade;
    protected CountDownLatch tradeLatch; // to synchronize on trade
    private Timer timeoutTimer;
    private Object timeoutTimerLock = new Object();
    protected TradeResultHandler tradeResultHandler;
    protected ErrorMessageHandler errorMessageHandler;

    private boolean depositsConfirmedTasksCalled;
    private int reprocessPaymentSentMessageCount;
    private int reprocessPaymentReceivedMessageCount;
    private boolean makerInitTradeRequestHasBeenNacked = false;
    private boolean autoMarkPaymentReceivedOnNack = true;


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
        log.info("Received {} as TradeMessage from {} with tradeId {} and uid {}", message.getClass().getSimpleName(), peerNodeAddress, message.getOfferId(), message.getUid());
        handle(message, peerNodeAddress);
    }

    protected void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        log.info("Received {} as MailboxMessage from {} with tradeId {} and uid {}", message.getClass().getSimpleName(), peerNodeAddress, message.getOfferId(), message.getUid());
        handle(message, peerNodeAddress);
    }

    private void handle(TradeMessage message, NodeAddress peerNodeAddress) {
        if (message instanceof DepositsConfirmedMessage) {
            handle((DepositsConfirmedMessage) message, peerNodeAddress);
        } else if (message instanceof PaymentSentMessage) {
            handle((PaymentSentMessage) message, peerNodeAddress);
        } else if (message instanceof PaymentReceivedMessage) {
            handle((PaymentReceivedMessage) message, peerNodeAddress);
        }
    }

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (!isMyMessage(networkEnvelope)) {
            return;
        }

        if (!isPubKeyValid(decryptedMessageWithPubKey)) {
            return;
        }

        if (networkEnvelope instanceof TradeMessage) {
            onTradeMessage((TradeMessage) networkEnvelope, peer);

            // notify trade listeners
            // TODO (woodser): better way to register message notifications for trade?
            if (((TradeMessage) networkEnvelope).getOfferId().equals(processModel.getOfferId())) {
              trade.onVerifiedTradeMessage((TradeMessage) networkEnvelope, peer);
            }
        } else if (networkEnvelope instanceof AckMessage) {
            onAckMessage((AckMessage) networkEnvelope, peer);
        }
    }

    @Override
    public void onMailboxMessageAdded(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peer) {
        if (!isPubKeyValid(decryptedMessageWithPubKey)) return;
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
            onAckMessage(ackMessage, mailboxMessage.getSenderNodeAddress());
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
        if (!trade.isFinished()) processModel.getP2PService().addDecryptedDirectMessageListener(this);

        // initialize trade
        synchronized (trade.getLock()) {
            trade.initialize(processModel.getProvider());

            // process mailbox messages
            MailboxMessageService mailboxMessageService = processModel.getP2PService().getMailboxMessageService();
            if (!trade.isCompleted()) mailboxMessageService.addDecryptedMailboxListener(this);
            handleMailboxCollection(mailboxMessageService.getMyDecryptedMailboxMessages());

            // reprocess applicable messages
            trade.reprocessApplicableMessages();
        }

        // send deposits confirmed message if applicable
        EasyBind.subscribe(trade.stateProperty(), state -> maybeSendDepositsConfirmedMessages());
    }

    public void maybeSendDepositsConfirmedMessages() {
        if (!trade.isInitialized() || trade.isShutDownStarted()) return; // skip if shutting down
        ThreadUtils.execute(() -> {
            if (!trade.isInitialized() || trade.isShutDownStarted()) return;
            if (!trade.isDepositsConfirmed() || trade.isDepositsConfirmedAcked() || trade.isPayoutPublished() || depositsConfirmedTasksCalled) return;
            depositsConfirmedTasksCalled = true;
            synchronized (trade.getLock()) {
                if (!trade.isInitialized() || trade.isShutDownStarted()) return;
                latchTrade();
                expect(new Condition(trade))
                        .setup(tasks(getDepositsConfirmedTasks())
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    handleTaskRunnerSuccess(null, null, "maybeSendDepositsConfirmedMessages");
                                },
                                (errorMessage) -> {
                                    handleTaskRunnerFault(null, null, "maybeSendDepositsConfirmedMessages", errorMessage, null);
                                })))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    public void maybeReprocessPaymentSentMessage(boolean reprocessOnError) {
        if (trade.isShutDownStarted()) return;
        ThreadUtils.execute(() -> {
            if (trade.isShutDownStarted()) return;
            synchronized (trade.getLock()) {

                // skip if no need to reprocess
                if (trade.isShutDownStarted() || trade.isBuyer() || trade.getBuyer().getPaymentSentMessage() == null || trade.getState().ordinal() >= Trade.State.BUYER_SENT_PAYMENT_SENT_MSG.ordinal()) {
                    return;
                }

                log.warn("Reprocessing PaymentSentMessage for {} {}", trade.getClass().getSimpleName(), trade.getId());
                handle(trade.getBuyer().getPaymentSentMessage(), trade.getBuyer().getPaymentSentMessage().getSenderNodeAddress(), reprocessOnError);
            }
        }, trade.getId());
    }

    public void maybeReprocessPaymentReceivedMessage(boolean reprocessOnError) {
        if (trade.isShutDownStarted()) return;
        ThreadUtils.execute(() -> {
            if (trade.isShutDownStarted()) return;
            synchronized (trade.getLock()) {

                // skip if no need to reprocess
                if (trade.isShutDownStarted() || trade.isSeller() || trade.getSeller().getPaymentReceivedMessage() == null || (trade.getState().ordinal() >= Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG.ordinal() && trade.isPayoutPublished())) {
                    return;
                }

                log.warn("Reprocessing PaymentReceivedMessage for {} {}", trade.getClass().getSimpleName(), trade.getId());
                handle(trade.getSeller().getPaymentReceivedMessage(), trade.getSeller().getPaymentReceivedMessage().getSenderNodeAddress(), reprocessOnError);
            }
        }, trade.getId());
    }

    public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
        log.info(LOG_HIGHLIGHT + "handleInitMultisigRequest() for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + sender);
        trade.addInitProgressStep();
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {

                // check trade
                if (trade.hasFailed()) {
                    log.warn("{} {} ignoring {} from {} because trade failed with previous error: {}", trade.getClass().getSimpleName(), trade.getId(), request.getClass().getSimpleName(), sender, trade.getErrorMessage());
                    return;
                }
                Validator.checkTradeId(processModel.getOfferId(), request);

                // process message
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
                                startTimeout();
                                handleTaskRunnerSuccess(sender, request);
                            },
                            errorMessage -> {
                                handleTaskRunnerFault(sender, request, errorMessage);
                            }))
                        .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
        log.info(LOG_HIGHLIGHT + "handleSignContractRequest() for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + sender);
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {

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
                                        handleTaskRunnerSuccess(sender, message);
                                    },
                                    errorMessage -> {
                                        handleTaskRunnerFault(sender, message, errorMessage);
                                    })))
                            .executeTasks(true);
                    awaitTradeLatch();
                } else {
                    
                    // process sign contract request after multisig created
                    EasyBind.subscribe(trade.stateProperty(), state -> {
                        if (state == Trade.State.MULTISIG_COMPLETED) ThreadUtils.execute(() -> handleSignContractRequest(message, sender), trade.getId()); // process notification without trade lock
                    });
                }
            }
        }, trade.getId());
    }

    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
        log.info(LOG_HIGHLIGHT + "handleSignContractResponse() for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + sender);
        trade.addInitProgressStep();
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {

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
                                    SendDepositRequest.class)
                            .using(new TradeTaskRunner(trade,
                                    () -> {
                                        startTimeout();
                                        handleTaskRunnerSuccess(sender, message);
                                    },
                                    errorMessage -> {
                                        handleTaskRunnerFault(sender, message, errorMessage);
                                    }))
                            .withTimeout(TRADE_STEP_TIMEOUT_SECONDS)) // extend timeout
                            .executeTasks(true);
                    awaitTradeLatch();
                } else {
                    
                    // process sign contract response after contract signed
                    EasyBind.subscribe(trade.stateProperty(), state -> {
                        if (state == Trade.State.CONTRACT_SIGNED) ThreadUtils.execute(() -> handleSignContractResponse(message, sender), trade.getId()); // process notification without trade lock
                    });
                }
            }
        }, trade.getId());
    }

    public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
        log.info(LOG_HIGHLIGHT + "handleDepositResponse() for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + sender);
        trade.addInitProgressStep();
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                Validator.checkTradeId(processModel.getOfferId(), response);
                latchTrade();
                processModel.setTradeMessage(response);
                expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_REQUESTED, Trade.Phase.DEPOSITS_PUBLISHED)
                        .with(response)
                        .from(sender))
                        .setup(tasks(
                                ProcessDepositResponse.class)
                        .using(new TradeTaskRunner(trade,
                            () -> {
                                stopTimeout();
                                
                                // tasks may complete successfully but process an error
                                if (trade.getInitError() == null) {
                                    this.errorMessageHandler = null; // TODO: set this when trade state is >= DEPOSIT_PUBLISHED
                                    handleTaskRunnerSuccess(sender, response);
                                    if (tradeResultHandler != null) tradeResultHandler.handleResult(trade); // trade is initialized
                                } else {
                                    handleTaskRunnerSuccess(sender, response);
                                    if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage(trade.getInitError().getMessage());
                                }

                                this.tradeResultHandler = null;
                                this.errorMessageHandler = null;
                            },
                            errorMessage -> {
                                handleTaskRunnerFault(sender, response, errorMessage);
                            }))
                        .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    public void handle(DepositsConfirmedMessage message, NodeAddress sender) {
        log.info(LOG_HIGHLIGHT + "handle(DepositsConfirmedMessage) for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + sender);
        if (!trade.isInitialized() || trade.isShutDown()) return;
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {
                if (!trade.isInitialized() || trade.isShutDown()) return;
                latchTrade();
                this.errorMessageHandler = null;
                expect(new Condition(trade)
                        .with(message)
                        .from(sender))
                        .setup(tasks(
                            ProcessDepositsConfirmedMessage.class,
                            VerifyPeersAccountAgeWitness.class,
                            MaybeResendDisputeClosedMessageWithPayout.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    handleTaskRunnerSuccess(sender, message);
                                },
                                errorMessage -> {
                                    handleTaskRunnerFault(sender, message, errorMessage);
                                })))
                        .executeTasks();
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    // received by seller and arbitrator
    protected void handle(PaymentSentMessage message, NodeAddress peer) {
        handle(message, peer, true);
    }

    // received by seller and arbitrator
    protected void handle(PaymentSentMessage message, NodeAddress peer, boolean reprocessOnError) {
        log.info(LOG_HIGHLIGHT + "handle(PaymentSentMessage) for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + peer);

        // ignore if not seller or arbitrator
        if (!(trade instanceof SellerTrade || trade instanceof ArbitratorTrade)) {
            log.warn("Ignoring PaymentSentMessage since not seller or arbitrator");
            return;
        }

        // validate signature
        try {
            HavenoUtils.verifyPaymentSentMessage(trade, message);
        } catch (Throwable t) {
            log.warn("Ignoring PaymentSentMessage with invalid signature for {} {}, error={}", trade.getClass().getSimpleName(), trade.getId(), t.getMessage());
            return;
        }

        // save message for reprocessing
        trade.getBuyer().setPaymentSentMessage(message);
        trade.persistNow(() -> {

            // process message on trade thread
            if (!trade.isInitialized() || trade.isShutDownStarted()) return;
            ThreadUtils.execute(() -> {
                // We are more tolerant with expected phase and allow also DEPOSITS_PUBLISHED as it can be the case
                // that the wallet is still syncing and so the DEPOSITS_CONFIRMED state to yet triggered when we received
                // a mailbox message with PaymentSentMessage.
                // TODO A better fix would be to add a listener for the wallet sync state and process
                // the mailbox msg once wallet is ready and trade state set.
                synchronized (trade.getLock()) {
                    if (!trade.isInitialized() || trade.isShutDownStarted()) return;
                    if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_SENT.ordinal()) {
                        log.warn("Received another PaymentSentMessage which was already processed for {} {}, ACKing", trade.getClass().getSimpleName(), trade.getId());
                        handleTaskRunnerSuccess(peer, message);
                        return;
                    }
                    if (trade.getPayoutTx() != null) {
                        log.warn("We received a PaymentSentMessage but we have already created the payout tx " +
                                                "so we ignore the message. This can happen if the ACK message to the peer did not " +
                                                "arrive and the peer repeats sending us the message. We send another ACK msg.");
                        sendAckMessage(peer, message, true, null);
                        removeMailboxMessageAfterProcessing(message);
                        return;
                    }
                    latchTrade();
                    expect(anyPhase()
                            .with(message)
                            .from(peer))
                            .setup(tasks(
                                    ApplyFilter.class,
                                    ProcessPaymentSentMessage.class,
                                    VerifyPeersAccountAgeWitness.class)
                            .using(new TradeTaskRunner(trade,
                                    () -> {
                                        handleTaskRunnerSuccess(peer, message);
                                    },
                                    (errorMessage) -> {
                                        log.warn("Error processing payment sent message: " + errorMessage);
                                        processModel.getTradeManager().requestPersistence();
        
                                        // schedule to reprocess message unless deleted
                                        if (trade.getBuyer().getPaymentSentMessage() != null) {
                                            UserThread.runAfter(() -> {
                                                reprocessPaymentSentMessageCount++;
                                                maybeReprocessPaymentSentMessage(reprocessOnError);
                                            }, trade.getReprocessDelayInSeconds(reprocessPaymentSentMessageCount));
                                        } else {
                                            handleTaskRunnerFault(peer, message, errorMessage); // otherwise send nack
                                        }
                                        unlatchTrade();
                                    })))
                            .executeTasks(true);
                    awaitTradeLatch();
                }
            }, trade.getId());
        });
    }

    // received by buyer and arbitrator
    protected void handle(PaymentReceivedMessage message, NodeAddress peer) {
        handle(message, peer, true);
    }

    private void handle(PaymentReceivedMessage message, NodeAddress peer, boolean reprocessOnError) {
        log.info(LOG_HIGHLIGHT + "handle(PaymentReceivedMessage) for " + trade.getClass().getSimpleName() + " " + trade.getShortId() + " from " + peer);

        // ignore if not buyer or arbitrator
        if (!(trade instanceof BuyerTrade || trade instanceof ArbitratorTrade)) {
            log.warn("Ignoring PaymentReceivedMessage since not buyer or arbitrator");
            return;
        }

        // validate signature
        try {
            HavenoUtils.verifyPaymentReceivedMessage(trade, message);
        } catch (Throwable t) {
            log.warn("Ignoring PaymentReceivedMessage with invalid signature for {} {}, error={}", trade.getClass().getSimpleName(), trade.getId(), t.getMessage());
            return;
        }

        // save message for reprocessing
        trade.getSeller().setPaymentReceivedMessage(message);
        trade.persistNow(() -> {

            // process message on trade thread
            if (!trade.isInitialized() || trade.isShutDownStarted()) return;
            ThreadUtils.execute(() -> {
                synchronized (trade.getLock()) {
                    if (!trade.isInitialized() || trade.isShutDownStarted()) return;
                    if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_RECEIVED.ordinal() && trade.isPayoutPublished()) {
                        log.warn("Received another PaymentReceivedMessage which was already processed for {} {}, ACKing", trade.getClass().getSimpleName(), trade.getId());
                        handleTaskRunnerSuccess(peer, message);
                        return;
                    }
                    latchTrade();
                    Validator.checkTradeId(processModel.getOfferId(), message);
                    processModel.setTradeMessage(message);

                    // check minimum trade phase
                    if (trade.isBuyer() && trade.getPhase().ordinal() < Trade.Phase.PAYMENT_SENT.ordinal()) {
                        log.warn("Received PaymentReceivedMessage before payment sent for {} {}, ignoring", trade.getClass().getSimpleName(), trade.getId());
                        return;
                    }
                    if (trade.isArbitrator() && trade.getPhase().ordinal() < Trade.Phase.DEPOSITS_CONFIRMED.ordinal()) {
                        log.warn("Received PaymentReceivedMessage before deposits confirmed for {} {}, ignoring", trade.getClass().getSimpleName(), trade.getId());
                        return;
                    }
                    if (trade.isSeller() && trade.getPhase().ordinal() < Trade.Phase.DEPOSITS_UNLOCKED.ordinal()) {
                        log.warn("Received PaymentReceivedMessage before deposits unlocked for {} {}, ignoring", trade.getClass().getSimpleName(), trade.getId());
                        return;
                    }

                    expect(anyPhase()
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

                                    // schedule to reprocess message or nack
                                    if (trade.getSeller().getPaymentReceivedMessage() != null) {
                                        if (reprocessOnError) {
                                            UserThread.runAfter(() -> {
                                                reprocessPaymentReceivedMessageCount++;
                                                maybeReprocessPaymentReceivedMessage(reprocessOnError);
                                            }, trade.getReprocessDelayInSeconds(reprocessPaymentReceivedMessageCount));
                                        }
                                    } else {
                                        trade.exportMultisigHex(); // export fresh multisig info for nack
                                        handleTaskRunnerFault(peer, message, null, errorMessage, trade.getSelf().getUpdatedMultisigHex()); // send nack
                                    }
                                    unlatchTrade();
                                })))
                        .executeTasks(true);
                    awaitTradeLatch();
                }
            }, trade.getId());
        });
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
                                result.getInfo(),
                                null);
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

    // TODO: this has grown in complexity over time and could use refactoring
    private void onAckMessage(AckMessage ackMessage, NodeAddress sender) {

        // ignore if trade is completely finished
        if (trade.isFinished()) return;

        // get trade peer
        TradePeer peer = trade.getTradePeer(sender);
        if (peer == null) {
            if (ackMessage.getSourceUid().equals(HavenoUtils.getDeterministicId(trade, DepositsConfirmedMessage.class, trade.getArbitrator().getNodeAddress()))) peer = trade.getArbitrator();
            else if (ackMessage.getSourceUid().equals(HavenoUtils.getDeterministicId(trade, DepositsConfirmedMessage.class, trade.getMaker().getNodeAddress()))) peer = trade.getMaker();
            else if (ackMessage.getSourceUid().equals(HavenoUtils.getDeterministicId(trade, DepositsConfirmedMessage.class, trade.getTaker().getNodeAddress()))) peer = trade.getTaker();
        }
        if (peer == null) {
            if (ackMessage.isSuccess()) log.warn("Received AckMessage from unknown peer for {}, sender={}, trade={} {}, messageUid={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid());
            else log.warn("Received AckMessage with error state from unknown peer for {}, sender={}, trade={} {}, messageUid={}, errorMessage={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.getErrorMessage());
            return;
        }

        // update sender's node address
        if (!peer.getNodeAddress().equals(sender)) {
            log.info("Updating peer's node address from {} to {} using ACK message to {}", peer.getNodeAddress(), sender, ackMessage.getSourceMsgClassName());
            peer.setNodeAddress(sender);
        }

        // TODO: arbitrator may nack maker's InitTradeRequest if reserve tx has become invalid (e.g. check_tx_key shows 0 funds received). recreate reserve tx in this case
        if (!ackMessage.isSuccess() && trade.isMaker() && peer == trade.getArbitrator() && ackMessage.getSourceMsgClassName().equals(InitTradeRequest.class.getSimpleName())) {
            if (ackMessage.getErrorMessage() != null && ackMessage.getErrorMessage().contains(SEND_INIT_TRADE_REQUEST_FAILED)) {
                // use default postprocessing to cancel maker's trade if arbitrator cannot send message to taker
            } else {
                if (makerInitTradeRequestHasBeenNacked) {
                    handleSecondMakerInitTradeRequestNack(ackMessage);
                    // use default postprocessing to cancel maker's trade
                } else {
                    makerInitTradeRequestHasBeenNacked = true;
                    handleFirstMakerInitTradeRequestNack(ackMessage);
                    return;
                }
            }
        }

        // handle nack of deposit request
        if (ackMessage.getSourceMsgClassName().equals(DepositRequest.class.getSimpleName())) {
            if (!ackMessage.isSuccess()) {
                trade.setStateIfValidTransitionTo(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
                processModel.getTradeManager().requestPersistence();
            }
        }

        // handle ack message for DepositsConfirmedMessage, which automatically re-sends if not ACKed in a certain time
        if (ackMessage.getSourceMsgClassName().equals(DepositsConfirmedMessage.class.getSimpleName())) {
            peer.setDepositsConfirmedAckMessage(ackMessage);
            processModel.getTradeManager().requestPersistence();
        }

        // handle ack message for PaymentSentMessage, which automatically re-sends if not ACKed in a certain time
        if (ackMessage.getSourceMsgClassName().equals(PaymentSentMessage.class.getSimpleName())) {
            if (peer == trade.getSeller()) {
                trade.getSeller().setPaymentSentAckMessage(ackMessage);
                if (ackMessage.isSuccess()) trade.setStateIfValidTransitionTo(Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG);
                else trade.setState(Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG);
                processModel.getTradeManager().requestPersistence();
            } else if (peer == trade.getArbitrator()) {
                trade.getArbitrator().setPaymentSentAckMessage(ackMessage);
                processModel.getTradeManager().requestPersistence();
            } else {
                log.warn("Received AckMessage from unexpected peer for {}, sender={}, trade={} {}, messageUid={}, success={}, errorMsg={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.isSuccess(), ackMessage.getErrorMessage());
                return;
            }
        }

        // handle ack message for PaymentReceivedMessage, which automatically re-sends if not ACKed in a certain time
        if (ackMessage.getSourceMsgClassName().equals(PaymentReceivedMessage.class.getSimpleName())) {

            // ack message from buyer
            if (peer == trade.getBuyer()) {
                trade.getBuyer().setPaymentReceivedAckMessage(ackMessage);
                processModel.getTradeManager().persistNow(null);

                // handle successful ack
                if (ackMessage.isSuccess()) {
                    trade.setStateIfValidTransitionTo(Trade.State.BUYER_RECEIVED_PAYMENT_RECEIVED_MSG);
                    processModel.getTradeManager().persistNow(null);
                }
                
                // handle nack
                else {
                    log.warn("We received a NACK for our PaymentReceivedMessage to the buyer for {} {}", trade.getClass().getSimpleName(), trade.getId());

                    // nack includes updated multisig hex since v1.1.1
                    if (ackMessage.getUpdatedMultisigHex() != null) {
                        trade.getBuyer().setUpdatedMultisigHex(ackMessage.getUpdatedMultisigHex());
                        processModel.getTradeManager().persistNow(null);
                        boolean autoResent = processPaymentReceivedNack(ackMessage);
                        if (autoResent) return; // skip remaining processing if auto resent
                    }
                }
            }
            
            // ack message from arbitrator
            else if (peer == trade.getArbitrator()) {
                trade.getArbitrator().setPaymentReceivedAckMessage(ackMessage);
                processModel.getTradeManager().persistNow(null);

                // handle nack
                if (!ackMessage.isSuccess()) {
                    log.warn("We received a NACK for our PaymentReceivedMessage to the arbitrator for {} {}", trade.getClass().getSimpleName(), trade.getId());

                    // nack includes updated multisig hex since v1.1.1
                    if (ackMessage.getUpdatedMultisigHex() != null) {
                        trade.getArbitrator().setUpdatedMultisigHex(ackMessage.getUpdatedMultisigHex());
                        processModel.getTradeManager().persistNow(null);
                        boolean autoResent = processPaymentReceivedNack(ackMessage);
                        if (autoResent) return; // skip remaining processing if auto resent
                    }
                }
            } else {
                log.warn("Received AckMessage from unexpected peer for {}, sender={}, trade={} {}, messageUid={}, success={}, errorMsg={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.isSuccess(), ackMessage.getErrorMessage());
                return;
            }

            // clear and shut down trade if completely finished after ack
            if (trade.isFinished()) {
                log.info("Trade {} {} is finished after PaymentReceivedMessage ACK, shutting it down", trade.getClass().getSimpleName(), trade.getId());
                trade.clearAndShutDown();
            }
        }

        // generic handling
        if (ackMessage.isSuccess()) {
            log.info("Received AckMessage for {}, sender={}, trade={} {}, messageUid={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid());
        } else {
            log.warn("Received AckMessage with error state for {}, sender={}, trade={} {}, messageUid={}, errorMessage={}", ackMessage.getSourceMsgClassName(), sender, trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.getErrorMessage());
            handleError("Your peer had a problem processing your message. Please ensure you and your peer are running the latest version and try again.\n\nError details:\n" + ackMessage.getErrorMessage());
        }

        // notify trade listeners
        trade.onAckMessage(ackMessage, sender);
    }

    private boolean processPaymentReceivedNack(AckMessage ackMessage) {
        synchronized (trade.getLock()) {
            if (trade.isPaymentReceived() && !trade.isPayoutPublished() && !isPaymentReceivedMessageAckedByEither()) {
                log.warn("Resetting state to payment sent for {} {}", trade.getClass().getSimpleName(), trade.getId());
                trade.resetToPaymentSentState();
                trade.getProcessModel().setPaymentSentPayoutTxStale(true);
                trade.getSelf().setUnsignedPayoutTxHex(null);

                // automatically mark payment received again once on nack
                if (autoMarkPaymentReceivedOnNack) {
                    autoMarkPaymentReceivedOnNack = false;
                    log.warn("Automatically marking payment received on NACK for {} {} after error={}", trade.getClass().getSimpleName(), trade.getId(), ackMessage.getErrorMessage());
                    UserThread.execute(() -> {
                        ((SellerProtocol) this).onPaymentReceived(null, null);
                    });
                    return true;
                }
            }
            return false;
        }
    }

    private void handleFirstMakerInitTradeRequestNack(AckMessage ackMessage) {
        log.warn("Maker received NACK to InitTradeRequest from arbitrator for {} {}, messageUid={}, errorMessage={}", trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.getErrorMessage());
        ThreadUtils.execute(() -> {
            Event event = new Event() {
                @Override
                public String name() {
                    return "MakerRecreateReserveTx";
                }
            };
            synchronized (trade.getLock()) {
                latchTrade();
                expect(phase(Trade.Phase.INIT)
                        .with(event))
                        .setup(tasks(
                                MakerRecreateReserveTx.class,
                                MakerSendInitTradeRequestToArbitrator.class)
                        .using(new TradeTaskRunner(trade,
                                () -> {
                                    startTimeout();
                                    unlatchTrade();
                                },
                                errorMessage -> {
                                    handleError("Failed to re-send InitTradeRequest to arbitrator for " + trade.getClass().getSimpleName() + " " + trade.getId() + ": " + errorMessage);
                                }))
                        .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                        .executeTasks(true);
                awaitTradeLatch();
            }
        }, trade.getId());
    }

    private void handleSecondMakerInitTradeRequestNack(AckMessage ackMessage) {
        log.warn("Maker received 2nd NACK to InitTradeRequest from arbitrator for {} {}, messageUid={}, errorMessage={}", trade.getClass().getSimpleName(), trade.getId(), ackMessage.getSourceUid(), ackMessage.getErrorMessage());
        String warningMessage = "Your offer (" + trade.getOffer().getShortId() + ") has been removed because there was a problem taking the trade.\n\nError message: " + ackMessage.getErrorMessage();
        OpenOffer openOffer = HavenoUtils.openOfferManager.getOpenOffer(trade.getId()).orElse(null);
        if (openOffer != null) {
            HavenoUtils.openOfferManager.cancelOpenOffer(openOffer, null, null);
            HavenoUtils.setTopError(warningMessage);
        }
        log.warn(warningMessage);
    }

    private boolean isPaymentReceivedMessageAckedByEither() {
        if (trade.getBuyer().getPaymentReceivedMessageStateProperty().get() == MessageState.ACKNOWLEDGED) return true;
        if (trade.getArbitrator().getPaymentReceivedMessageStateProperty().get() == MessageState.ACKNOWLEDGED) return true;
        return false;
    }

    protected void sendAckMessage(NodeAddress peer, TradeMessage message, boolean result, @Nullable String errorMessage) {
        sendAckMessage(peer, message, result, errorMessage, null);
    }

    protected void sendAckMessage(NodeAddress peer, TradeMessage message, boolean result, @Nullable String errorMessage, String updatedMultisigHex) {

        // get peer's pub key ring
        PubKeyRing peersPubKeyRing = getPeersPubKeyRing(peer);
        if (peersPubKeyRing == null) {
            log.error("We cannot send the ACK message as peersPubKeyRing is null");
            return;
        }

        // send ack message
        processModel.getTradeManager().sendAckMessage(peer, peersPubKeyRing, message, result, errorMessage, updatedMultisigHex);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Timeout
    ///////////////////////////////////////////////////////////////////////////////////////////

    public synchronized void startTimeout() {
        startTimeout(TradeProtocol.TRADE_STEP_TIMEOUT_SECONDS);
    }

    public synchronized void startTimeout(long timeoutSec) {
        synchronized (timeoutTimerLock) {
            stopTimeout();
            timeoutTimer = UserThread.runAfter(() -> {
                handleError(TIMEOUT_REACHED + " Protocol did not complete in " + timeoutSec + " sec. TradeID=" + trade.getId() + ", state=" + trade.stateProperty().get());
            }, timeoutSec);
        }
    }

    public synchronized void stopTimeout() {
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
        handleTaskRunnerFault(sender, message, message.getClass().getSimpleName(), errorMessage, null);
    }

    protected void handleTaskRunnerFault(FluentProtocol.Event event, String errorMessage) {
        handleTaskRunnerFault(null, null, event.name(), errorMessage, null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Validation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PubKeyRing getPeersPubKeyRing(NodeAddress address) {
      trade.setMyNodeAddress(); // TODO: this is a hack to update my node address before verifying the message
      TradePeer peer = trade.getTradePeer(address);
      if (peer == null) {
        log.warn("Cannot get peer's pub key ring because peer is not maker, taker, or arbitrator. Their address might have changed: " + address);
        return null;
      }
      return peer.getPubKeyRing();
    }

    public boolean isPubKeyValid(DecryptedMessageWithPubKey message) {
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

    void handleTaskRunnerFault(NodeAddress ackReceiver, @Nullable TradeMessage message, String source, String errorMessage, String updatedMultisigHex) {
        log.error("Task runner failed with error {}. Triggered from {}. Monerod={}" , errorMessage, source, trade.getXmrWalletService().getXmrConnectionService().getConnection());

        handleError(errorMessage);

        if (message != null) {
            sendAckMessage(ackReceiver, message, false, errorMessage, updatedMultisigHex);
        }
    }

    // these are not thread safe, so they must be used within a lock on the trade

    protected void handleError(String errorMessage) {
        stopTimeout();
        log.error(errorMessage);
        trade.setErrorMessage(errorMessage);
        processModel.getTradeManager().requestPersistence();
        unlatchTrade();
        if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage(errorMessage);
        errorMessageHandler = null;
    }

    protected void latchTrade() {
        trade.awaitInitialized();
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
            return tradeMessage.getOfferId().equals(trade.getId());
        } else if (message instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) message;
            return ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE && ackMessage.getSourceId().equals(trade.getId());
        } else {
            return false;
        }
    }
}
