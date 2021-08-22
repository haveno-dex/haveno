/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.offer.availability.tasks;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferUtil;
import bisq.core.offer.availability.OfferAvailabilityModel;
import bisq.core.offer.messages.OfferAvailabilityRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.user.User;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.UUID;
import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendOfferAvailabilityRequest extends Task<OfferAvailabilityModel> {
    public SendOfferAvailabilityRequest(TaskRunner<OfferAvailabilityModel> taskHandler, OfferAvailabilityModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // collect fields
            Offer offer = model.getOffer();
            User user = model.getUser();
            P2PService p2PService = model.getP2PService();
            XmrWalletService walletService = model.getXmrWalletService();
            OfferUtil offerUtil = model.getOfferUtil();
            String paymentAccountId = model.getPaymentAccountId();
            String paymentMethodId = user.getPaymentAccount(paymentAccountId).getPaymentAccountPayload().getPaymentMethodId();
            String payoutAddress = walletService.getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString(); // reserve new payout address
            
            // taker signs offer using offer id as nonce to avoid challenge protocol
            byte[] sig = Sig.sign(model.getP2PService().getKeyRing().getSignatureKeyPair().getPrivate(), offer.getId().getBytes(Charsets.UTF_8));
            
            // send InitTradeRequest to maker to sign
            InitTradeRequest tradeRequest = new InitTradeRequest(
                    offer.getId(),
                    P2PService.getMyNodeAddress(),
                    p2PService.getKeyRing().getPubKeyRing(),
                    offer.getAmount().value,
                    offer.getPrice().getValue(),
                    offerUtil.getTakerFee(true, offer.getAmount()).value,
                    user.getAccountId(),
                    paymentAccountId,
                    paymentMethodId,
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime(),
                    offer.getMakerNodeAddress(),
                    P2PService.getMyNodeAddress(),
                    null, // maker provides node address of arbitrator on response
                    null, // reserve tx not sent from taker to maker
                    null,
                    null,
                    payoutAddress,
                    null);
            
            // save trade request to later send to arbitrator
            model.setTradeRequest(tradeRequest);
            
            OfferAvailabilityRequest message = new OfferAvailabilityRequest(model.getOffer().getId(),
                    model.getPubKeyRing(), model.getTakersTradePrice(), model.isTakerApiUser(), tradeRequest);
            log.info("Send {} with offerId {} and uid {} to peer {}",
                    message.getClass().getSimpleName(), message.getOfferId(),
                    message.getUid(), model.getPeerNodeAddress());

            model.getP2PService().sendEncryptedDirectMessage(model.getPeerNodeAddress(),
                    model.getOffer().getPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}",
                                    message.getClass().getSimpleName(), message.getOfferId(), message.getUid());
                            complete();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}",
                                    message.getClass().getSimpleName(), message.getUid(),
                                    model.getPeerNodeAddress(), errorMessage);
                            model.getOffer().setState(Offer.State.MAKER_OFFLINE);
                        }
                    }
            );
        } catch (Throwable t) {
            model.getOffer().setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + t.getMessage());

            failed(t);
        }
    }
}

