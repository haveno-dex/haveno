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

package bisq.core.offer.placeoffer.tasks;

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.app.Version;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.offer.messages.SignOfferRequest;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MakerSendsSignOfferRequest extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerSendsSignOfferRequest.class);
    
    private boolean failed = false;

    @SuppressWarnings({"unused"})
    public MakerSendsSignOfferRequest(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();

        try {
            runInterceptHook();
            
            // create request for arbitrator to sign offer
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            SignOfferRequest request = new SignOfferRequest(
                    model.getOffer().getId(),
                    P2PService.getMyNodeAddress(),
                    model.getKeyRing().getPubKeyRing(),
                    model.getUser().getAccountId(),
                    offer.getOfferPayload(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    model.getReserveTx().getHash(),
                    model.getReserveTx().getFullHex(),
                    model.getReserveTx().getKey(),
                    offer.getOfferPayload().getReserveTxKeyImages(),
                    returnAddress);
            
            // get signing arbitrator
            Mediator arbitrator = checkNotNull(model.getUser().getAcceptedMediatorByAddress(offer.getOfferPayload().getArbitratorSigner()), "user.getAcceptedMediatorByAddress(mediatorNodeAddress) must not be null");
            
            // complete on successful ack message
            DecryptedDirectMessageListener ackListener = new DecryptedDirectMessageListener() {
                @Override
                public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress sender) {
                    if (!(decryptedMessageWithPubKey.getNetworkEnvelope() instanceof AckMessage)) return;
                    if (!sender.equals(arbitrator.getNodeAddress())) return;
                    AckMessage ackMessage = (AckMessage) decryptedMessageWithPubKey.getNetworkEnvelope();
                    if (!ackMessage.getSourceMsgClassName().equals(SignOfferRequest.class.getSimpleName())) return;
                    if (!ackMessage.getSourceUid().equals(request.getUid())) return;
                    if (ackMessage.isSuccess()) {
                        offer.setState(Offer.State.OFFER_FEE_RESERVED);
                        model.getP2PService().removeDecryptedDirectMessageListener(this);
                        complete();
                     } else {
                         if (!failed) {
                             failed = true;
                             failed(ackMessage.getErrorMessage()); // TODO: (woodser): only fail once? build into task?
                         }
                     }
                }
            };
            model.getP2PService().addDecryptedDirectMessageListener(ackListener);

            // send request
            model.getP2PService().sendEncryptedDirectMessage(arbitrator.getNodeAddress(), arbitrator.getPubKeyRing(), request, new SendDirectMessageListener() {
                @Override
                public void onArrived() {
                    log.info("{} arrived: arbitrator={}; offerId={}; uid={}", request.getClass().getSimpleName(), arbitrator.getNodeAddress(), offer.getId());
                }
                @Override
                public void onFault(String errorMessage) {
                    log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), arbitrator.getNodeAddress(), offer.getId(), errorMessage);
                    appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                    failed();
                }
              });
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
