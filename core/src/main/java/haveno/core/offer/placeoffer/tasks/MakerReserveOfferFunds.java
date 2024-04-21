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
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.xmr.model.XmrAddressEntry;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class MakerReserveOfferFunds extends Task<PlaceOfferModel> {

    public MakerReserveOfferFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOpenOffer().getOffer();

        try {
            runInterceptHook();

            // verify monero connection
            model.getXmrWalletService().getConnectionService().verifyConnection();

            // create reserve tx
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(model.getOpenOffer());
            model.setReserveTx(reserveTx);

            // check for error in case creating reserve tx exceeded timeout // TODO: better way?
            if (!model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).isPresent()) {
                throw new RuntimeException("An error has occurred posting offer " + offer.getId() + " causing its subaddress entry to be deleted");
            }

            // reset protocol timeout
            model.getProtocol().startTimeoutTimer();
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
