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

package haveno.core.offer.placeoffer.tasks;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.placeoffer.PlaceOfferModel;

import static com.google.common.base.Preconditions.checkNotNull;

public class MaybeAddToOfferBook extends Task<PlaceOfferModel> {

    public MaybeAddToOfferBook(TaskRunner<PlaceOfferModel> taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            checkNotNull(model.getSignOfferResponse().getSignedOfferPayload().getArbitratorSignature(), "Offer's arbitrator signature is null: " + model.getOpenOffer().getOffer().getId());

            // deactivate if conflicting offer exists
            if (model.getOpenOfferManager().hasConflictingClone(model.getOpenOffer())) {
                model.getOpenOffer().setState(OpenOffer.State.DEACTIVATED);
                model.setOfferAddedToOfferBook(false);
                complete();
                return;
            }

            // add to offer book and activate if pending or available
            if (model.getOpenOffer().isPending() || model.getOpenOffer().isAvailable()) {
                model.getOfferBookService().addOffer(new Offer(model.getSignOfferResponse().getSignedOfferPayload()),
                        () -> {
                            model.getOpenOffer().setState(OpenOffer.State.AVAILABLE);
                            model.setOfferAddedToOfferBook(true);
                            complete();
                        },
                        errorMessage -> {
                            model.getOpenOffer().getOffer().setErrorMessage("Could not add offer to offerbook.\n" +
                                    "Please check your network connection and try again.");
                            failed(errorMessage);
                        });
            } else {
                complete();
                return;
            }
        } catch (Throwable t) {
            model.getOpenOffer().getOffer().setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + t.getMessage());

            failed(t);
        }
    }
}
