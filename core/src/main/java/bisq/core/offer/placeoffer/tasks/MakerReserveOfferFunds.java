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

package bisq.core.offer.placeoffer.tasks;

import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.trade.HavenoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.bitcoinj.core.Coin;

import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class MakerReserveOfferFunds extends Task<PlaceOfferModel> {

    public MakerReserveOfferFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();

        try {
            runInterceptHook();

            // create reserve tx
            BigInteger makerFee = HavenoUtils.coinToAtomicUnits(offer.getMakerFee());
            BigInteger peerAmount = HavenoUtils.coinToAtomicUnits(offer.getDirection() == OfferDirection.BUY ? Coin.ZERO : offer.getAmount());
            BigInteger securityDeposit = HavenoUtils.coinToAtomicUnits(offer.getDirection() == OfferDirection.BUY ? offer.getBuyerSecurityDeposit() : offer.getSellerSecurityDeposit());
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(makerFee, peerAmount, securityDeposit, returnAddress);

            // collect reserved key images
            List<String> reservedKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

            // save offer state
            model.setReserveTx(reserveTx);
            offer.getOfferPayload().setReserveTxKeyImages(reservedKeyImages);
            offer.setOfferFeePaymentTxId(reserveTx.getHash()); // TODO (woodser): don't use this field
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
