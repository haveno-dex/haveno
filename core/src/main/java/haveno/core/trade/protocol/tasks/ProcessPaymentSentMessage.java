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
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.util.Validator;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

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
            checkNotNull(message);
            Validator.checkTradeId(processModel.getOfferId(), message);

            // verify signature of payment sent message
            HavenoUtils.verifyPaymentSentMessage(trade, message);

            // update latest peer address
            trade.getBuyer().setNodeAddress(processModel.getTempTradePeerNodeAddress());

            // update state from message
            processModel.setPaymentSentMessage(message);
            trade.setPayoutTxHex(message.getPayoutTxHex());
            trade.getBuyer().setUpdatedMultisigHex(message.getUpdatedMultisigHex());
            trade.getSeller().setAccountAgeWitness(message.getSellerAccountAgeWitness());
            String counterCurrencyTxId = message.getCounterCurrencyTxId();
            if (counterCurrencyTxId != null && counterCurrencyTxId.length() < 100) trade.setCounterCurrencyTxId(counterCurrencyTxId);
            String counterCurrencyExtraData = message.getCounterCurrencyExtraData();
            if (counterCurrencyExtraData != null && counterCurrencyExtraData.length() < 100) trade.setCounterCurrencyExtraData(counterCurrencyExtraData);

            // if seller, decrypt buyer's payment account payload
            if (trade.isSeller()) trade.decryptPeerPaymentAccountPayload(message.getPaymentAccountKey());
            trade.requestPersistence();
            
            // import multisig hex
            try {
                trade.importMultisigHex();
            } catch (Exception e) {
                log.warn("Error importing multisig hex for {} {}: {}", trade.getClass().getSimpleName(), trade.getId(), e.getMessage());
                e.printStackTrace();
            }

            // update state
            trade.advanceState(trade.isSeller() ? Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG : Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
            trade.requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
