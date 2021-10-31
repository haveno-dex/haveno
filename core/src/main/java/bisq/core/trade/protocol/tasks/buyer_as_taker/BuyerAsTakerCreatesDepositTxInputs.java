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

package bisq.core.trade.protocol.tasks.buyer_as_taker;

import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsTakerCreatesDepositTxInputs extends TradeTask {

    public BuyerAsTakerCreatesDepositTxInputs(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            throw new RuntimeException("Outputs not communicated in xmr integration");

//            processModel.getTradeManager().requestPersistence();

//            Coin txFee = trade.getTxFee();
//            Coin takerInputAmount = checkNotNull(trade.getOffer()).getBuyerSecurityDeposit()
//                    .add(txFee)
//                    .add(txFee); // 2 times the fee as we need it for payout tx as well
//            InputsAndChangeOutput result = processModel.getTradeWalletService().takerCreatesDepositTxInputs(
//                    processModel.getTakeOfferFeeTx(),
//                    takerInputAmount,
//                    txFee);
//            processModel.setRawTransactionInputs(result.rawTransactionInputs);
//            processModel.setChangeOutputValue(result.changeOutputValue);
//            processModel.setChangeOutputAddress(result.changeOutputAddress);
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
