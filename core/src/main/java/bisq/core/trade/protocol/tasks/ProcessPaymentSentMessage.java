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

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.util.Validator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessPaymentSentMessage extends TradeTask {
    public ProcessPaymentSentMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            PaymentSentMessage message = (PaymentSentMessage) processModel.getTradeMessage();
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);

            // verify signature of payment sent message
            HavenoUtils.verifyPaymentSentMessage(trade, message);

            // update buyer info
            trade.setPayoutTxHex(message.getPayoutTxHex());
            trade.getBuyer().setUpdatedMultisigHex(message.getUpdatedMultisigHex());
            trade.getBuyer().setPaymentSentMessage(message);

            // if seller, decrypt buyer's payment account payload
            if (trade.isSeller()) trade.decryptPeerPaymentAccountPayload(message.getPaymentAccountKey());

            // update latest peer address
            trade.getBuyer().setNodeAddress(processModel.getTempTradingPeerNodeAddress());

            // set state
            String counterCurrencyTxId = message.getCounterCurrencyTxId();
            if (counterCurrencyTxId != null && counterCurrencyTxId.length() < 100) trade.setCounterCurrencyTxId(counterCurrencyTxId);
            String counterCurrencyExtraData = message.getCounterCurrencyExtraData();
            if (counterCurrencyExtraData != null && counterCurrencyExtraData.length() < 100) trade.setCounterCurrencyExtraData(counterCurrencyExtraData);
            trade.setStateIfProgress(trade.isSeller() ? Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG : Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
