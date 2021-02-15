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

package bisq.core.trade.protocol.tasks.maker;

import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.Validator;
import bisq.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class MakerCreateAndPublishDepositTx extends TradeTask {
    private Subscription tradeStateSubscription;
  
    @SuppressWarnings({"unused"})
    public MakerCreateAndPublishDepositTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            System.out.println("MakerCreateAndPublishDepositTx");
            log.debug("current trade state " + trade.getState());
            DepositTxMessage message = (DepositTxMessage) processModel.getTradeMessage();
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);
            trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());
            if (trade.getMakerDepositTxId() != null) throw new RuntimeException("Maker deposit tx already set for trade " + trade.getId() +  ", this should not happen"); // TODO: ignore and nack bad requests to not show on client
            
            System.out.println(message);
            
            // decide who goes first
            boolean takerGoesFirst = true;  // TODO (woodser): based on rep?
            
            // send deposit tx after taker
            if (takerGoesFirst) {
              
              // verify taker's deposit tx  // TODO (woodser): taker needs to prove tx to address, cannot claim tx id, verify tx id seen in pool
              if (message.getDepositTxId() == null) throw new RuntimeException("Taker must prove deposit tx before maker deposits");
              processModel.setTakerPreparedDepositTxId(message.getDepositTxId());
              
              // collect parameters for transfer to multisig
              XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
              MoneroWallet wallet = walletService.getWallet();
              MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());
              String multisigAddress = multisigWallet.getPrimaryAddress();
              
              // send deposit tx
              XmrAddressEntry addressEntry = walletService.getAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.RESERVED_FOR_TRADE).get();
              int accountIndex = addressEntry.getAccountIndex();
              if (!wallet.getBalance(accountIndex).equals(wallet.getUnlockedBalance(accountIndex)) || wallet.getBalance(accountIndex).equals(BigInteger.valueOf(0))) {
                throw new RuntimeException("Reserved trade account balance expected to be fully available");
              }
              System.out.println("Sweeping unlocked balance in account " + accountIndex + ": " + wallet.getUnlockedBalance(accountIndex));
              List<MoneroTxWallet> txs = wallet.sweepUnlocked(new MoneroTxConfig()
                      .setAccountIndex(accountIndex)
                      .setAddress(multisigAddress)
                      .setCanSplit(false)
                      .setRelay(true));
              if (txs.size() != 1) throw new RuntimeException("Sweeping reserved trade account to multisig expected to create exactly 1 transaction");
              MoneroTxWallet makerDepositTx = txs.get(0);
              processModel.setMakerPreparedDepositTxId(makerDepositTx.getHash());
              //trade.setState(Trade.State.SELLER_SENT_DEPOSIT_TX_PUBLISHED_MSG); // TODO (wooder): state for MAKER_TRANSFERRED_TO_MULTISIG?
              System.out.println("SUCCESSFULLY SWEPT RESERVED TRADE ACCOUNT TO MULTISIG");
              System.out.println(txs.get(0));
              
              // apply published transaction which notifies ui
              applyPublishedDepositTxs(makerDepositTx, multisigWallet.getTx(processModel.getTakerPreparedDepositTxId()));
              
              // notify trade state subscription when deposit published
              tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), newValue -> {
                if (trade.isDepositPublished()) {
                  swapReservedForTradeEntry();
                  UserThread.execute(this::unSubscribe);  // hack to remove tradeStateSubscription at callback
                }
              });
            }
            
            // send deposit tx before taker
            else {
              throw new RuntimeException("Maker goes first not implemented");
            }
            
            // create message to notify taker of maker's deposit tx
            DepositTxMessage request = new DepositTxMessage(
                    Version.getP2PMessageVersion(),
                    UUID.randomUUID().toString(),
                    processModel.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    null,
                    trade.getMakerDepositTxId());
            
            // notify taker of maker's deposit tx
            log.info("Send {} with offerId {} and uid {} to maker {}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), trade.getTakerNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getTakerNodeAddress(),
                    trade.getTakerPubKeyRing(),
                    request,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at taker: offerId={}; uid={}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid());
                            complete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), trade.getTakerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending request failed: request=" + request + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
            
        // complete
        processModel.getTradeManager().requestPersistence();
        } catch (Throwable t) {
            failed(t);
        }
    }
    
    private void applyPublishedDepositTxs(MoneroTxWallet makerDepositTx, MoneroTxWallet takerDepositTx) {
      if (trade.getMakerDepositTx() == null && trade.getTakerDepositTx() == null) {
        trade.applyDepositTxs(makerDepositTx, takerDepositTx);
        XmrWalletService.printTxs("depositTxs received from network", makerDepositTx, takerDepositTx);
        trade.setState(Trade.State.MAKER_SAW_DEPOSIT_TX_IN_NETWORK);  // TODO (woodser): MAKER_PUBLISHED_DEPOSIT_TX
      } else {
        log.info("We got the deposit tx already set from MakerCreateAndPublishDepositTx.  tradeId={}, state={}", trade.getId(), trade.getState());
      }
      
      swapReservedForTradeEntry();
      
      // need delay as it can be called inside the listener handler before listener and tradeStateSubscription are actually set.
      UserThread.execute(this::unSubscribe);
    }

    private void swapReservedForTradeEntry() {
        log.info("swapReservedForTradeEntry");
        processModel.getProvider().getXmrWalletService().swapTradeEntryToAvailableEntry(trade.getId(), XmrAddressEntry.Context.RESERVED_FOR_TRADE);
    }

    private void unSubscribe() {
        if (tradeStateSubscription != null) tradeStateSubscription.unsubscribe();
    }
}
