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

package haveno.core.offer.placeoffer;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.locale.Res;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.offer.placeoffer.tasks.AddToOfferBook;
import haveno.core.offer.placeoffer.tasks.MakerProcessSignOfferResponse;
import haveno.core.offer.placeoffer.tasks.MakerReserveOfferFunds;
import haveno.core.offer.placeoffer.tasks.MakerSendSignOfferRequest;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.trade.handlers.TransactionResultHandler;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.network.p2p.NodeAddress;
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
      log.debug("handleSignOfferResponse() " + model.getOpenOffer().getOffer().getId());
      model.setSignOfferResponse(response);

      if (!model.getOpenOffer().getOffer().getOfferPayload().getArbitratorSigner().equals(sender)) {
          log.warn("Ignoring sign offer response from different sender");
          return;
      }

      // ignore if timer already stopped
      if (timeoutTimer == null) {
          log.warn("Ignoring sign offer response from arbitrator because timeout has expired for offer " + model.getOpenOffer().getOffer().getId());
          return;
      }

      // reset timer
      stopTimeoutTimer();
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
            model.getOpenOffer().getOffer().setErrorMessage(errorMessage);
            errorMessageHandler.handleErrorMessage(errorMessage);
        }
    }
}
