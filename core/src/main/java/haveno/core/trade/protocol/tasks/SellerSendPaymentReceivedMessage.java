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

package haveno.core.trade.protocol.tasks;

import com.google.common.base.Charsets;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.account.sign.SignedWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.network.MessageState;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.NodeAddress;
import javafx.beans.value.ChangeListener;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

@Slf4j
@EqualsAndHashCode(callSuper = true)
public abstract class SellerSendPaymentReceivedMessage extends SendMailboxMessageTask {
    private SignedWitness signedWitness = null;
    private ChangeListener<MessageState> listener;
    private Timer timer;
    private static final int MAX_RESEND_ATTEMPTS = 20;
    private int delayInMin = 10;
    private int resendCounter = 0;
    private String unsignedPayoutTxHex = null;
    private String signedPayoutTxHex = null;
    private String updatedMultisigHex = null;

    public SellerSendPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }
    
    protected abstract TradePeer getReceiver();
    
    @Override
    protected NodeAddress getReceiverNodeAddress() {
        return getReceiver().getNodeAddress();
    }

    @Override
    protected PubKeyRing getReceiverPubKeyRing() {
        return getReceiver().getPubKeyRing();
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if stopped
            if (stopSending()) {
                if (!isCompleted()) complete();
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (getReceiver().getPaymentReceivedMessage() == null) {

            // sign account witness
            AccountAgeWitnessService accountAgeWitnessService = processModel.getAccountAgeWitnessService();
            if (accountAgeWitnessService.isSignWitnessTrade(trade)) {
                try {
                    accountAgeWitnessService.traderSignAndPublishPeersAccountAgeWitness(trade).ifPresent(witness -> signedWitness = witness);
                    log.info("{} {} signed and published peers account age witness", trade.getClass().getSimpleName(), trade.getId());
                } catch (Exception e) {
                    log.warn("Failed to sign and publish peer's account age witness for {} {}, error={}\n", getClass().getSimpleName(), trade.getId(), e.getMessage(), e);
                }
            }

            // We do not use a real unique ID here as we want to be able to re-send the exact same message in case the
            // peer does not respond with an ACK msg in a certain time interval. To avoid that we get dangling mailbox
            // messages where only the one which gets processed by the peer would be removed we use the same uid. All
            // other data stays the same when we re-send the message at any time later.
            String deterministicId = HavenoUtils.getDeterministicId(trade, PaymentReceivedMessage.class, getReceiverNodeAddress());
            boolean deferPublishPayout = getReceiver() == trade.getArbitrator() && (trade.isPayoutPublished() || trade.getOtherPeer(getReceiver()).isPaymentReceivedMessageArrived()); // informs receiver to expect payout so delay processing
            unsignedPayoutTxHex = trade.getPayoutTxHex() == null ? trade.getSelf().getUnsignedPayoutTxHex() : null; // signed
            signedPayoutTxHex = trade.getPayoutTxHex();
            updatedMultisigHex = trade.getSelf().getUpdatedMultisigHex();
            PaymentReceivedMessage message = new PaymentReceivedMessage(
                    tradeId,
                    processModel.getMyNodeAddress(),
                    deterministicId,
                    unsignedPayoutTxHex,
                    signedPayoutTxHex,
                    updatedMultisigHex,
                    deferPublishPayout,
                    trade.getTradePeer().getAccountAgeWitness(),
                    signedWitness,
                    getReceiver() == trade.getArbitrator() ? trade.getBuyer().getPaymentSentMessage() : null, // buyer already has payment sent message,
                    trade.getPayoutTxId()
            );

            // verify message
            if (trade.isPayoutPublished()) {
                checkArgument(message.getUpdatedMultisigHex() != null || message.getPayoutTxId() != null, "PaymentReceivedMessage does not include updated multisig hex or payout tx id after payout published");
            } else {
                checkArgument(message.getUnsignedPayoutTxHex() != null || message.getSignedPayoutTxHex() != null, "PaymentReceivedMessage does not include payout tx hex");
            }

            // sign message
            try {
                String messageAsJson = JsonUtil.objectToJson(message);
                byte[] sig = Sig.sign(processModel.getP2PService().getKeyRing().getSignatureKeyPair().getPrivate(), messageAsJson.getBytes(Charsets.UTF_8));
                message.setSellerSignature(sig);
                getReceiver().setPaymentReceivedMessage(message);
                trade.requestPersistence();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return getReceiver().getPaymentReceivedMessage();
    }

    @Override
    protected void setStateSent() {
        log.info("{} sent: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        getReceiver().setPaymentReceivedMessageState(MessageState.SENT);
        tryToSendAgainLater();
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        log.error("{} failed: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        getReceiver().setPaymentReceivedMessageState(MessageState.FAILED);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        log.info("{} stored in mailbox: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        getReceiver().setPaymentReceivedMessageState(MessageState.STORED_IN_MAILBOX);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        log.info("{} arrived: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        getReceiver().setPaymentReceivedMessageState(MessageState.ARRIVED);
        processModel.getTradeManager().requestPersistence();
    }

    private void cleanup() {
        if (timer != null) {
            timer.stop();
        }
        if (listener != null) {
            trade.getBuyer().getPaymentReceivedMessageStateProperty().removeListener(listener);
        }
    }

    private void tryToSendAgainLater() {

        // skip if stopped
        if (stopSending()) return;

        if (resendCounter >= MAX_RESEND_ATTEMPTS) {
            cleanup();
            log.warn("We never received an ACK message when sending the PaymentReceivedMessage to the peer. We stop trying to send the message.");
            return;
        }

        if (timer != null) {
            timer.stop();
        }

        timer = UserThread.runAfter(this::run, delayInMin, TimeUnit.MINUTES);

        if (resendCounter == 0) {
            listener = (observable, oldValue, newValue) -> onMessageStateChange(newValue);
            getReceiver().getPaymentReceivedMessageStateProperty().addListener(listener);
            onMessageStateChange(getReceiver().getPaymentReceivedMessageStateProperty().get());
        }

        // first re-send is after 2 minutes, then increase the delay exponentially
        if (resendCounter == 0) {
            int shortDelay = 2;
            log.info("We will send the message again to the peer after a delay of {} min.", shortDelay);
            timer = UserThread.runAfter(this::run, shortDelay, TimeUnit.MINUTES);
        } else {
            log.info("We will send the message again to the peer after a delay of {} min.", delayInMin);
            timer = UserThread.runAfter(this::run, delayInMin, TimeUnit.MINUTES);
            delayInMin = (int) ((double) delayInMin * 1.5);
        }
        resendCounter++;
    }

    private void onMessageStateChange(MessageState newValue) {
        if (isMessageReceived()) {
            cleanup();
        }
    }

    protected boolean isMessageReceived() {
        return getReceiver().isPaymentReceivedMessageReceived();
    }

    protected boolean stopSending() {
        if (isMessageReceived()) return true; // stop if message received
        if (!trade.isPaymentReceived()) return true; // stop if trade state reset
        if (trade.isPayoutPublished() && !((SellerTrade) trade).resendPaymentReceivedMessagesWithinDuration()) return true; // stop if payout is published and we are not in the resend period

        // check if message state is outdated
        if (unsignedPayoutTxHex != null && !StringUtils.equals(unsignedPayoutTxHex, trade.getSelf().getUnsignedPayoutTxHex())) return true;
        if (signedPayoutTxHex != null && !StringUtils.equals(signedPayoutTxHex, trade.getPayoutTxHex())) return true;
        if (updatedMultisigHex != null && !StringUtils.equals(updatedMultisigHex, trade.getSelf().getUpdatedMultisigHex())) return true;
        return false;
    }
}
