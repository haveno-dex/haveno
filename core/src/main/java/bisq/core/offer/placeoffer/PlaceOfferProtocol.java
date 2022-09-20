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

package bisq.core.offer.placeoffer;

import bisq.core.locale.Res;
import bisq.core.offer.messages.SignOfferResponse;
import bisq.core.offer.placeoffer.tasks.AddToOfferBook;
import bisq.core.offer.placeoffer.tasks.MakerReserveOfferFunds;
import bisq.core.offer.placeoffer.tasks.MakerSendSignOfferRequest;
import bisq.core.offer.placeoffer.tasks.MakerProcessSignOfferResponse;
import bisq.core.offer.placeoffer.tasks.ValidateOffer;
import bisq.core.trade.handlers.TransactionResultHandler;
import bisq.core.trade.protocol.TradeProtocol;
import bisq.network.p2p.NodeAddress;
import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.taskrunner.TaskRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaceOfferProtocol {
    private static final Logger log = LoggerFactory.getLogger(PlaceOfferProtocol.class);

    private final PlaceOfferModel model;
    private Timer timeoutTimer;
    private final TransactionResultHandler resultHandler;
    private final ErrorMessageHandler errorMessageHandler;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PlaceOfferProtocol(PlaceOfferModel model,
                              TransactionResultHandler resultHandler,
                              ErrorMessageHandler errorMessageHandler) {
        this.model = model;
        this.resultHandler = resultHandler;
        this.errorMessageHandler = errorMessageHandler;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void placeOffer() {
        log.debug("placeOffer() " + model.getOffer().getId());

        timeoutTimer = UserThread.runAfter(() -> {
            handleError(Res.get("createOffer.timeoutAtPublishing"));
        }, TradeProtocol.TRADE_TIMEOUT);

        TaskRunner<PlaceOfferModel> taskRunner = new TaskRunner<>(model,
                () -> {
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
    
    // TODO (woodser): switch to fluent
    public void handleSignOfferResponse(SignOfferResponse response, NodeAddress sender) {
      log.debug("handleSignOfferResponse() " + model.getOffer().getId());
      model.setSignOfferResponse(response);

      if (!model.getOffer().getOfferPayload().getArbitratorSigner().equals(sender)) {
          log.warn("Ignoring sign offer response from different sender");
          return;
      }

      // ignore if timer already stopped
      if (timeoutTimer == null) {
          log.warn("Ignoring sign offer response from arbitrator because timeout has expired");
          return;
      }

      timeoutTimer = UserThread.runAfter(() -> {
          handleError(Res.get("createOffer.timeoutAtPublishing"));
      }, TradeProtocol.TRADE_TIMEOUT);

      TaskRunner<PlaceOfferModel> taskRunner = new TaskRunner<>(model,
              () -> {
                  log.debug("sequence at handleSignOfferResponse completed");
                  stopTimeoutTimer();
                  resultHandler.handleResult(model.getTransaction()); // TODO (woodser): XMR transaction instead
              },
              (errorMessage) -> {
                  if (model.isOfferAddedToOfferBook()) {
                      model.getOfferBookService().removeOffer(model.getOffer().getOfferPayload(),
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
              AddToOfferBook.class
      );

      taskRunner.run();
    }

    private void stopTimeoutTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    private void handleError(String errorMessage) {
        if (timeoutTimer != null) {
            log.error(errorMessage);
            stopTimeoutTimer();
            model.getOffer().setErrorMessage(errorMessage);
            errorMessageHandler.handleErrorMessage(errorMessage);
        }
    }
}
