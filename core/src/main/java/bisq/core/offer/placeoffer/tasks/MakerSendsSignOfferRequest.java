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


import bisq.common.app.Version;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.offer.availability.DisputeAgentSelection;
import bisq.core.offer.messages.SignOfferRequest;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.support.dispute.arbitration.arbitrator.Arbitrator;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
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
            
            // send request to least used arbitrators until success
            sendSignOfferRequests(request, () -> {
                complete();
            }, (errorMessage) -> {
                log.warn("Cannot sign offer with any arbitrator: " + errorMessage);
                appendToErrorMessage("Cannot sign offer with any arbitrator: " + request + "\nerrorMessage=" + errorMessage);
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
        sendSignOfferRequests(request, leastUsedArbitrator.getNodeAddress(), new HashSet<NodeAddress>(), resultHandler, errorMessageHandler);
    }

    private void sendSignOfferRequests(SignOfferRequest request, NodeAddress arbitratorNodeAddress, Set<NodeAddress> excludedArbitrators,  ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        
        // complete on successful ack message
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
                     log.warn("Arbitrator nacked request: {}", errorMessage);
                     handleArbitratorFailure(request, arbitratorNodeAddress, excludedArbitrators, resultHandler, errorMessageHandler);
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
                handleArbitratorFailure(request, arbitratorNodeAddress, excludedArbitrators, resultHandler, errorMessageHandler);
            }
        });
    }

    private void sendSignOfferRequest(SignOfferRequest request, NodeAddress arbitratorNodeAddress, SendDirectMessageListener listener) {

        // get registered arbitrator
        Arbitrator arbitrator = model.getUser().getAcceptedArbitratorByAddress(arbitratorNodeAddress);
        if (arbitrator == null) throw new RuntimeException("Node address " + arbitratorNodeAddress + " is not a registered arbitrator");
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

    private void handleArbitratorFailure(SignOfferRequest request, NodeAddress arbitratorNodeAddress, Set<NodeAddress> excludedArbitrators,  ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        excludedArbitrators.add(arbitratorNodeAddress);
        Arbitrator altArbitrator = DisputeAgentSelection.getLeastUsedArbitrator(model.getTradeStatisticsManager(), model.getArbitratorManager(), excludedArbitrators);
        if (altArbitrator == null) {
            errorMessageHandler.handleErrorMessage("Cannot sign offer because no arbitrators are available");
            return;
        }
        log.info("Using alternative arbitrator {}", altArbitrator.getNodeAddress());
        sendSignOfferRequests(request, altArbitrator.getNodeAddress(), excludedArbitrators, resultHandler, errorMessageHandler);
    }
}
