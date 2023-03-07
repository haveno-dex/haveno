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

package haveno.core.trade.protocol.tasks.mediation;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.tasks.TradeTask;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessMediatedPayoutTxPublishedMessage extends TradeTask {
    public ProcessMediatedPayoutTxPublishedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("ProcessMediatedPayoutTxPublishedMessage not implemented for xmr");
//            MediatedPayoutTxPublishedMessage message = (MediatedPayoutTxPublishedMessage) processModel.getTradeMessage();
//            Validator.checkTradeId(processModel.getOfferId(), message);
//            checkNotNull(message);
//            checkArgument(message.getPayoutTx() != null);
//
//            // update to the latest peer address of our peer if the message is correct
//            trade.getTradePeer().setNodeAddress(processModel.getTempTradePeerNodeAddress());
//
//            if (trade.getPayoutTx() == null) {
//                Transaction committedMediatedPayoutTx = WalletService.maybeAddNetworkTxToWallet(message.getPayoutTx(), processModel.getBtcWalletService().getWallet());
//                trade.setPayoutTx(committedMediatedPayoutTx);
//                log.info("MediatedPayoutTx received from peer.  Txid: {}\nhex: {}",
//                        committedMediatedPayoutTx.getTxId().toString(), Utils.HEX.encode(committedMediatedPayoutTx.bitcoinSerialize()));
//
//                trade.setMediationResultState(MediationResultState.RECEIVED_PAYOUT_TX_PUBLISHED_MSG);
//
//                if (trade.getPayoutTx() != null) {
//                    // We need to delay that call as we might get executed at startup after mailbox messages are
//                    // applied where we iterate over out pending trades. The closeDisputedTrade method would remove
//                    // that trade from the list causing a ConcurrentModificationException.
//                    // To avoid that we delay for one render frame.
//                    UserThread.execute(() -> processModel.getTradeManager()
//                            .closeDisputedTrade(trade.getId(), Trade.DisputeState.MEDIATION_CLOSED));
//                }
//
//                processModel.getBtcWalletService().resetCoinLockedInMultiSigAddressEntry(trade.getId());
//            } else {
//                log.info("We got the payout tx already set from BuyerSetupPayoutTxListener and do nothing here. trade ID={}", trade.getId());
//            }
//
//            processModel.getTradeManager().requestPersistence();
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
