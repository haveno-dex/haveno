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

import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.offer.placeoffer.tasks.AddToOfferBook;
import haveno.core.offer.placeoffer.tasks.MakerReservesTradeFunds;
import haveno.core.offer.placeoffer.tasks.MakerSendsSignOfferRequest;
import haveno.core.offer.placeoffer.tasks.MakerProcessesSignOfferResponse;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.trade.handlers.TransactionResultHandler;
import haveno.network.p2p.NodeAddress;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.taskrunner.TaskRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaceOfferProtocol {
    private static final Logger log = LoggerFactory.getLogger(PlaceOfferProtocol.class);

    private final PlaceOfferModel model;
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
        TaskRunner<PlaceOfferModel> taskRunner = new TaskRunner<>(model,
                () -> {
                    log.debug("sequence at placeOffer completed");
                },
                (errorMessage) -> {
                    log.error(errorMessage);
                    model.getOffer().setErrorMessage(errorMessage);
                    errorMessageHandler.handleErrorMessage(errorMessage);
                }
        );
        taskRunner.addTasks(
                ValidateOffer.class,
                MakerReservesTradeFunds.class,
                MakerSendsSignOfferRequest.class
        );

        taskRunner.run();
    }
    
    // TODO (woodser): switch to fluent
    public void handleSignOfferResponse(SignOfferResponse response, NodeAddress sender) {
      log.debug("handleSignOfferResponse() " + model.getOffer().getId());
      model.setSignOfferResponse(response);
      
      if (!model.getOffer().getOfferPayload().getArbitratorNodeAddress().equals(sender)) {
          log.warn("Ignoring sign offer response from different sender");
          return;
      }
      
      TaskRunner<PlaceOfferModel> taskRunner = new TaskRunner<>(model,
              () -> {
                  log.debug("sequence at handleSignOfferResponse completed");
                  resultHandler.handleResult(model.getTransaction()); // TODO (woodser): XMR transaction instead
              },
              (errorMessage) -> {
                  log.error(errorMessage);
                  if (model.isOfferAddedToOfferBook()) {
                      model.getOfferBookService().removeOffer(model.getOffer().getOfferPayload(),
                              () -> {
                                  model.setOfferAddedToOfferBook(false);
                                  log.debug("OfferPayload removed from offer book.");
                              },
                              log::error);
                  }
                  model.getOffer().setErrorMessage(errorMessage);
                  errorMessageHandler.handleErrorMessage(errorMessage);
              }
      );
      taskRunner.addTasks(
              MakerProcessesSignOfferResponse.class,
              AddToOfferBook.class
      );

      taskRunner.run();
    }
}
