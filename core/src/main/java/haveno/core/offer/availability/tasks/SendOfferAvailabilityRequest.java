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

package haveno.core.offer.availability.tasks;

import haveno.common.app.Version;
import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.offer.messages.OfferAvailabilityRequest;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.user.User;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.UUID;

// TODO (woodser): rename to TakerSendOfferAvailabilityRequest and group with other taker tasks
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
            String paymentAccountId = model.getPaymentAccountId();
            String paymentMethodId = user.getPaymentAccount(paymentAccountId).getPaymentAccountPayload().getPaymentMethodId();
            String payoutAddress = walletService.getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();

            // taker signs offer using offer id as nonce to avoid challenge protocol
            byte[] sig = HavenoUtils.sign(model.getP2PService().getKeyRing(), offer.getId());

            // get price
            Price price = offer.getPrice();
            if (price == null) throw new RuntimeException("Could not get price for offer");

            // send InitTradeRequest to maker to sign
            InitTradeRequest tradeRequest = new InitTradeRequest(
                    offer.getId(),
                    P2PService.getMyNodeAddress(),
                    p2PService.getKeyRing().getPubKeyRing(),
                    offer.getAmount().longValueExact(),
                    price.getValue(),
                    HavenoUtils.getTakerFee(offer.getAmount()).longValueExact(),
                    user.getAccountId(),
                    paymentAccountId,
                    paymentMethodId,
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime(),
                    offer.getMakerNodeAddress(),
                    P2PService.getMyNodeAddress(),
                    null, // maker provides node address of backup arbitrator on response
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

