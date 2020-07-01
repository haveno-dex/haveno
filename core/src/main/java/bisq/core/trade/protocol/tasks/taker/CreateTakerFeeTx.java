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

package bisq.core.trade.protocol.tasks.taker;

import org.bitcoinj.core.Transaction;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.WalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.dao.exceptions.DaoDisabledException;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class CreateTakerFeeTx extends TradeTask {

    @SuppressWarnings({"unused"})
    public CreateTakerFeeTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            XmrWalletService walletService = processModel.getXmrWalletService();
            String id = processModel.getOffer().getId();

            // We enforce here to create a MULTI_SIG and TRADE_PAYOUT address entry to avoid that the change output would be used later
            // for those address entries. Because we do not commit our fee tx yet the change address would
            // appear as unused and therefor selected for the outputs for the MS tx.
            // That would cause incorrect display of the balance as
            // the change output would be considered as not available balance (part of the locked trade amount).
            walletService.getNewAddressEntry(id, XmrAddressEntry.Context.MULTI_SIG);
            walletService.getNewAddressEntry(id, XmrAddressEntry.Context.TRADE_PAYOUT);

            XmrAddressEntry addressEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.OFFER_FUNDING);
            XmrAddressEntry reservedForTradeAddressEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
            TradeWalletService tradeWalletService = processModel.getTradeWalletService();
            MoneroTxWallet transaction;
            String feeReceiver = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM"; // TODO (woodser): don't hardcode
            if (trade.isCurrencyForTakerFeeBtc()) {
              //fundingAccountIndex, reservedForTradeAddress, reservedFundsForOffer, isUsingSavingsWallet, makerFee, txFee, feeReceiver, broadcastTx)(
                transaction = tradeWalletService.createXmrTradingFeeTx(
                        addressEntry.getAccountIndex(),
                        reservedForTradeAddressEntry.getAddressString(),
                        processModel.getFundsNeededForTradeAsLong(),
                        processModel.isUseSavingsWallet(),
                        trade.getTakerFee(),
                        trade.getTxFee(),
                        feeReceiver,
                        false);
            } else {
                throw new RuntimeException("BSQ wallet not supported in xmr integration");
            }

            // We did not broadcast and commit the tx yet to avoid issues with lost trade fee in case the
            // take offer attempt failed.

            trade.setTakerFeeTxId(transaction.getHash());
            processModel.setTakeOfferFeeTx(transaction);
            walletService.swapTradeEntryToAvailableEntry(id, XmrAddressEntry.Context.OFFER_FUNDING);
            complete();
        } catch (Throwable t) {
            if (t instanceof DaoDisabledException) {
                failed("You cannot pay the trade fee in BSQ at the moment because the DAO features have been " +
                        "disabled due technical problems. Please use the BTC fee option until the issues are resolved. " +
                        "For more information please visit the Bisq Forum.");
            } else {
                failed(t);
            }
        }
    }
}
