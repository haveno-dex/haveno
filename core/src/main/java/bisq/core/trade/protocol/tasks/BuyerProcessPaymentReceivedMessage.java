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
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.util.Validator;

import bisq.common.taskrunner.TaskRunner;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;

@Slf4j
public class BuyerProcessPaymentReceivedMessage extends TradeTask {
    public BuyerProcessPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            PaymentReceivedMessage message = (PaymentReceivedMessage) processModel.getTradeMessage();
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);
            checkArgument(message.getPayoutTxHex() != null);

            // update to the latest peer address of our peer if the message is correct
            trade.getTradingPeer().setNodeAddress(processModel.getTempTradingPeerNodeAddress());

            // handle if payout tx is not seen on network
            if (trade.getPhase().ordinal() < Trade.Phase.PAYOUT_PUBLISHED.ordinal()) {

                // publish payout tx if signed. otherwise verify, sign, and publish payout tx
                boolean previouslySigned = trade.getPayoutTxHex() != null;
                if (previouslySigned) {
                    log.info("Buyer publishing signed payout tx from seller");
                    XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
                    MoneroWallet multisigWallet = walletService.getMultisigWallet(trade.getId());
                    List<String> txHashes = multisigWallet.submitMultisigTxHex(message.getPayoutTxHex());
                    trade.setPayoutTx(multisigWallet.getTx(txHashes.get(0)));
                    XmrWalletService.printTxs("payoutTx received from peer", trade.getPayoutTx());
                    trade.setStateIfValidTransitionTo(Trade.State.BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG);
                    walletService.closeMultisigWallet(trade.getId());
                } else {
                    log.info("Buyer verifying, signing, and publishing seller's payout tx");
                    trade.verifyPayoutTx(message.getPayoutTxHex(), true, true);
                    trade.setStateIfValidTransitionTo(Trade.State.BUYER_PUBLISHED_PAYOUT_TX);
                    // TODO (woodser): send PayoutTxPublishedMessage to seller
                }

                // mark address entries as available
                processModel.getXmrWalletService().resetAddressEntriesForPendingTrade(trade.getId());
            } else {
                log.info("We got the payout tx already set from BuyerSetupPayoutTxListener and do nothing here. trade ID={}", trade.getId());
            }

            // TODO: remove witness
            SignedWitness signedWitness = message.getSignedWitness();
            if (signedWitness != null) {
                // We received the signedWitness from the seller and publish the data to the network.
                // The signer has published it as well but we prefer to re-do it on our side as well to achieve higher
                // resilience.
                processModel.getAccountAgeWitnessService().publishOwnSignedWitness(signedWitness);
            }

            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
