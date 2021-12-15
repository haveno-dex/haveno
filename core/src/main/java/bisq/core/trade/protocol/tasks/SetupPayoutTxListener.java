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

import bisq.core.btc.listeners.AddressConfidenceListener;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;

import bisq.common.UserThread;
import bisq.common.taskrunner.TaskRunner;

import org.bitcoinj.core.TransactionConfidence;

import org.fxmisc.easybind.Subscription;

import java.util.List;

import lombok.extern.slf4j.Slf4j;



import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public abstract class SetupPayoutTxListener extends TradeTask {
    // Use instance fields to not get eaten up by the GC
    private Subscription tradeStateSubscription;
    private AddressConfidenceListener confidenceListener;

    public SetupPayoutTxListener(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }


    protected abstract void setState();

    @Override
    protected void run() {
        try {
            runInterceptHook();
            System.out.println("NEED TO IMPLEMENT PAYOUT TX LISTENER!"); // TODO (woodser): implement SetupPayoutTxListener
//            if (!trade.isPayoutPublished()) {
//                BtcWalletService walletService = processModel.getBtcWalletService();
//                String id = processModel.getOffer().getId();
//                Address address = walletService.getOrCreateAddressEntry(id, AddressEntry.Context.TRADE_PAYOUT).getAddress();
//
//                TransactionConfidence confidence = walletService.getConfidenceForAddress(address);
//                if (isInNetwork(confidence)) {
//                    applyConfidence(confidence);
//                } else {
//                    confidenceListener = new AddressConfidenceListener(address) {
//                        @Override
//                        public void onTransactionConfidenceChanged(TransactionConfidence confidence) {
//                            if (isInNetwork(confidence))
//                                applyConfidence(confidence);
//                        }
//                    };
//                    walletService.addAddressConfidenceListener(confidenceListener);
//
//                    tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), newValue -> {
//                        if (trade.isPayoutPublished()) {
//                            processModel.getBtcWalletService().resetCoinLockedInMultiSigAddressEntry(trade.getId());
//
//                            // hack to remove tradeStateSubscription at callback
//                            UserThread.execute(this::unSubscribe);
//                        }
//                    });
//                }
//            }

            // we complete immediately, our object stays alive because the balanceListener is stored in the WalletService
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void applyPayoutTx(int accountIdx) {
        if (trade.getPayoutTx() == null) {

            // get txs with transfers to payout subaddress
            List<MoneroTxWallet> txs = processModel.getProvider().getXmrWalletService().getWallet().getTxs(new MoneroTxQuery()
                    .setTransferQuery(new MoneroTransferQuery().setAccountIndex(accountIdx).setSubaddressIndex(0).setIsIncoming(true)));  // TODO (woodser): hardcode account 0 as savings wallet, subaddress 0 trade accounts in config

            // resolve payout tx if multiple txs sent to payout address
            MoneroTxWallet payoutTx;
            if (txs.size() > 1) {
                throw new RuntimeException("Need to resolve multiple payout txs");  // TODO (woodser)
            } else {
                payoutTx = txs.get(0);
            }

            trade.setPayoutTx(payoutTx);
            XmrWalletService.printTxs("payoutTx received from network", payoutTx);
            setState();
        } else {
            log.info("We had the payout tx already set. tradeId={}, state={}", trade.getId(), trade.getState());
        }

        processModel.getBtcWalletService().resetCoinLockedInMultiSigAddressEntry(trade.getId());

        // need delay as it can be called inside the handler before the listener and tradeStateSubscription are actually set.
        UserThread.execute(this::unSubscribe);
    }

    private boolean isInNetwork(TransactionConfidence confidence) {
        return confidence != null &&
                (confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.BUILDING) ||
                        confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.PENDING));
    }

    private void unSubscribe() {
        if (tradeStateSubscription != null)
            tradeStateSubscription.unsubscribe();

        if (confidenceListener != null)
            processModel.getBtcWalletService().removeAddressConfidenceListener(confidenceListener);
    }
}
