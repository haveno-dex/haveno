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
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.account.sign.SignedWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.TradeMailboxMessage;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@EqualsAndHashCode(callSuper = true)
public abstract class SellerSendPaymentReceivedMessage extends SendMailboxMessageTask {
    SignedWitness signedWitness = null;

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
            PaymentReceivedMessage message = new PaymentReceivedMessage(
                    tradeId,
                    processModel.getMyNodeAddress(),
                    deterministicId,
                    trade.getPayoutTxHex() == null ? trade.getSelf().getUnsignedPayoutTxHex() : null, // unsigned // TODO: phase in after next update to clear old style trades
                    trade.getPayoutTxHex() == null ? null : trade.getPayoutTxHex(), // signed
                    trade.getSelf().getUpdatedMultisigHex(),
                    trade.getState().ordinal() >= Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG.ordinal(), // informs to expect payout
                    trade.getTradePeer().getAccountAgeWitness(),
                    signedWitness,
                    getReceiver() == trade.getArbitrator() ? trade.getBuyer().getPaymentSentMessage() : null // buyer already has payment sent message
            );
            checkArgument(message.getUnsignedPayoutTxHex() != null || message.getSignedPayoutTxHex() != null, "PaymentReceivedMessage does not include payout tx hex");

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
        trade.advanceState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG);
        log.info("{} sent: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        trade.advanceState(Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG);
        log.error("{} failed: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.advanceState(Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG);
        log.info("{} stored in mailbox: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.advanceState(Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG);
        log.info("{} arrived: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }
}
