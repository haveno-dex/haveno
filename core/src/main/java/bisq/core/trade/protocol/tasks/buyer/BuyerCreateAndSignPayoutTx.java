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

package bisq.core.trade.protocol.tasks.buyer;

import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.ParsingUtils;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class BuyerCreateAndSignPayoutTx extends TradeTask {

    @SuppressWarnings({"unused"})
    public BuyerCreateAndSignPayoutTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // validate state
            Preconditions.checkNotNull(trade.getTradeAmount(), "trade.getTradeAmount() must not be null");
            Preconditions.checkNotNull(trade.getMakerDepositTx(), "trade.getMakerDepositTx() must not be null");
            Preconditions.checkNotNull(trade.getTakerDepositTx(), "trade.getTakerDepositTx() must not be null");
            Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
            
            // gather relevant trade info
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());
            String sellerPayoutAddress = processModel.getTradingPeer().getPayoutAddressString();
            String buyerPayoutAddress = trade instanceof MakerTrade ? trade.getContract().getMakerPayoutAddressString() : trade.getContract().getTakerPayoutAddressString();
            Preconditions.checkNotNull(sellerPayoutAddress, "sellerPayoutAddress must not be null");
            Preconditions.checkNotNull(buyerPayoutAddress, "buyerPayoutAddress must not be null");
            BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getTakerPreparedDepositTxId() : processModel.getMakerPreparedDepositTxId()).getIncomingAmount(); 	// TODO (woodser): redundancy of processModel.getPreparedDepositTxId() vs trade.getDepositTxId() necessary or avoidable?
            BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getMakerPreparedDepositTxId() : processModel.getTakerPreparedDepositTxId()).getIncomingAmount();
            BigInteger tradeAmount = BigInteger.valueOf(trade.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
            BigInteger buyerPayoutAmount = buyerDepositAmount.add(tradeAmount);
            BigInteger sellerPayoutAmount = sellerDepositAmount.subtract(tradeAmount);
            
            System.out.println("sellerPayoutAddress: " + sellerPayoutAddress);
            System.out.println("buyerPayoutAddress: " + buyerPayoutAddress);
            System.out.println("Multisig balance: " + multisigWallet.getBalance());
            System.out.println("Multisig unlocked balance: " + multisigWallet.getUnlockedBalance());
            System.out.println("Multisig txs");
            System.out.println(multisigWallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true)));
            
            //System.out.println("Testing buyer payout amount: " + buyerPayoutAmount.multiply(BigInteger.valueOf(3)).divide(BigInteger.valueOf(5)));
            //System.out.println("Testing seller payout amount: " + sellerPayoutAmount.multiply(BigInteger.valueOf(3)).divide(BigInteger.valueOf(5)));
            //System.out.println("Testing payout amount: " + (buyerPayoutAmount.multiply(BigInteger.valueOf(3)).divide(BigInteger.valueOf(5))).add(sellerPayoutAmount.multiply(BigInteger.valueOf(3)).divide(BigInteger.valueOf(5))));
            
            // create transaction to get fee estimate
            if (multisigWallet.isMultisigImportNeeded()) throw new RuntimeException("Multisig import is still needed!!!");
            
            System.out.println("Creating feeEstimateTx!");
            MoneroTxWallet feeEstimateTx = multisigWallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(new MoneroDestination(buyerPayoutAddress, buyerPayoutAmount.multiply(BigInteger.valueOf(4)).divide(BigInteger.valueOf(5)))) // reduce payment amount to compute fee of similar tx
                    .addDestination(new MoneroDestination(sellerPayoutAddress, sellerPayoutAmount.multiply(BigInteger.valueOf(4)).divide(BigInteger.valueOf(5)))) // TODO (woodser): support addDestination(addr, amt) without new
                    .setRelay(false)
            );
            
            System.out.println("Created fee estimate tx!");
            System.out.println(feeEstimateTx);
            //BigInteger estimatedFee = feeEstimateTx.getFee();
            
            // attempt to create payout tx by increasing estimated fee until successful
            MoneroTxWallet payoutTx = null;
            int numAttempts = 0;
            while (payoutTx == null && numAttempts < 50) {
              BigInteger feeEstimate = feeEstimateTx.getFee().add(feeEstimateTx.getFee().multiply(BigInteger.valueOf(numAttempts)).divide(BigInteger.valueOf(10))); // add 1/10 of fee until tx is successful
              try {
                numAttempts++;
                payoutTx = multisigWallet.createTx(new MoneroTxConfig()
                        .setAccountIndex(0)
                        .addDestination(new MoneroDestination(buyerPayoutAddress, buyerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(2))))) // split fee subtracted from each payout amount
                        .addDestination(new MoneroDestination(sellerPayoutAddress, sellerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(2))))) // TODO (woodser): support addDestination(addr, amt) without new
                        .setRelay(false));
              } catch (MoneroError e) {
                e.printStackTrace();
                System.out.println("FAILED TO CREATE PAYOUT TX, ITERATING...");
              }
            }
            
            if (payoutTx == null) throw new RuntimeException("Failed to generate payout tx");
            System.out.println("PAYOUT TX GENERATED ON ATTEMPT " + numAttempts);
            System.out.println(payoutTx);
            
            processModel.setBuyerSignedPayoutTx(payoutTx);
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
    
    /**
     * Generic parameterized pair.
     * 
     * @author woodser
     *
     * @param <F> the type of the first element
     * @param <S> the type of the second element
     */
    public static class Pair<F, S> {

      private F first;
      private S second;
      
      public Pair(F first, S second) {
        super();
        this.first = first;
        this.second = second;
      }

      public F getFirst() {
        return first;
      }
      
      public void setFirst(F first) {
        this.first = first;
      }
      
      public S getSecond() {
        return second;
      }
      
      public void setSecond(S second) {
        this.second = second;
      }
    }
    
    public static void printBalances(MoneroWallet wallet) {
      
      // collect info about subaddresses
      List<Pair<String, List<Object>>> pairs = new ArrayList<Pair<String, List<Object>>>();
      //if (wallet == null) wallet = TestUtils.getWalletJni();
      BigInteger balance = wallet.getBalance();
      BigInteger unlockedBalance = wallet.getUnlockedBalance();
      List<MoneroAccount> accounts = wallet.getAccounts(true);
      System.out.println("Wallet balance: " + balance);
      System.out.println("Wallet unlocked balance: " + unlockedBalance);
      for (MoneroAccount account : accounts) {
        add(pairs, "ACCOUNT", account.getIndex());
        add(pairs, "SUBADDRESS", "");
        add(pairs, "LABEL", "");
        add(pairs, "ADDRESS", "");
        add(pairs, "BALANCE", account.getBalance());
        add(pairs, "UNLOCKED", account.getUnlockedBalance());
        for (MoneroSubaddress subaddress : account.getSubaddresses()) {
          add(pairs, "ACCOUNT", account.getIndex());
          add(pairs, "SUBADDRESS", subaddress.getIndex());
          add(pairs, "LABEL", subaddress.getLabel());
          add(pairs, "ADDRESS", subaddress.getAddress());
          add(pairs, "BALANCE", subaddress.getBalance());
          add(pairs, "UNLOCKED", subaddress.getUnlockedBalance());
        }
      }
      
      // convert info to csv
      Integer length = null;
      for (Pair<String, List<Object>> pair : pairs) {
        if (length == null) length = pair.getSecond().size();
      }
      
      System.out.println(pairsToCsv(pairs));
    }
    
    private static void add(List<Pair<String, List<Object>>> pairs, String header, Object value) {
      if (value == null) value = "";
      Pair<String, List<Object>> pair = null;
      for (Pair<String, List<Object>> aPair : pairs) {
        if (aPair.getFirst().equals(header)) {
          pair = aPair;
          break;
        }
      }
      if (pair == null) {
        List<Object> vals = new ArrayList<Object>();
        pair = new Pair<String, List<Object>>(header, vals);
        pairs.add(pair);
      }
      pair.getSecond().add(value);
    }
    
    private static String pairsToCsv(List<Pair<String, List<Object>>> pairs) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < pairs.size(); i++) {
        sb.append(pairs.get(i).getFirst());
        if (i < pairs.size() - 1) sb.append(',');
        else sb.append('\n');
      }
      for (int i = 0; i < pairs.get(0).getSecond().size(); i++) {
        for (int j = 0; j < pairs.size(); j++) {
          sb.append(pairs.get(j).getSecond().get(i));
          if (j < pairs.size() - 1) sb.append(',');
          else sb.append('\n');
        }
      }
      return sb.toString();
    }
}


