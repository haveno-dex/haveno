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
import haveno.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerPublishDepositTx extends TradeTask {
    public SellerPublishDepositTx(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("SellerPublishesDepositTx not implemented for xmr");

//            final Transaction depositTx = processModel.getDepositTx();
//            processModel.getTradeWalletService().broadcastTx(depositTx,
//                    new TxBroadcaster.Callback() {
//                        @Override
//                        public void onSuccess(Transaction transaction) {
//                            if (!completed) {
//                                // Now as we have published the deposit tx we set it in trade
//                                trade.applyDepositTx(depositTx);
//
//                                trade.setState(Trade.State.SELLER_PUBLISHED_DEPOSIT_TX);
//
//                                processModel.getBtcWalletService().swapTradeEntryToAvailableEntry(processModel.getOffer().getId(),
//                                        AddressEntry.Context.RESERVED_FOR_TRADE);
//
//                                processModel.getTradeManager().requestPersistence();
//
//                                complete();
//                            } else {
//                                log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
//                            }
//                        }
//
//                        @Override
//                        public void onFailure(TxBroadcastException exception) {
//                            if (!completed) {
//                                failed(exception);
//                            } else {
//                                log.warn("We got the onFailure callback called after the timeout has been triggered a complete().");
//                            }
//                        }
//                    });
        } catch (Throwable t) {
            failed(t);
        }
    }
}
