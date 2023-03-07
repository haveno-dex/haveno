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

package haveno.core.offer.availability.tasks;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.AvailabilityResult;
import haveno.core.offer.Offer;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.core.trade.HavenoUtils;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ProcessOfferAvailabilityResponse extends Task<OfferAvailabilityModel> {
    public ProcessOfferAvailabilityResponse(TaskRunner<OfferAvailabilityModel> taskHandler,
                                            OfferAvailabilityModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();
        try {
            runInterceptHook();

            checkArgument(offer.getState() != Offer.State.REMOVED, "Offer state must not be Offer.State.REMOVED");

            // check availability result
            OfferAvailabilityResponse offerAvailabilityResponse = model.getMessage();
            if (offerAvailabilityResponse.getAvailabilityResult() != AvailabilityResult.AVAILABLE) {
                offer.setState(Offer.State.NOT_AVAILABLE);
                failed("Take offer attempt rejected because of: " + offerAvailabilityResponse.getAvailabilityResult());
                return;
            }
            
            // verify maker signature for trade request
            if (!HavenoUtils.isMakerSignatureValid(model.getTradeRequest(), offerAvailabilityResponse.getMakerSignature(), offer.getPubKeyRing())) {
                offer.setState(Offer.State.NOT_AVAILABLE);
                failed("Take offer attempt failed because maker signature is invalid");
                return;
            }
            
            offer.setState(Offer.State.AVAILABLE);
            model.setMakerSignature(offerAvailabilityResponse.getMakerSignature());
            checkNotNull(model.getMakerSignature());

            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + t.getMessage());

            failed(t);
        }
    }
}
