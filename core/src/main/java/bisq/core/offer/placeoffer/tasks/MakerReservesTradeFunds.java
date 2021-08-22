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

import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.trade.TradeUtils;
import bisq.core.util.ParsingUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import monero.daemon.model.MoneroOutput;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;

public class MakerReservesTradeFunds extends Task<PlaceOfferModel> {

    public MakerReservesTradeFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();

        try {
            runInterceptHook();
            
            // create transaction to reserve trade
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            BigInteger makerFee = ParsingUtils.coinToAtomicUnits(offer.getMakerFee());
            BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(model.getReservedFundsForOffer());
            MoneroTxWallet reserveTx = TradeUtils.createReserveTx(model.getXmrWalletService(), offer.getId(), makerFee, returnAddress, depositAmount);
            
            // freeze reserved outputs
            // TODO (woodser): synchronize to handle potential race condition where concurrent trades freeze each other's outputs
            List<String> frozenKeyImages = new ArrayList<String>();
            MoneroWallet wallet = model.getXmrWalletService().getWallet();
            for (MoneroOutput input : reserveTx.getInputs()) {
                frozenKeyImages.add(input.getKeyImage().getHex());
                wallet.freezeOutput(input.getKeyImage().getHex());
            }
            
            // save offer state
            // TODO (woodser): persist
            model.setReserveTx(reserveTx);
            offer.setOfferFeePaymentTxId(reserveTx.getHash()); // TODO (woodser): rename this to reserve tx id
            offer.setState(Offer.State.OFFER_FEE_RESERVED);
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
