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
import haveno.core.trade.HavenoUtils;
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
            trade.verifyDaemonConnection();

            // handle first time preparation
            if (trade.getArbitrator().getPaymentReceivedMessage() == null) {

                // synchronize on lock for wallet operations
                synchronized (trade.getWalletLock()) {
                    synchronized (HavenoUtils.getWalletFunctionLock()) {

                        // import multisig hex unless already signed
                        if (trade.getPayoutTxHex() == null) {
                            trade.importMultisigHex();
                        }

                        // verify, sign, and publish payout tx if given
                        if (trade.getBuyer().getPaymentSentMessage().getPayoutTxHex() != null && !trade.getProcessModel().isPaymentSentPayoutTxStale()) {
                            try {
                                if (trade.getPayoutTxHex() == null) {
                                    log.info("Seller verifying, signing, and publishing payout tx for trade {}", trade.getId());
                                    trade.processPayoutTx(trade.getBuyer().getPaymentSentMessage().getPayoutTxHex(), true, true);
                                } else {
                                    log.warn("Seller publishing previously signed payout tx for trade {}", trade.getId());
                                    trade.processPayoutTx(trade.getPayoutTxHex(), false, true);
                                }
                            } catch (IllegalArgumentException | IllegalStateException e) {
                                log.warn("Illegal state or argument verifying, signing, and publishing payout tx for {} {}. Creating new unsigned payout tx. error={}. ", trade.getClass().getSimpleName(), trade.getId(), e.getMessage(), e);
                                createUnsignedPayoutTx();
                            } catch (Exception e) {
                                log.warn("Error verifying, signing, and publishing payout tx for trade {}: {}", trade.getId(), e.getMessage(), e);
                                throw e;
                            }
                        }
                        
                        // otherwise create unsigned payout tx
                        else if (trade.getSelf().getUnsignedPayoutTxHex() == null) {
                            createUnsignedPayoutTx();
                        }
                    }
                }
            } else if (trade.getArbitrator().getPaymentReceivedMessage().getSignedPayoutTxHex() != null && !trade.isPayoutPublished()) {

                // republish payout tx from previous message
                log.info("Seller re-verifying and publishing signed payout tx for trade {}", trade.getId());
                trade.processPayoutTx(trade.getArbitrator().getPaymentReceivedMessage().getSignedPayoutTxHex(), false, true);
            }

            // close open disputes
            if (trade.isPayoutPublished() && trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_REQUESTED.ordinal()) {
                trade.advanceDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                for (Dispute dispute : trade.getDisputes()) dispute.setIsClosed();
            }

            trade.requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void createUnsignedPayoutTx() {
        log.info("Seller creating unsigned payout tx for trade {}", trade.getId());
        MoneroTxWallet payoutTx = trade.createPayoutTx();
        trade.updatePayout(payoutTx);
        trade.getSelf().setUnsignedPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
    }
}
