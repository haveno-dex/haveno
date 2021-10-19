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

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.taskrunner.TaskRunner;

import org.bitcoinj.core.Coin;

import lombok.extern.slf4j.Slf4j;



import monero.wallet.model.MoneroTxWallet;

// TODO (woodser): rename this to TakerCreateFeeTx or rename TakerPublishFeeTx to TakerPublishReserveTradeTx for consistency
@Slf4j
public class TakerCreateFeeTx extends TradeTask {
  @SuppressWarnings({ "unused" })
  public TakerCreateFeeTx(TaskRunner taskHandler, Trade trade) {
    super(taskHandler, trade);
  }

  @Override
  protected void run() {
    try {
      runInterceptHook();

      XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
      String id = processModel.getOffer().getId();
      XmrAddressEntry reservedForTradeAddressEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
      TradeWalletService tradeWalletService = processModel.getTradeWalletService();
      String feeReceiver = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM"; // TODO (woodser): don't hardcode

      // pay trade fee to reserve trade
      MoneroTxWallet tx = tradeWalletService.createXmrTradingFeeTx(
              reservedForTradeAddressEntry.getAddressString(),
              Coin.valueOf(processModel.getFundsNeededForTradeAsLong()),
              trade.getTakerFee(),
              trade.getTxFee(),
              feeReceiver,
              false);

      trade.setTakerFeeTxId(tx.getHash());
      processModel.setTakeOfferFeeTx(tx);
      complete();
    } catch (Throwable t) {
        failed(t);
    }
  }
}
