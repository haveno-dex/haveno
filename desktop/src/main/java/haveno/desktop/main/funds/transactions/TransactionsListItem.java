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

package haveno.desktop.main.funds.transactions;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.Tooltip;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroOutgoingTransfer;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Date;
import java.util.Optional;

@Slf4j
class TransactionsListItem {
    private String dateString;
    private final Date date;
    private final String txId;
    @Nullable
    private Tradable tradable;
    private String details = "";
    private String addressString = "";
    private String direction = "";
    private boolean received;
    private boolean detailsAvailable;
    private BigInteger amount = BigInteger.valueOf(0);
    private String memo = "";
    private long confirmations = 0;
    @Getter
    private boolean initialTxConfidenceVisibility = true;
    private final Supplier<LazyFields> lazyFieldsSupplier;
    private XmrWalletService xmrWalletService;
    MoneroWalletListener walletListener;

    private static class LazyFields {
        TxConfidenceIndicator txConfidenceIndicator;
        Tooltip tooltip;
    }

    private LazyFields lazy() {
        return lazyFieldsSupplier.get();
    }

    // used at exportCSV
    TransactionsListItem() {
        date = null;
        txId = null;
        lazyFieldsSupplier = null;
    }

    TransactionsListItem(MoneroTxWallet tx,
                         XmrWalletService xmrWalletService,
                         TransactionAwareTradable transactionAwareTradable) {
        this.memo = tx.getNote();
        this.txId = tx.getHash();
        this.xmrWalletService = xmrWalletService;
        this.confirmations = tx.getNumConfirmations() == null ? 0 : tx.getNumConfirmations();

        Optional<Tradable> optionalTradable = Optional.ofNullable(transactionAwareTradable)
                .map(TransactionAwareTradable::asTradable);

        BigInteger valueSentToMe = tx.getIncomingAmount() == null ? BigInteger.valueOf(0) : tx.getIncomingAmount();
        BigInteger valueSentFromMe = tx.getOutgoingAmount() == null ? BigInteger.valueOf(0) : tx.getOutgoingAmount();

        if (tx.getTransfers().get(0).isIncoming()) {
            addressString = ((MoneroIncomingTransfer) tx.getTransfers().get(0)).getAddress();
        } else {
            MoneroOutgoingTransfer transfer = (MoneroOutgoingTransfer) tx.getTransfers().get(0);
            if (transfer.getDestinations() != null) addressString = transfer.getDestinations().get(0).getAddress();
            else addressString = "unavailable";
        }

        if (valueSentFromMe.compareTo(BigInteger.valueOf(0)) == 0) {
            amount = valueSentToMe;
            direction = Res.get("funds.tx.direction.receivedWith");
            received = true;
        } else {
            amount = valueSentFromMe.multiply(BigInteger.valueOf(-1));
            received = false;
            direction = Res.get("funds.tx.direction.sentTo");
        }

        if (optionalTradable.isPresent()) {
            tradable = optionalTradable.get();
            detailsAvailable = true;
            String tradeId = tradable.getShortId();
            if (tradable instanceof OpenOffer) {
                details = Res.get("funds.tx.createOfferFee", tradeId);
            } else if (tradable instanceof Trade) {
                Trade trade = (Trade) tradable;

                Offer offer = trade.getOffer();
                String offerFeeTxId = offer.getOfferFeeTxId();
                if (offerFeeTxId != null && offerFeeTxId.equals(txId)) {
                    details = Res.get("funds.tx.createOfferFee", tradeId);
                } else if (trade.getSelf().getDepositTxHash() != null &&
                        trade.getSelf().getDepositTxHash().equals(txId)) {
                    details = Res.get("funds.tx.multiSigDeposit", tradeId);
                } else if (trade.getPayoutTxId() != null &&
                        trade.getPayoutTxId().equals(txId)) {
                    details = Res.get("funds.tx.multiSigPayout", tradeId);
                    if (amount.compareTo(BigInteger.valueOf(0)) == 0) {
                        initialTxConfidenceVisibility = false;
                    }
                } else {
                    Trade.DisputeState disputeState = trade.getDisputeState();
                    if (disputeState == Trade.DisputeState.DISPUTE_CLOSED) {
                        if (valueSentToMe.compareTo(BigInteger.valueOf(0)) > 0) {
                            details = Res.get("funds.tx.disputePayout", tradeId);
                        } else {
                            details = Res.get("funds.tx.disputeLost", tradeId);
                        }
                    } else if (disputeState == Trade.DisputeState.REFUND_REQUEST_CLOSED ||
                            disputeState == Trade.DisputeState.REFUND_REQUESTED ||
                            disputeState == Trade.DisputeState.REFUND_REQUEST_STARTED_BY_PEER) {
                        if (valueSentToMe.compareTo(BigInteger.valueOf(0)) > 0) {
                            details = Res.get("funds.tx.refund", tradeId);
                        } else {
                            // We have spent the deposit tx outputs to the Haveno donation address to enable
                            // the refund process (refund agent -> reimbursement). As the funds have left our wallet
                            // already when funding the deposit tx we show 0 BTC as amount.
                            // Confirmation is not known from the BitcoinJ side (not 100% clear why) as no funds
                            // left our wallet nor we received funds. So we set indicator invisible.
                            amount = BigInteger.valueOf(0);
                            details = Res.get("funds.tx.collateralForRefund", tradeId);
                            initialTxConfidenceVisibility = false;
                        }
                    } else {
                        details = Res.get("funds.tx.unknown", tradeId);
                    }
                }
            }
        } else {
            if (amount.compareTo(BigInteger.valueOf(0)) == 0) {
                details = Res.get("funds.tx.noFundsFromDispute");
            }
        }

        // get tx date/time
        Long timestamp = tx.getBlock() == null ? System.currentTimeMillis() : tx.getBlock().getTimestamp() * 1000l;
        this.date = new Date(timestamp);
        dateString = DisplayUtils.formatDateTime(date);

        // confidence
        lazyFieldsSupplier = Suppliers.memoize(() -> new LazyFields() {{
            txConfidenceIndicator = new TxConfidenceIndicator();
            txConfidenceIndicator.setId("funds-confidence");
            tooltip = new Tooltip(Res.get("shared.notUsedYet"));
            txConfidenceIndicator.setProgress(0);
            txConfidenceIndicator.setTooltip(tooltip);
            txConfidenceIndicator.setVisible(initialTxConfidenceVisibility);

            GUIUtil.updateConfidence(tx, tooltip, txConfidenceIndicator);
            confirmations = tx.getNumConfirmations();
        }});

        // listen for tx updates
        walletListener = new MoneroWalletListener() {
            @Override
            public void onNewBlock(long height) {
                MoneroTxWallet tx = xmrWalletService.getWallet().getTxs(new MoneroTxQuery()
                        .setHash(txId)
                        .setInTxPool(confirmations > 0 ? false : null)).get(0);
                GUIUtil.updateConfidence(tx, lazy().tooltip, lazy().txConfidenceIndicator);
                confirmations = tx.getNumConfirmations();
            }
        };
        xmrWalletService.addWalletListener(walletListener);
    }

    public void cleanup() {
        if (walletListener != null) {
            xmrWalletService.removeWalletListener(walletListener);
            walletListener = null;
        }
    }

    public TxConfidenceIndicator getTxConfidenceIndicator() {
        return lazy().txConfidenceIndicator;
    }

    public final String getDateString() {
        return dateString;
    }

    public String getAmountStr() {
        return HavenoUtils.formatXmr(amount);
    }

    public BigInteger getAmount() {
        return amount;
    }

    public String getAddressString() {
        return addressString;
    }

    public String getDirection() {
        return direction;
    }

    public String getTxId() {
        return txId;
    }

    public boolean getReceived() {
        return received;
    }

    public String getDetails() {
        return details;
    }

    public boolean getDetailsAvailable() {
        return detailsAvailable;
    }

    public Date getDate() {
        return date;
    }

    @Nullable
    public Tradable getTradable() {
        return tradable;
    }

    public long getNumConfirmations() {
        return confirmations;
    }

    public String getMemo() {
        return memo;
    }
}
