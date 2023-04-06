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

import haveno.common.app.Version;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.util.Validator.checkTradeId;

@Slf4j
public class MakerSendInitTradeRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerSendInitTradeRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // verify trade
            InitTradeRequest makerRequest = (InitTradeRequest) processModel.getTradeMessage(); // arbitrator's InitTradeRequest to maker
            checkNotNull(makerRequest);
            checkTradeId(processModel.getOfferId(), makerRequest);

            // create request to arbitrator
            Offer offer = processModel.getOffer();
            InitTradeRequest arbitratorRequest = new InitTradeRequest(
                    offer.getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    offer.getAmount().longValueExact(),
                    trade.getPrice().getValue(),
                    offer.getMakerFee().longValueExact(),
                    trade.getProcessModel().getAccountId(),
                    offer.getMakerPaymentAccountId(),
                    offer.getOfferPayload().getPaymentMethodId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    null,
                    makerRequest.getCurrentDate(),
                    trade.getMaker().getNodeAddress(),
                    trade.getTaker().getNodeAddress(),
                    trade.getArbitrator().getNodeAddress(),
                    trade.getSelf().getReserveTxHash(),
                    trade.getSelf().getReserveTxHex(),
                    trade.getSelf().getReserveTxKey(),
                    model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString(),
                    null);

            // send request to arbitrator
            log.info("Sending {} with offerId {} and uid {} to arbitrator {}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid(), trade.getArbitrator().getNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitrator().getNodeAddress(),
                    trade.getArbitrator().getPubKeyRing(),
                    arbitratorRequest,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}", InitTradeRequest.class.getSimpleName(), trade.getId());
                            complete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.warn("Failed to send {} to arbitrator, error={}.", InitTradeRequest.class.getSimpleName(), errorMessage);
                            failed();
                        }
                    });
        } catch (Throwable t) {
            failed(t);
        }
    }
}
