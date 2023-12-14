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

import haveno.common.taskrunner.TaskRunner;
import haveno.core.support.dispute.Dispute;
import haveno.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class SellerPreparePaymentReceivedMessage extends TradeTask {

    @SuppressWarnings({"unused"})
    public SellerPreparePaymentReceivedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // check connection
            trade.checkDaemonConnection();

            // handle first time preparation
            if (trade.getArbitrator().getPaymentReceivedMessage() == null) {

                // import multisig hex
                trade.importMultisigHex();

                // verify, sign, and publish payout tx if given. otherwise create payout tx
                if (trade.getPayoutTxHex() != null) {
                    try {
                        log.info("Seller verifying, signing, and publishing payout tx for trade {}", trade.getId());
                        trade.processPayoutTx(trade.getPayoutTxHex(), true, true);
                    } catch (Exception e) {
                        log.warn("Error verifying, signing, and publishing payout tx for trade {}: {}. Creating unsigned payout tx", trade.getId(), e.getMessage());
                        createUnsignedPayoutTx();
                    }
                } else {
                    createUnsignedPayoutTx();
                }
            } else if (trade.getArbitrator().getPaymentReceivedMessage().getSignedPayoutTxHex() != null && !trade.isPayoutPublished()) {

                // republish payout tx from previous message
                log.info("Seller re-verifying and publishing payout tx for trade {}", trade.getId());
                trade.processPayoutTx(trade.getArbitrator().getPaymentReceivedMessage().getSignedPayoutTxHex(), false, true);
            }

            // close open disputes
            if (trade.isPayoutPublished() && trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_REQUESTED.ordinal()) {
                trade.advanceDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                for (Dispute dispute : trade.getDisputes()) dispute.setIsClosed();
            }

            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void createUnsignedPayoutTx() {
        log.info("Seller creating unsigned payout tx for trade {}", trade.getId());
        MoneroTxWallet payoutTx = trade.createPayoutTx();
        trade.setPayoutTx(payoutTx);
        trade.setPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
    }
}
