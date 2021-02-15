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

package bisq.core.trade.protocol.tasks;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public abstract class SetupDepositTxsListener extends TradeTask {
  // Use instance fields to not get eaten up by the GC
  private MoneroWalletListener depositTxListener;
  private Boolean makerDepositLocked; // null when unknown, true while locked, false when unlocked
  private Boolean takerDepositLocked;

  @SuppressWarnings({ "unused" })
  public SetupDepositTxsListener(TaskRunner taskHandler, Trade trade) {
    super(taskHandler, trade);
  }

  @Override
  protected void run() {
    try {
      runInterceptHook();

      // fetch relevant trade info
      XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
      MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());
      System.out.println("Maker prepared deposit tx id: " + processModel.getMakerPreparedDepositTxId());
      System.out.println("Taker prepared deposit tx id: " + processModel.getTakerPreparedDepositTxId());

      // register listener with multisig wallet
      depositTxListener = walletService.new MisqWalletListener(new MoneroWalletListener() { // TODO (woodser): separate into own class file
        @Override
        public void onOutputReceived(MoneroOutputWallet output) {
          
          // ignore if no longer listening
          if (depositTxListener == null) return;
          
          // TODO (woodser): remove this
          if (output.getTx().isConfirmed() && (processModel.getMakerPreparedDepositTxId().equals(output.getTx().getHash()) || processModel.getTakerPreparedDepositTxId().equals(output.getTx().getHash()))) {
            System.out.println("Deposit output for tx " + output.getTx().getHash() + " is confirmed at height " + output.getTx().getHeight());
          }
          
          // update locked state
          if (output.getTx().getHash().equals(processModel.getMakerPreparedDepositTxId())) makerDepositLocked = output.getTx().isLocked();
          else if (output.getTx().getHash().equals(processModel.getTakerPreparedDepositTxId())) takerDepositLocked = output.getTx().isLocked();
          
          // deposit txs seen when both locked states seen
          if (makerDepositLocked != null && takerDepositLocked != null) {
            trade.setState(getSeenState());
          }
          
          // confirm trade and update ui when both deposits unlock
          if (Boolean.FALSE.equals(makerDepositLocked) && Boolean.FALSE.equals(takerDepositLocked)) {
            System.out.println("MULTISIG DEPOSIT TXS UNLOCKED!!!");
            trade.applyDepositTxs(multisigWallet.getTx(processModel.getMakerPreparedDepositTxId()), multisigWallet.getTx(processModel.getTakerPreparedDepositTxId()));
            multisigWallet.removeListener(depositTxListener); // remove listener when notified
            depositTxListener = null; // prevent re-applying trade state in subsequent requests
          }
        }
      });
      multisigWallet.addListener(depositTxListener);

      // complete immediately
      complete();
    } catch (Throwable t) {
      failed(t);
    }
  }
  
  protected abstract State getSeenState();
}
