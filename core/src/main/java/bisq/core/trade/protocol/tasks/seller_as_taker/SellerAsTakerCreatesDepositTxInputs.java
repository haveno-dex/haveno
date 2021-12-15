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

package bisq.core.trade.protocol.tasks.seller_as_taker;

import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerAsTakerCreatesDepositTxInputs extends TradeTask {
    public SellerAsTakerCreatesDepositTxInputs(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("XMR integration does not communicate outputs");

//            Coin tradeAmount = checkNotNull(trade.getTradeAmount());
//            Offer offer = checkNotNull(trade.getOffer());
//            Coin txFee = trade.getTxFee();
//            Coin takerInputAmount = offer.getSellerSecurityDeposit()
//                    .add(txFee)
//                    .add(txFee) // We add 2 times the fee as one is for the payout tx
//                    .add(tradeAmount);
//            InputsAndChangeOutput result = processModel.getTradeWalletService().takerCreatesDepositTxInputs(
//                    processModel.getTakeOfferFeeTx(),
//                    takerInputAmount,
//                    txFee);
//
//            processModel.setRawTransactionInputs(result.rawTransactionInputs);
//            processModel.setChangeOutputValue(result.changeOutputValue);
//            processModel.setChangeOutputAddress(result.changeOutputAddress);
//
//            processModel.getTradeManager().requestPersistence();
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
