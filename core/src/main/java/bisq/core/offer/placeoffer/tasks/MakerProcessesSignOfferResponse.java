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

package bisq.core.offer.placeoffer.tasks;

import bisq.core.offer.Offer;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.TradeUtils;

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;

public class MakerProcessesSignOfferResponse extends Task<PlaceOfferModel> {
    public MakerProcessesSignOfferResponse(TaskRunner<PlaceOfferModel> taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();
        try {
            runInterceptHook();
            
            // validate arbitrator signature
            Mediator arbitrator = checkNotNull(model.getUser().getAcceptedMediatorByAddress(offer.getOfferPayload().getArbitratorNodeAddress()), "user.getAcceptedMediatorByAddress(arbitratorNodeAddress) must not be null");
            if (!TradeUtils.isArbitratorSignatureValid(model.getSignOfferResponse().getSignedOfferPayload(), arbitrator)) {
                throw new RuntimeException("Offer payload has invalid arbitrator signature");
            }
            
            // set arbitrator signature for maker's offer
            model.getOffer().getOfferPayload().setArbitratorSignature(model.getSignOfferResponse().getSignedOfferPayload().getArbitratorSignature());
            
            complete();
        } catch (Exception e) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + e.getMessage());
            failed(e);
        }
    }
}
