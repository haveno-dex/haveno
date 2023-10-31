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

package haveno.core.offer.placeoffer.tasks;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.xmr.model.XmrAddressEntry;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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
            model.getXmrWalletService().getConnectionsService().verifyConnection();

            // create reserve tx
            BigInteger makerFee = offer.getMakerFee();
            BigInteger sendAmount = offer.getDirection() == OfferDirection.BUY ? BigInteger.valueOf(0) : offer.getAmount();
            BigInteger securityDeposit = offer.getDirection() == OfferDirection.BUY ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit();
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            XmrAddressEntry fundingEntry = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.OFFER_FUNDING).orElse(null);
            Integer preferredSubaddressIndex = fundingEntry == null ? null : fundingEntry.getSubaddressIndex();
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(makerFee, sendAmount, securityDeposit, returnAddress, model.getOpenOffer().isReserveExactAmount(), preferredSubaddressIndex);

            // check for error in case creating reserve tx exceeded timeout
            // TODO: better way?
            if (!model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).isPresent()) {
                throw new RuntimeException("An error has occurred posting offer " + offer.getId() + " causing its subaddress entry to be deleted");
            }

            // collect reserved key images
            List<String> reservedKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

            // save offer state
            model.setReserveTx(reserveTx);
            offer.getOfferPayload().setReserveTxKeyImages(reservedKeyImages);
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
