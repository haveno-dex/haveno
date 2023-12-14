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

import common.utils.GenUtils;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.account.sign.SignedWitness;
import haveno.core.support.dispute.Dispute;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.util.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ProcessPaymentReceivedMessage extends TradeTask {
    public ProcessPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            log.debug("current trade state " + trade.getState());
            PaymentReceivedMessage message = (PaymentReceivedMessage) processModel.getTradeMessage();
            checkNotNull(message);
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkArgument(message.getUnsignedPayoutTxHex() != null || message.getSignedPayoutTxHex() != null, "No payout tx hex provided");

            // verify signature of payment received message
            HavenoUtils.verifyPaymentReceivedMessage(trade, message);

            // update to the latest peer address of our peer if message is correct
            trade.getSeller().setNodeAddress(processModel.getTempTradePeerNodeAddress());
            if (trade.getSeller().getNodeAddress().equals(trade.getBuyer().getNodeAddress())) trade.getBuyer().setNodeAddress(null); // tests can reuse addresses

            // ack and complete if already processed
            if (trade.getPhase().ordinal() >= Trade.Phase.PAYMENT_RECEIVED.ordinal()) {
                log.warn("Received another PaymentReceivedMessage which was already processed, ACKing");
                complete();
                return;
            }

            // save message for reprocessing
            trade.getSeller().setPaymentReceivedMessage(message);

            // set state
            trade.getSeller().setUpdatedMultisigHex(message.getUpdatedMultisigHex());
            trade.getBuyer().setAccountAgeWitness(message.getBuyerAccountAgeWitness());
            if (trade.isArbitrator() && trade.getBuyer().getPaymentSentMessage() == null) {
                checkNotNull(message.getPaymentSentMessage(), "PaymentSentMessage is null for arbitrator");
                trade.getBuyer().setPaymentSentMessage(message.getPaymentSentMessage());
                trade.getBuyer().setUpdatedMultisigHex(message.getPaymentSentMessage().getUpdatedMultisigHex());
            }
            trade.requestPersistence();


            // process payout tx unless already unlocked
            if (!trade.isPayoutUnlocked()) processPayoutTx(message);

            // close open disputes
            if (trade.isPayoutPublished() && trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_REQUESTED.ordinal()) {
                trade.advanceDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                for (Dispute dispute : trade.getDisputes()) dispute.setIsClosed();
            }

            // publish signed witness
            SignedWitness signedWitness = message.getBuyerSignedWitness();
            if (signedWitness != null && trade instanceof BuyerTrade) {
                // We received the signedWitness from the seller and publish the data to the network.
                // The signer has published it as well but we prefer to re-do it on our side as well to achieve higher
                // resilience.
                processModel.getAccountAgeWitnessService().publishOwnSignedWitness(signedWitness);
            }

            // complete
            trade.advanceState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG); // arbitrator auto completes when payout published
            trade.requestPersistence();
            complete();
        } catch (Throwable t) {

            // do not reprocess illegal argument
            if (t instanceof IllegalArgumentException) {
                trade.getSeller().setPaymentReceivedMessage(null); // do not reprocess
                trade.requestPersistence();
            }

            failed(t);
        }
    }

    private void processPayoutTx(PaymentReceivedMessage message) {

        // update wallet
        trade.importMultisigHex();
        trade.syncAndPollWallet();
        trade.saveWallet();

        // handle if payout tx not published
        if (!trade.isPayoutPublished()) {

            // wait to sign and publish payout tx if defer flag set (seller recently saw payout tx arrive at buyer)
            boolean isSigned = message.getSignedPayoutTxHex() != null;
            boolean deferSignAndPublish = trade instanceof ArbitratorTrade && !isSigned && message.isDeferPublishPayout();
            if (deferSignAndPublish) {
                log.info("Deferring signing and publishing payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                GenUtils.waitFor(Trade.DEFER_PUBLISH_MS);
                if (!trade.isPayoutUnlocked()) trade.syncAndPollWallet();
            }

            // verify and publish payout tx
            if (!trade.isPayoutPublished()) {
                if (isSigned) {
                    log.info("{} {} publishing signed payout tx from seller", trade.getClass().getSimpleName(), trade.getId());
                    trade.processPayoutTx(message.getSignedPayoutTxHex(), false, true);
                } else {
                    try {
                        PaymentSentMessage paymentSentMessage = (trade.isArbitrator() ? trade.getBuyer() : trade.getArbitrator()).getPaymentSentMessage();
                        if (paymentSentMessage == null) throw new RuntimeException("Process model does not have payment sent message for " + trade.getClass().getSimpleName() + " " + trade.getId());
                        if (StringUtils.equals(trade.getPayoutTxHex(), paymentSentMessage.getPayoutTxHex())) { // unsigned
                            log.info("{} {} verifying, signing, and publishing payout tx", trade.getClass().getSimpleName(), trade.getId());
                            trade.processPayoutTx(message.getUnsignedPayoutTxHex(), true, true);
                        } else {
                            log.info("{} {} re-verifying and publishing payout tx", trade.getClass().getSimpleName(), trade.getId());
                            trade.processPayoutTx(trade.getPayoutTxHex(), false, true);
                        }
                    } catch (Exception e) {
                        trade.syncAndPollWallet();
                        if (trade.isPayoutPublished()) log.info("Payout tx already published for {} {}", trade.getClass().getName(), trade.getId());
                        else throw e;
                    }
                }
            }
        } else {
            log.info("Payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
            if (message.getSignedPayoutTxHex() != null && !trade.isPayoutConfirmed()) trade.processPayoutTx(message.getSignedPayoutTxHex(), false, true);
        }
    }
}
