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

import com.google.common.base.Preconditions;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class BuyerPreparePaymentSentMessage extends TradeTask {

    @SuppressWarnings({"unused"})
    public BuyerPreparePaymentSentMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // skip if already created
            if (processModel.getPaymentSentMessage() != null) {
              log.warn("Skipping preparation of payment sent message since it's already created for {} {}", trade.getClass().getSimpleName(), trade.getId());
              complete();
              return;
            }

            // validate state
            Preconditions.checkNotNull(trade.getSeller().getPaymentAccountPayload(), "Seller's payment account payload is null");
            Preconditions.checkNotNull(trade.getAmount(), "trade.getTradeAmount() must not be null");
            Preconditions.checkNotNull(trade.getMakerDepositTx(), "trade.getMakerDepositTx() must not be null");
            Preconditions.checkNotNull(trade.getTakerDepositTx(), "trade.getTakerDepositTx() must not be null");
            checkNotNull(trade.getOffer(), "offer must not be null");

            // create payout tx if we have seller's updated multisig hex
            if (trade.getSeller().getUpdatedMultisigHex() != null) {

                // create payout tx
                log.info("Buyer creating unsigned payout tx");
                MoneroTxWallet payoutTx = trade.createPayoutTx();
                trade.setPayoutTx(payoutTx);
                trade.setPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
            }

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }

    // TODO (woodser): move these to gen utils

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


