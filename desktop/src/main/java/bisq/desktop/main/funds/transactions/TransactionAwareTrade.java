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

package bisq.desktop.main.funds.transactions;

import java.util.Optional;

import bisq.common.crypto.PubKeyRing;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.arbitration.ArbitrationManager;
import bisq.core.support.dispute.refund.RefundManager;
import bisq.core.trade.Tradable;
import bisq.core.trade.Trade;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;


@Slf4j
class TransactionAwareTrade implements TransactionAwareTradable {
    private final Trade trade;
    private final ArbitrationManager arbitrationManager;
    private final RefundManager refundManager;
    private final XmrWalletService xmrWalletService;
    private final PubKeyRing pubKeyRing;

    TransactionAwareTrade(Trade trade,
                          ArbitrationManager arbitrationManager,
                          RefundManager refundManager,
                          XmrWalletService xmrWalletService,
                          PubKeyRing pubKeyRing) {
        this.trade = trade;
        this.arbitrationManager = arbitrationManager;
        this.refundManager = refundManager;
        this.xmrWalletService = xmrWalletService;
        this.pubKeyRing = pubKeyRing;
    }

    @Override
    public boolean isRelatedToTransaction(MoneroTxWallet transaction) {
        String txId = transaction.getHash();

        boolean isTakerOfferFeeTx = txId.equals(trade.getTakerFeeTxId());
        boolean isOfferFeeTx = isOfferFeeTx(txId);
        boolean isMakerDepositTx = isMakerDepositTx(txId);
        boolean isTakerDepositTx = isTakerDepositTx(txId);
        boolean isPayoutTx = isPayoutTx(txId);
        boolean isDisputedPayoutTx = isDisputedPayoutTx(txId);

        return isTakerOfferFeeTx || isOfferFeeTx || isMakerDepositTx || isTakerDepositTx ||
                isPayoutTx || isDisputedPayoutTx;
    }

    private boolean isPayoutTx(String txId) {
        return Optional.ofNullable(trade.getPayoutTx())
                .map(MoneroTxWallet::getHash)
                .map(hash -> hash.equals(txId))
                .orElse(false);
    }
    
    private boolean isMakerDepositTx(String txId) {
        return Optional.ofNullable(trade.getMakerDepositTx())
                .map(MoneroTxWallet::getHash)
                .map(hash -> hash.equals(txId))
                .orElse(false);
    }
    
    private boolean isTakerDepositTx(String txId) {
      return Optional.ofNullable(trade.getTakerDepositTx())
              .map(MoneroTxWallet::getHash)
              .map(hash -> hash.equals(txId))
              .orElse(false);
  }

    private boolean isOfferFeeTx(String txId) {
        return Optional.ofNullable(trade.getOffer())
                .map(Offer::getOfferFeePaymentTxId)
                .map(paymentTxId -> paymentTxId.equals(txId))
                .orElse(false);
    }

    private boolean isDisputedPayoutTx(String txId) {
        String delegateId = trade.getId();

        ObservableList<Dispute> disputes = arbitrationManager.getDisputesAsObservableList();
        return disputes.stream()
                .anyMatch(dispute -> {
                    String disputePayoutTxId = dispute.getDisputePayoutTxId();
                    boolean isDisputePayoutTx = txId.equals(disputePayoutTxId);

                    String disputeTradeId = dispute.getTradeId();
                    boolean isDisputeRelatedToThis = delegateId.equals(disputeTradeId);

                    return isDisputePayoutTx && isDisputeRelatedToThis;
                });
    }

//    boolean isDelayedPayoutTx(String txId) {
//        Transaction transaction = xmrWalletService.getTransaction(txId);
//        if (transaction == null)
//            return false;
//
//        if (transaction.getLockTime() == 0)
//            return false;
//
//        if (transaction.getInputs() == null)
//            return false;
//
//        return transaction.getInputs().stream()
//                .anyMatch(input -> {
//                    TransactionOutput connectedOutput = input.getConnectedOutput();
//                    if (connectedOutput == null) {
//                        return false;
//                    }
//                    Transaction parentTransaction = connectedOutput.getParentTransaction();
//                    if (parentTransaction == null) {
//                        return false;
//                    }
//                    return isDepositTx(parentTransaction.getHashAsString());
//                });
//    }
//
//    private boolean isRefundPayoutTx(String txId) {
//        String tradeId = trade.getId();
//        ObservableList<Dispute> disputes = refundManager.getDisputesAsObservableList();
//        AtomicBoolean isRefundTx = new AtomicBoolean(false);
//        AtomicBoolean isDisputeRelatedToThis = new AtomicBoolean(false);
//        disputes.forEach(dispute -> {
//            String disputeTradeId = dispute.getTradeId();
//            isDisputeRelatedToThis.set(tradeId.equals(disputeTradeId));
//            if (isDisputeRelatedToThis.get()) {
//                Transaction tx = xmrWalletService.getTransaction(txId);
//                if (tx != null) {
//                    tx.getOutputs().forEach(txo -> {
//                        if (xmrWalletService.isTransactionOutputMine(txo)) {
//                            try {
//                                Address receiverAddress = txo.getAddressFromP2PKHScript(xmrWalletService.getParams());
//                                Contract contract = checkNotNull(trade.getContract());
//                                String myPayoutAddressString = contract.isMyRoleBuyer(pubKeyRing) ?
//                                        contract.getBuyerPayoutAddressString() :
//                                        contract.getSellerPayoutAddressString();
//                                if (receiverAddress != null && myPayoutAddressString.equals(receiverAddress.toString())) {
//                                    isRefundTx.set(true);
//                                }
//                            } catch (Throwable ignore) {
//                            }
//
//                        }
//                    });
//                }
//            }
//        });
//
//        return isRefundTx.get() && isDisputeRelatedToThis.get();
//    }

    @Override
    public Tradable asTradable() {
        return trade;
    }
}
