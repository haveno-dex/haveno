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

package bisq.core.trade.protocol.tasks;

import bisq.core.account.sign.SignedWitness;
import bisq.core.support.dispute.Dispute;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.BuyerTrade;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.util.Validator;
import common.utils.GenUtils;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

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

            // save message for reprocessing
            processModel.setPaymentReceivedMessage(message);
            trade.requestPersistence();

            // set state
            trade.getSeller().setUpdatedMultisigHex(message.getUpdatedMultisigHex());
            trade.getBuyer().setUpdatedMultisigHex(message.getPaymentSentMessage().getUpdatedMultisigHex());
            trade.getBuyer().setAccountAgeWitness(message.getBuyerAccountAgeWitness());

            // update to the latest peer address of our peer if message is correct
            trade.getSeller().setNodeAddress(processModel.getTempTradePeerNodeAddress());
            if (trade.getSeller().getNodeAddress().equals(trade.getBuyer().getNodeAddress())) trade.getBuyer().setNodeAddress(null); // tests can reuse addresses

            // close open disputes
            if (trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_REQUESTED.ordinal()) {
                trade.advanceDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                for (Dispute dispute : trade.getDisputes()) {
                    dispute.setIsClosed();
                }
            }

            // process payout tx unless already unlocked
            if (!trade.isPayoutUnlocked()) processPayoutTx(message);

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
                processModel.setPaymentReceivedMessage(null); // do not reprocess
                trade.requestPersistence();
            }

            failed(t);
        }
    }

    private void processPayoutTx(PaymentReceivedMessage message) {

        // sync and save wallet
        trade.syncWallet();
        trade.saveWallet();

        // import multisig hex
        List<String> updatedMultisigHexes = new ArrayList<String>();
        if (trade.getSeller().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getSeller().getUpdatedMultisigHex());
        if (trade.getArbitrator().getUpdatedMultisigHex() != null) updatedMultisigHexes.add(trade.getArbitrator().getUpdatedMultisigHex());
        if (!updatedMultisigHexes.isEmpty()) trade.getWallet().importMultisigHex(updatedMultisigHexes.toArray(new String[0])); // TODO (monero-project): fails if multisig hex imported individually

        // handle if payout tx not published
        if (!trade.isPayoutPublished()) {

            // wait to sign and publish payout tx if defer flag set (seller recently saw payout tx arrive at buyer)
            boolean isSigned = message.getSignedPayoutTxHex() != null;
            if (trade instanceof ArbitratorTrade && !isSigned && message.isDeferPublishPayout()) {
                log.info("Deferring signing and publishing payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                GenUtils.waitFor(Trade.DEFER_PUBLISH_MS);
                if (!trade.isPayoutUnlocked()) trade.syncWallet();
            }

            // verify and publish payout tx
            if (!trade.isPayoutPublished()) {
                if (isSigned) {
                    log.info("{} {} publishing signed payout tx from seller", trade.getClass().getSimpleName(), trade.getId());
                    trade.verifyPayoutTx(message.getSignedPayoutTxHex(), false, true);
                } else {
                    try {
                        if (StringUtils.equals(trade.getPayoutTxHex(), trade.getProcessModel().getPaymentSentMessage().getPayoutTxHex())) { // unsigned
                            log.info("{} {} verifying, signing, and publishing seller's payout tx", trade.getClass().getSimpleName(), trade.getId());
                            trade.verifyPayoutTx(message.getUnsignedPayoutTxHex(), true, true);
                        } else {
                            log.info("{} {} re-verifying and publishing payout tx", trade.getClass().getSimpleName(), trade.getId());
                            trade.verifyPayoutTx(trade.getPayoutTxHex(), false, true);
                        }
                    } catch (Exception e) {
                        if (trade.isPayoutPublished()) log.info("Payout tx already published for {} {}", trade.getClass().getName(), trade.getId());
                        else throw e;
                    }
                }
            }
        } else {
            log.info("Payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
            if (message.getSignedPayoutTxHex() != null && !trade.isPayoutConfirmed()) trade.verifyPayoutTx(message.getSignedPayoutTxHex(), false, true);
        }
    }
}
