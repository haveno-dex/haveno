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

package haveno.core.trade.protocol.tasks;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.OfferDirection;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.xmr.model.XmrAddressEntry;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class TakerReserveTradeFunds extends TradeTask {

    public TakerReserveTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // create reserve tx
            BigInteger penaltyFee = HavenoUtils.multiply(trade.getAmount(), trade.getOffer().getPenaltyFeePct());
            BigInteger takerFee = trade.getTakerFee();
            BigInteger sendAmount = trade.getOffer().getDirection() == OfferDirection.BUY ? trade.getAmount() : BigInteger.ZERO;
            BigInteger securityDeposit = trade.getOffer().getDirection() == OfferDirection.BUY ? trade.getSellerSecurityDepositBeforeMiningFee() : trade.getBuyerSecurityDepositBeforeMiningFee();
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(penaltyFee, takerFee, sendAmount, securityDeposit, returnAddress, false, null);

            // check if trade still exists
            if (!processModel.getTradeManager().hasOpenTrade(trade)) {
                throw new RuntimeException("Trade protocol has timed out while creating reserve tx, tradeId=" + trade.getId());
            }

            // collect reserved key images
            List<String> reservedKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

            // reset protocol timeout
            trade.getProtocol().startTimeout(TradeProtocol.TRADE_TIMEOUT);

            // save process state
            processModel.setReserveTx(reserveTx);
            processModel.getTaker().setReserveTxKeyImages(reservedKeyImages);
            processModel.getTradeManager().requestPersistence();
            trade.addInitProgressStep();
            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
