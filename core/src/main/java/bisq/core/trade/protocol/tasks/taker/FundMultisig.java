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

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.ParsingUtils;
import bisq.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class FundMultisig extends TradeTask {
    @SuppressWarnings({"unused"})
    public FundMultisig(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            if (trade.getMakerDepositTxId() != null) throw new RuntimeException("Maker deposit tx already set");  // TODO (woodser): proper error handling
            if (trade.getTakerDepositTxId() != null) throw new RuntimeException("Taker deposit tx already set");
            
            // decide who goes first
            boolean takerGoesFirst = true;  // TODO (woodser): decide who goes first based on rep?
            
            // taker and maker fund multisig
            String takerDepositTxId = null;
            if (takerGoesFirst) takerDepositTxId = takerFundMultisig();
            makerFundMultisig(takerDepositTxId);
        } catch (Throwable t) {
            failed(t);
        }
    }
    
    private String takerFundMultisig() {
      
      // collect parameters for transfer to multisig
      XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
      MoneroWallet wallet = walletService.getWallet();
      MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());
      String multisigAddress = multisigWallet.getPrimaryAddress();
      boolean tradeReserved = trade.getTakerFeeTxId() != null && trade.getState() == Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX;
      
      // if trade is reserved, fund multisig from reserved account
      if (tradeReserved) {
        XmrAddressEntry addressEntry = walletService.getAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.RESERVED_FOR_TRADE).get();
        int accountIndex = addressEntry.getAccountIndex();
        if (!wallet.getBalance(accountIndex).equals(wallet.getUnlockedBalance(accountIndex)) || wallet.getBalance(accountIndex).equals(BigInteger.valueOf(0))) {
          throw new RuntimeException("Reserved trade account " + accountIndex + " balance expected to be fully available.  Balance: " + wallet.getBalance(accountIndex) + ", Unlocked Balance: " + wallet.getUnlockedBalance(accountIndex));
        }
        List<MoneroTxWallet> txs = wallet.sweepUnlocked(new MoneroTxConfig()
                .setAccountIndex(accountIndex)
                .setAddress(multisigAddress)
                .setCanSplit(false)
                .setRelay(true));
        if (txs.size() != 1) throw new RuntimeException("Sweeping reserved trade account to multisig expected to create exactly 1 transaction");
        processModel.setTakerPreparedDepositTxId(txs.get(0).getHash());
        trade.setState(Trade.State.TAKER_PUBLISHED_DEPOSIT_TX);
        System.out.println("SUCCESSFULLY SWEPT RESERVED TRADE ACCOUNT TO MULTISIG");
        System.out.println(txs.get(0));
        return txs.get(0).getHash();
      }
      
      // otherwise fund multisig from account 0 and pay taker fee in one transaction
      else {
          String tradeFeeAddress = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM"; // TODO (woodser): don't hardcode
          MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig()
                  .setAccountIndex(0)
                  .setDestinations(
                          new MoneroDestination(tradeFeeAddress, BigInteger.valueOf(trade.getTakerFee().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER)),
                          new MoneroDestination(multisigAddress, BigInteger.valueOf(processModel.getFundsNeededForTradeAsLong()).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER)))
                  .setRelay(true));
          System.out.println("SUCCESSFULLY TRANSFERRED FROM ACCOUNT 0 TO MULTISIG AND PAID FEE");
          System.out.println(tx);
          trade.setTakerFeeTxId(tx.getHash());
          processModel.setTakerPreparedDepositTxId(tx.getHash());
          trade.setState(Trade.State.TAKER_PUBLISHED_DEPOSIT_TX);
          return tx.getHash();
      }
    }
    
    private void makerFundMultisig(String takerDepositTxId) {
      
      // create message to initialize trade
      DepositTxMessage request = new DepositTxMessage(
              Version.getP2PMessageVersion(),
              UUID.randomUUID().toString(),
              processModel.getOffer().getId(),
              processModel.getMyNodeAddress(),
              processModel.getPubKeyRing(),
              trade.getTakerFeeTxId(),
              takerDepositTxId);
      
      // send request to maker
      // TODO (woodser): get maker deposit tx id by processing DepositTxMessage or DepositTxRequest/DepositTxResponse
      log.info("Send {} with offerId {} and uid {} to maker {} with pub key ring", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), trade.getMakerNodeAddress(), trade.getMakerPubKeyRing());
      processModel.getP2PService().sendEncryptedDirectMessage(
              trade.getMakerNodeAddress(),
              trade.getMakerPubKeyRing(),
              request,
              new SendDirectMessageListener() {
                  @Override
                  public void onArrived() {
                      log.info("{} arrived at arbitrator: offerId={}; uid={}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid());
                      //trade.setState(Trade.State.SELLER_PUBLISHED_DEPOSIT_TX);  // TODO (woodser): Trade.State.MAKER_PUBLISHED_DEPOSIT_TX
                      if (takerDepositTxId == null) takerFundMultisig(); // send taker funds if not already
                      complete();
                  }
                  @Override
                  public void onFault(String errorMessage) {
                      log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                      appendToErrorMessage("Sending request failed: request=" + request + "\nerrorMessage=" + errorMessage);
                      failed();
                  }
              }
      );
    }
}
