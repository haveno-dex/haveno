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

package haveno.core.offer.placeoffer;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.locale.Res;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.offer.placeoffer.tasks.MaybeAddToOfferBook;
import haveno.core.offer.placeoffer.tasks.MakerProcessSignOfferResponse;
import haveno.core.offer.placeoffer.tasks.MakerReserveOfferFunds;
import haveno.core.offer.placeoffer.tasks.MakerSendSignOfferRequest;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.trade.handlers.TransactionResultHandler;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.network.p2p.NodeAddress;

import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaceOfferProtocol {
    private static final Logger log = LoggerFactory.getLogger(PlaceOfferProtocol.class);

    private final PlaceOfferModel model;
    private Timer timeoutTimer;
    private int timeoutSeq; // identifies the current timer so one reset after firing is ignored
    private TransactionResultHandler resultHandler;
    private ErrorMessageHandler errorMessageHandler;
    private TaskRunner<PlaceOfferModel> taskRunner;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PlaceOfferProtocol(PlaceOfferModel model,
                              TransactionResultHandler resultHandler,
                              ErrorMessageHandler errorMessageHandler) {
        this.model = model;
        this.model.setProtocol(this);
        this.resultHandler = resultHandler;
        this.errorMessageHandler = errorMessageHandler;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void placeOffer() {

        startTimeoutTimer();

        taskRunner = new TaskRunner<>(model,
                () -> {

                    // reset timer if response not yet received
                    if (model.getSignOfferResponse() == null) startTimeoutTimer();
                },
                (errorMessage) -> {
                    handleError(errorMessage);
                }
        );
        taskRunner.addTasks(
                ValidateOffer.class,
                MakerReserveOfferFunds.class,
                MakerSendSignOfferRequest.class
        );

        taskRunner.run();
    }

    public void cancelOffer() {
        handleError("Offer was canceled: " + model.getOpenOffer().getOffer().getId()); // cancel is treated as error for callers to handle
    }
    
    public void handleSignOfferResponse(SignOfferResponse response, NodeAddress sender) {
        log.debug("handleSignOfferResponse() " + model.getOpenOffer().getOffer().getId());
        model.setSignOfferResponse(response);

        // ignore if unexpected signer
        if (!model.getOpenOffer().getOffer().getOfferPayload().getArbitratorSigner().equals(sender)) {
            log.warn("Ignoring sign offer response from different sender");
            return;
        }

        // ignore if payloads have different timestamps
        if (model.getOpenOffer().getOffer().getOfferPayload().getDate() != response.getSignedOfferPayload().getDate()) {
            log.warn("Ignoring sign offer response from arbitrator for offer payload with different timestamp");
            return;
        }

        // ignore the response if the protocol already completed or timed out, else reset timer
        synchronized (this) {
            if (timeoutTimer == null) {
                log.warn("Ignoring sign offer response from arbitrator because timeout has expired for offer " + model.getOpenOffer().getOffer().getId());
                return;
            }
            startTimeoutTimer();
        }

        TaskRunner<PlaceOfferModel> taskRunner = new TaskRunner<>(model,
                () -> {
                    log.debug("sequence at handleSignOfferResponse completed");
                    stopTimeoutTimer();
                    handleResult(model.getTransaction()); // TODO: use XMR transaction instead
                },
                (errorMessage) -> {
                    if (model.isOfferAddedToOfferBook()) {
                        model.getOfferBookService().removeOffer(model.getOpenOffer().getOffer().getOfferPayload(),
                                () -> {
                                    model.setOfferAddedToOfferBook(false);
                                    log.debug("OfferPayload removed from offer book.");
                                },
                                log::error);
                    }
                    handleError(errorMessage);
                }
        );
        taskRunner.addTasks(
                MakerProcessSignOfferResponse.class,
                MaybeAddToOfferBook.class
        );

        taskRunner.run();
    }

    public synchronized void startTimeoutTimer() {
        if (resultHandler == null) return;
        stopTimeoutTimer();
        int seq = ++timeoutSeq;
        timeoutTimer = UserThread.runAfter(() -> {
            ThreadUtils.submitToPool(() -> handleError(Res.get("createOffer.timeoutAtPublishing"), seq)); // off the user thread since cancel handlers may block on the wallet lock
        }, TradeProtocol.TRADE_STEP_TIMEOUT_SECONDS);
    }

    private synchronized void stopTimeoutTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    // handlers are invoked outside the monitor, which tasks acquire to reset the timeout while holding the wallet lock
    private void handleResult(Transaction transaction) {
        TransactionResultHandler handler;
        synchronized (this) {
            handler = resultHandler;
            resetHandlers();
        }
        if (handler != null) handler.handleResult(transaction);
    }

    private void handleError(String errorMessage) {
        handleError(errorMessage, null);
    }

    private void handleError(String errorMessage, Integer expectedTimeoutSeq) {
        ErrorMessageHandler handler;
        synchronized (this) {
            if (expectedTimeoutSeq != null && (timeoutTimer == null || expectedTimeoutSeq != timeoutSeq)) return; // timer was stopped or reset after firing
            handler = errorMessageHandler;
            resetHandlers();
            if (handler == null) return;
            if (taskRunner != null) taskRunner.cancel();
            if (!model.getOpenOffer().isCanceled()) {
                model.getOpenOffer().getOffer().setErrorMessage(errorMessage);
            }
            stopTimeoutTimer();
        }
        handler.handleErrorMessage(errorMessage);
    }

    private synchronized void resetHandlers() {
        resultHandler = null;
        errorMessageHandler = null;
    }
}
