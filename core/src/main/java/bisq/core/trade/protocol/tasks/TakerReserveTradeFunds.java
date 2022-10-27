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

package bisq.core.trade.protocol.tasks;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.trade.Trade;
import bisq.core.util.ParsingUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

public class TakerReserveTradeFunds extends TradeTask {

    public TakerReserveTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // create reserve tx without padding
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            BigInteger takerFee = ParsingUtils.coinToAtomicUnits(trade.getTakerFee());
            BigInteger depositAmount = ParsingUtils.centinerosToAtomicUnits(processModel.getFundsNeededForTradeAsLong());
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(takerFee, returnAddress, depositAmount, true);

            // collect reserved key images
            List<String> reservedKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

            // save process state
            processModel.setReserveTx(reserveTx);
            processModel.getTaker().setReserveTxKeyImages(reservedKeyImages);

            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
