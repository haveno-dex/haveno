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

package bisq.core.trade.protocol;

import bisq.core.offer.Offer;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.messages.CounterCurrencyTransferStartedMessage;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.messages.UpdateMultisigRequest;
import bisq.core.trade.protocol.tasks.ProcessUpdateMultisigRequest;
import bisq.core.util.Validator;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendMailboxMessageListener;
import bisq.network.p2p.mailbox.MailboxMessage;
import bisq.network.p2p.mailbox.MailboxMessageService;
import bisq.network.p2p.messaging.DecryptedMailboxListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.PubKeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.taskrunner.Task;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public abstract class TradeProtocol implements DecryptedDirectMessageListener, DecryptedMailboxListener {

    protected final ProcessModel processModel;
    protected final Trade trade;
    private Timer timeoutTimer;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradeProtocol(Trade trade) {
        this.trade = trade;
        this.processModel = trade.getProcessModel();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void initialize(ProcessModelServiceProvider serviceProvider, TradeManager tradeManager, Offer offer) {
        processModel.applyTransient(serviceProvider, tradeManager, offer);
        onInitialized();
    }

    protected void onInitialized() {
        if (!trade.isWithdrawn()) {
            processModel.getP2PService().addDecryptedDirectMessageListener(this);
        }

        MailboxMessageService mailboxMessageService = processModel.getP2PService().getMailboxMessageService();
        // We delay a bit here as the trade gets updated from the wallet to update the trade
        // state (deposit confirmed) and that happens after our method is called.
        // TODO To fix that in a better way we would need to change the order of some routines
        // from the TradeManager, but as we are close to a release I dont want to risk a bigger
        // change and leave that for a later PR
        UserThread.runAfter(() -> {
            mailboxMessageService.addDecryptedMailboxListener(this);
            handleMailboxCollection(mailboxMessageService.getMyDecryptedMailboxMessages());
        }, 100, TimeUnit.MILLISECONDS);
    }

    public void onWithdrawCompleted() {
        cleanup();
    }

    protected void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        log.info("Received {} as MailboxMessage from {} with tradeId {} and uid {}",
                message.getClass().getSimpleName(), peerNodeAddress, message.getTradeId(), message.getUid());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

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


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedMailboxListener
    ///////////////////////////////////////////////////////////////////////////////////////////

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
                .forEach(this::handleMailboxMessage);
    }

    private void handleMailboxMessage(MailboxMessage mailboxMessage) {
        if (mailboxMessage instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) mailboxMessage;
            // We only remove here if we have already completed the trade.
            // Otherwise removal is done after successfully applied the task runner.
            if (trade.isWithdrawn()) {
                processModel.getP2PService().getMailboxMessageService().removeMailboxMsg(mailboxMessage);
                log.info("Remove {} from the P2P network as trade is already completed.",
                        tradeMessage.getClass().getSimpleName());
                return;
            }
            onMailboxMessage(tradeMessage, mailboxMessage.getSenderNodeAddress());
        } else if (mailboxMessage instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) mailboxMessage;
            if (!trade.isWithdrawn()) {
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
    // Abstract
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected abstract void onTradeMessage(TradeMessage message, NodeAddress peer);
    public abstract void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress peer, ErrorMessageHandler errorMessageHandler);
    public abstract void handleSignContractRequest(SignContractRequest request, NodeAddress peer, ErrorMessageHandler errorMessageHandler);

    // TODO (woodser): update to use fluent for consistency
    public void handleUpdateMultisigRequest(UpdateMultisigRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
      Validator.checkTradeId(processModel.getOfferId(), message);
      processModel.setTradeMessage(message);

      TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
              () -> {
                stopTimeout();
                handleTaskRunnerSuccess(peer, message, "handleUpdateMultisigRequest");
              },
              errorMessage -> {
                  errorMessageHandler.handleErrorMessage(errorMessage);
                  handleTaskRunnerFault(peer, message, errorMessage);
              });
      taskRunner.addTasks(
              ProcessUpdateMultisigRequest.class
      );
      startTimeout(60);  // TODO (woodser): what timeout to use?  don't hardcode
      taskRunner.run();
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

    @SafeVarargs
    public final FluentProtocol.Setup tasks(Class<? extends Task<Trade>>... tasks) {
        return new FluentProtocol.Setup(this, trade).tasks(tasks);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ACK msg
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): support notifications of ack messages
    private void onAckMessage(AckMessage ackMessage, NodeAddress peer) {
        // We handle the ack for CounterCurrencyTransferStartedMessage and DepositTxAndDelayedPayoutTxMessage
        // as we support automatic re-send of the msg in case it was not ACKed after a certain time
        // TODO (woodser): add AckMessage for InitTradeRequest and support automatic re-send ?
        if (ackMessage.getSourceMsgClassName().equals(CounterCurrencyTransferStartedMessage.class.getSimpleName())) {
            processModel.setPaymentStartedAckMessage(ackMessage);
        } else if (ackMessage.getSourceMsgClassName().equals(DepositTxAndDelayedPayoutTxMessage.class.getSimpleName())) {
            processModel.setDepositTxSentAckMessage(ackMessage);
        }

        if (ackMessage.isSuccess()) {
            log.info("Received AckMessage for {} from {} with tradeId {} and uid {}",
                    ackMessage.getSourceMsgClassName(), peer, trade.getId(), ackMessage.getSourceUid());
        } else {
            log.warn("Received AckMessage with error state for {} from {} with tradeId {} and errorMessage={}",
                    ackMessage.getSourceMsgClassName(), peer, trade.getId(), ackMessage.getErrorMessage());
        }
    }

    protected void sendAckMessage(NodeAddress peer, TradeMessage message, boolean result, @Nullable String errorMessage) {
        
        // TODO (woodser): remove trade.getTradingPeerNodeAddress() and processModel.getTempTradingPeerNodeAddress() if everything should be maker, taker, or arbitrator

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

    protected void startTimeout(long timeoutSec) {
        stopTimeout();

        timeoutTimer = UserThread.runAfter(() -> {
            log.error("Timeout reached. TradeID={}, state={}, timeoutSec={}",
                    trade.getId(), trade.stateProperty().get(), timeoutSec);
            trade.setErrorMessage("Timeout reached. Protocol did not complete in " + timeoutSec + " sec.");

            processModel.getTradeManager().requestPersistence();
            cleanup();
        }, timeoutSec);
    }

    protected void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
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

    private PubKeyRing getPeersPubKeyRing(NodeAddress peer) {
      if (peer.equals(trade.getArbitratorNodeAddress())) return trade.getArbitratorPubKeyRing();
      else if (peer.equals(trade.getMakerNodeAddress())) return trade.getMakerPubKeyRing();
      else if (peer.equals(trade.getTakerNodeAddress())) return trade.getTakerPubKeyRing();
      else {
        log.error("Cannot get peer's pub key ring because peer is not maker, taker, or arbitrator");
        return null;
      }
    }

    private boolean isPubKeyValid(DecryptedMessageWithPubKey message) {
        MailboxMessage mailboxMessage = (MailboxMessage) message.getNetworkEnvelope();
        NodeAddress sender = mailboxMessage.getSenderNodeAddress();
        return isPubKeyValid(message, sender);
    }

    private boolean isPubKeyValid(DecryptedMessageWithPubKey message, NodeAddress sender) {
        // We can only validate the peers pubKey if we have it already. If we are the taker we get it from the offer
        // Otherwise it depends on the state of the trade protocol if we have received the peers pubKeyRing already.
        PubKeyRing peersPubKeyRing = getPeersPubKeyRing(sender);
        boolean isValid = true; // TODO (woodser): this returns valid=true even if peer's pub key ring is null?
        if (peersPubKeyRing != null &&
                !message.getSignaturePubKey().equals(peersPubKeyRing.getSignaturePubKey())) {
            isValid = false;
            log.error("SignaturePubKey in message does not match the SignaturePubKey we have set for our trading peer.");
        }
        return isValid;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleTaskRunnerSuccess(NodeAddress sender, @Nullable TradeMessage message, String source) {
        log.info("TaskRunner successfully completed. Triggered from {}, tradeId={}", source, trade.getId());
        if (message != null) {
            sendAckMessage(sender, message, true, null);

            // Once a taskRunner is completed we remove the mailbox message. To not remove it directly at the task
            // adds some resilience in case of minor errors, so after a restart the mailbox message can be applied
            // again.
            removeMailboxMessageAfterProcessing(message);
        }
    }

    void handleTaskRunnerFault(NodeAddress ackReceiver, @Nullable TradeMessage message, String source, String errorMessage) {
        log.error("Task runner failed with error {}. Triggered from {}", errorMessage, source);

        if (message != null) {
            sendAckMessage(ackReceiver, message, false, errorMessage);
        }
        cleanup();
    }

    private boolean isMyMessage(NetworkEnvelope message) {
        if (message instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) message;
            return tradeMessage.getTradeId().equals(trade.getId());
        } else if (message instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) message;
            return ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE &&
                    ackMessage.getSourceId().equals(trade.getId());
        } else {
            return false;
        }
    }

    private void cleanup() {
        stopTimeout();
        // We do not remove the decryptedDirectMessageListener as in case of not critical failures we want allow to receive
        // follow-up messages still
    }
}
