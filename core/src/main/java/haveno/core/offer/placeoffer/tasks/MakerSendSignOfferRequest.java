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

package haveno.core.offer.placeoffer.tasks;


import haveno.common.app.Version;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.availability.DisputeAgentSelection;
import haveno.core.offer.messages.SignOfferRequest;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendDirectMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MakerSendSignOfferRequest extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerSendSignOfferRequest.class);

    private boolean failed = false;

    @SuppressWarnings({"unused"})
    public MakerSendSignOfferRequest(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();
        try {
            runInterceptHook();

            // create request for arbitrator to sign offer
            String returnAddress = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString();
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

            // send request to least used arbitrators until success
            sendSignOfferRequests(request, () -> {
                complete();
            }, (errorMessage) -> {
                appendToErrorMessage("Error signing offer " + request.getOfferId() + ": " + errorMessage);
                failed(errorMessage);
            });
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }

    private void sendSignOfferRequests(SignOfferRequest request, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Arbitrator leastUsedArbitrator = DisputeAgentSelection.getLeastUsedArbitrator(model.getTradeStatisticsManager(), model.getArbitratorManager());
        if (leastUsedArbitrator == null) {
            errorMessageHandler.handleErrorMessage("Could not get least used arbitrator to send " + request.getClass().getSimpleName() + " for offer " + request.getOfferId());
            return;
        }
        sendSignOfferRequests(request, leastUsedArbitrator.getNodeAddress(), new HashSet<NodeAddress>(), resultHandler, errorMessageHandler);
    }

    private void sendSignOfferRequests(SignOfferRequest request, NodeAddress arbitratorNodeAddress, Set<NodeAddress> excludedArbitrators,  ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {

        // complete on successful ack message, fail on first nack
        DecryptedDirectMessageListener ackListener = new DecryptedDirectMessageListener() {
            @Override
            public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress sender) {
                if (!(decryptedMessageWithPubKey.getNetworkEnvelope() instanceof AckMessage)) return;
                if (!sender.equals(arbitratorNodeAddress)) return;
                AckMessage ackMessage = (AckMessage) decryptedMessageWithPubKey.getNetworkEnvelope();
                if (!ackMessage.getSourceMsgClassName().equals(SignOfferRequest.class.getSimpleName())) return;
                if (!ackMessage.getSourceUid().equals(request.getUid())) return;
                if (ackMessage.isSuccess()) {
                    model.getP2PService().removeDecryptedDirectMessageListener(this);
                    model.getOffer().getOfferPayload().setArbitratorSigner(arbitratorNodeAddress);
                    model.getOffer().setState(Offer.State.OFFER_FEE_RESERVED);
                    resultHandler.handleResult();
                 } else {
                     errorMessageHandler.handleErrorMessage("Arbitrator nacked SignOfferRequest for offer " + request.getOfferId() + ": " + ackMessage.getErrorMessage());
                 }
            }
        };
        model.getP2PService().addDecryptedDirectMessageListener(ackListener);

        // send sign offer request
        sendSignOfferRequest(request, arbitratorNodeAddress, new SendDirectMessageListener() {
            @Override
            public void onArrived() {
                log.info("{} arrived at arbitrator: offerId={}", request.getClass().getSimpleName(), model.getOffer().getId());
            }

            // if unavailable, try alternative arbitrator
            @Override
            public void onFault(String errorMessage) {
                log.warn("Arbitrator unavailable: {}", errorMessage);
                excludedArbitrators.add(arbitratorNodeAddress);
                Arbitrator altArbitrator = DisputeAgentSelection.getLeastUsedArbitrator(model.getTradeStatisticsManager(), model.getArbitratorManager(), excludedArbitrators);
                if (altArbitrator == null) {
                    errorMessageHandler.handleErrorMessage("Offer " + request.getOfferId() + " could not be signed by any arbitrator");
                    return;
                }
                log.info("Using alternative arbitrator {}", altArbitrator.getNodeAddress());
                sendSignOfferRequests(request, altArbitrator.getNodeAddress(), excludedArbitrators, resultHandler, errorMessageHandler);
            }
        });
    }

    private void sendSignOfferRequest(SignOfferRequest request, NodeAddress arbitratorNodeAddress, SendDirectMessageListener listener) {

        // get registered arbitrator
        Arbitrator arbitrator = model.getUser().getAcceptedArbitratorByAddress(arbitratorNodeAddress);
        if (arbitrator == null) throw new RuntimeException("Node address " + arbitratorNodeAddress + " is not a registered arbitrator"); // TODO: use error handler
        request.getOfferPayload().setArbitratorSigner(arbitratorNodeAddress);

        // send request to arbitrator
        log.info("Sending {} with offerId {} and uid {} to arbitrator {}", request.getClass().getSimpleName(), request.getOfferId(), request.getUid(), arbitratorNodeAddress);
        model.getP2PService().sendEncryptedDirectMessage(
                arbitratorNodeAddress,
                arbitrator.getPubKeyRing(),
                request,
                listener
        );
    }
}
