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

package bisq.core.trade.protocol.tasks.maker;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;

import com.google.common.base.Charsets;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.util.Validator.checkTradeId;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class MakerSendsInitTradeRequestIfUnreserved extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerSendsInitTradeRequestIfUnreserved(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if arbitrator is signer and therefore already has reserve tx
            Offer offer = processModel.getOffer();
            if (offer.getOfferPayload().getArbitratorSigner().equals(trade.getArbitratorNodeAddress())) {
                complete();
                return;
            }

            // verify trade
            InitTradeRequest makerRequest = (InitTradeRequest) processModel.getTradeMessage(); // arbitrator's InitTradeRequest to maker
            checkNotNull(makerRequest);
            checkTradeId(processModel.getOfferId(), makerRequest);
            
            // maker signs offer id as nonce to avoid challenge protocol // TODO (woodser): is this necessary?
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), offer.getId().getBytes(Charsets.UTF_8));
            
            // create request to arbitrator
            InitTradeRequest arbitratorRequest = new InitTradeRequest(
                    offer.getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    offer.getAmount().value,
                    offer.getPrice().getValue(),
                    offer.getMakerFee().value,
                    trade.getProcessModel().getAccountId(),
                    offer.getMakerPaymentAccountId(),
                    offer.getOfferPayload().getPaymentMethodId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    makerRequest.getCurrentDate(),
                    trade.getMakerNodeAddress(),
                    trade.getTakerNodeAddress(),
                    trade.getArbitratorNodeAddress(),
                    trade.getSelf().getReserveTxHash(),
                    trade.getSelf().getReserveTxHex(),
                    trade.getSelf().getReserveTxKey(),
                    model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString(),
                    null);
            
            // send request to arbitrator
            log.info("Sending {} with offerId {} and uid {} to arbitrator {} with pub key ring {}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid(), trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitratorNodeAddress(),
                    trade.getArbitratorPubKeyRing(),
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
