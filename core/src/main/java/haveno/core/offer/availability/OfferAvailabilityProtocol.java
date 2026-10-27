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

package haveno.core.offer.availability;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.availability.tasks.ProcessOfferAvailabilityResponse;
import haveno.core.offer.availability.tasks.SendOfferAvailabilityRequest;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.core.offer.messages.OfferMessage;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.Validator;
import haveno.core.xmr.exceptions.WalletUnavailableException;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OfferAvailabilityProtocol {
    private static final long TIMEOUT = 90;
    private static final long WALLET_PREPARATION_TIMEOUT = 210; // allow the three-minute wallet sync timeout plus recovery

    private final OfferAvailabilityModel model;
    private final ResultHandler resultHandler;
    private final ErrorMessageHandler errorMessageHandler;
    private final DecryptedDirectMessageListener decryptedDirectMessageListener;

    private TaskRunner<OfferAvailabilityModel> taskRunner;
    private Timer timeoutTimer;
    private long timeoutGeneration;
    private volatile boolean canceled;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OfferAvailabilityProtocol(OfferAvailabilityModel model, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        this.model = model;
        this.resultHandler = resultHandler;
        this.errorMessageHandler = errorMessageHandler;

        decryptedDirectMessageListener = (decryptedMessageWithPubKey, peersNodeAddress) -> {
            NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
            if (networkEnvelope instanceof OfferMessage) {
                OfferMessage offerMessage = (OfferMessage) networkEnvelope;
                Validator.nonEmptyStringOf(offerMessage.offerId);
                if (networkEnvelope instanceof OfferAvailabilityResponse
                        && model.getOffer().getId().equals(offerMessage.offerId)) {
                    if (!model.getOffer().getPubKeyRing().getSignaturePubKey().equals(decryptedMessageWithPubKey.getSignaturePubKey())
                            || !model.getPeerNodeAddress().equals(peersNodeAddress)) {
                        log.warn("Ignoring OfferAvailabilityResponse from unexpected peer for offer {}", offerMessage.offerId);
                        return;
                    }
                    // leave the P2P listener lock before taking the protocol lock
                    UserThread.execute(() -> handleOfferAvailabilityResponse((OfferAvailabilityResponse) networkEnvelope, peersNodeAddress));
                }
            }
        };
    }

    private void cleanup() {
        stopTimeout();
        model.getP2PService().removeDecryptedDirectMessageListener(decryptedDirectMessageListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendOfferAvailabilityRequest() {
        synchronized (this) {
            if (canceled) return;
            model.getOffer().setState(Offer.State.UNKNOWN);
            startTimeout(WALLET_PREPARATION_TIMEOUT, true);
        }

        try {
            String payoutAddress = preparePayoutAddress();
            synchronized (this) {
                if (canceled) return;
                model.setPayoutAddress(payoutAddress);
                model.setPeerNodeAddress(model.getOffer().getMakerNodeAddress());
                model.getP2PService().addDecryptedDirectMessageListener(decryptedDirectMessageListener);

                taskRunner = new TaskRunner<>(model,
                        () -> handleTaskRunnerSuccess("TaskRunner at sendOfferAvailabilityRequest completed", null),
                        errorMessage -> handleTaskRunnerFault(errorMessage, null)
                );
                taskRunner.addTasks(SendOfferAvailabilityRequest.class);
                stopTimeout();
                startTimeout();
                taskRunner.run();
            }
        } catch (Exception e) {
            synchronized (this) {
                if (canceled) return;
                log.warn("Error preparing offer {} for availability check", model.getOffer().getId(), e);
                String errorMessage = e.getMessage() == null ? e.toString() : e.getMessage();
                model.getOffer().setErrorMessage("An error occurred.\nError message:\n" + errorMessage);
                handleTaskRunnerFault(errorMessage, null);
            }
        }
    }

    private String preparePayoutAddress() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WALLET_PREPARATION_TIMEOUT);
        Exception lastError = null;
        while (!canceled && System.nanoTime() < deadline) {
            try {
                return model.getXmrWalletService().getOrCreateAddressEntry(model.getOffer().getId(),
                        XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            } catch (Exception e) {
                if (!(e instanceof WalletUnavailableException) && !HavenoUtils.isUnresponsive(e)) throw e;
                if (lastError == null) log.warn("Waiting for main wallet before checking offer {}: {}", model.getOffer().getId(), e.getMessage());
                lastError = e;
            }
            if (!canceled) HavenoUtils.waitFor(1000); // let existing wallet recovery finish without holding wallet locks
        }
        if (canceled) return null;
        throw new WalletUnavailableException(Res.get("takeOffer.walletNotReady"), lastError);
    }

    public synchronized void cancel() {
        canceled = true;
        if (taskRunner != null) taskRunner.cancel();
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private synchronized void handleOfferAvailabilityResponse(OfferAvailabilityResponse message, NodeAddress peersNodeAddress) {
        if (canceled) return;
        log.info("Received OfferAvailabilityResponse from {} with offerId {} and uid {}",
                peersNodeAddress, message.getOfferId(), message.getUid());

        stopTimeout();
        startTimeout();
        model.setMessage(message);

        if (taskRunner != null) taskRunner.cancel(); // ignore late callbacks from the superseded send runner
        taskRunner = new TaskRunner<>(model,
                () -> {
                    handleTaskRunnerSuccess("TaskRunner at handle OfferAvailabilityResponse completed", message);

                    stopTimeout();
                    resultHandler.handleResult();
                },
                errorMessage -> handleTaskRunnerFault(errorMessage, message));
        taskRunner.addTasks(ProcessOfferAvailabilityResponse.class);
        taskRunner.run();
    }

    private void startTimeout() {
        startTimeout(TIMEOUT, false);
    }

    private void startTimeout(long timeout, boolean preparingWallet) {
        if (timeoutTimer == null) {
            long generation = ++timeoutGeneration;
            timeoutTimer = UserThread.runAfter(() -> {
                synchronized (this) {
                    if (canceled || generation != timeoutGeneration) return;
                    log.debug("Timeout reached at " + this);
                    cancel();
                    if (!preparingWallet) model.getOffer().setState(Offer.State.MAKER_OFFLINE);
                    errorMessageHandler.handleErrorMessage(preparingWallet ? Res.get("takeOffer.walletNotReady") : "Timeout reached: Peer has not responded.");
                }
            }, timeout);
        } else {
            log.warn("timeoutTimer already created. That must not happen.");
        }
    }

    private void stopTimeout() {
        timeoutGeneration++;
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    private void handleTaskRunnerSuccess(String info, @Nullable OfferAvailabilityResponse message) {
        log.debug("handleTaskRunnerSuccess " + info);

        if (message != null)
            sendAckMessage(message, true, null);
    }

    private synchronized void handleTaskRunnerFault(String errorMessage, @Nullable OfferAvailabilityResponse message) {
        if (canceled) return;
        log.error(errorMessage);

        cancel();
        errorMessageHandler.handleErrorMessage(errorMessage);

        if (message != null)
            sendAckMessage(message, false, errorMessage);
    }

    private void sendAckMessage(OfferAvailabilityResponse message, boolean result, @Nullable String errorMessage) {
        String offerId = message.getOfferId();
        String sourceUid = message.getUid();
        final NodeAddress makersNodeAddress = model.getPeerNodeAddress();
        PubKeyRing makersPubKeyRing = model.getOffer().getPubKeyRing();
        log.info("Send AckMessage for OfferAvailabilityResponse to peer {} with offerId {} and sourceUid {}",
                makersNodeAddress, offerId, sourceUid);

        AckMessage ackMessage = new AckMessage(model.getP2PService().getNetworkNode().getNodeAddress(),
                AckMessageSourceType.OFFER_MESSAGE,
                message.getClass().getSimpleName(),
                sourceUid,
                offerId,
                result,
                errorMessage);
        model.getP2PService().sendEncryptedDirectMessage(
                makersNodeAddress,
                makersPubKeyRing,
                ackMessage,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for OfferAvailabilityResponse arrived at makersNodeAddress {}. " +
                                        "offerId={}, sourceUid={}",
                                makersNodeAddress, offerId, ackMessage.getSourceUid());
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for OfferAvailabilityResponse failed. AckMessage={}, " +
                                        "makersNodeAddress={}, errorMessage={}",
                                ackMessage, makersNodeAddress, errorMessage);
                    }
                }
        );
    }
}
