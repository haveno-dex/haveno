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

//import haveno.core.util.FeeReceiverSelector;
import haveno.common.UserThread;
import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.btc.exceptions.TxBroadcastException;
import haveno.core.btc.model.AddressEntry;
import haveno.core.btc.wallet.BtcWalletService;
import haveno.core.btc.wallet.TradeWalletService;
import haveno.core.btc.wallet.TxBroadcaster;
import haveno.core.btc.wallet.WalletService;
import haveno.core.offer.Offer;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class CreateMakerFeeTx extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(CreateMakerFeeTx.class);

    @SuppressWarnings({"unused"})
    public CreateMakerFeeTx(TaskRunner<PlaceOfferModel> taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();

        try {
            runInterceptHook();

            String id = offer.getId();
            BtcWalletService walletService = model.getWalletService();

            Address fundingAddress = walletService.getOrCreateAddressEntry(id, AddressEntry.Context.OFFER_FUNDING).getAddress();
            Address reservedForTradeAddress = walletService.getOrCreateAddressEntry(id, AddressEntry.Context.RESERVED_FOR_TRADE).getAddress();
            Address changeAddress = walletService.getFreshAddressEntry().getAddress();

            TradeWalletService tradeWalletService = model.getTradeWalletService();
            throw new RuntimeException("CreateMakerFeeTx not used for XMR");
        } catch (Throwable t) {
                offer.setErrorMessage("An error occurred.\n" +
                        "Error message:\n"
                        + t.getMessage());

            failed(t);
        }
    }
}
