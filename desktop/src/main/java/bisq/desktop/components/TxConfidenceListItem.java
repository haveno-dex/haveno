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

package bisq.desktop.components;

import bisq.desktop.components.indicator.TxConfidenceIndicator;
import bisq.desktop.util.GUIUtil;

import bisq.core.btc.listeners.TxConfidenceListener;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;

import javafx.scene.control.Tooltip;

import lombok.Data;


@Data
public class TxConfidenceListItem {
    protected final String txId;
    protected int confirmations = 0;
    protected TxConfidenceIndicator txConfidenceIndicator;
    protected TxConfidenceListener txConfidenceListener;

    protected TxConfidenceListItem(Transaction transaction) {

        txId = transaction.getTxId().toString();
        txConfidenceIndicator = new TxConfidenceIndicator();
        txConfidenceIndicator.setId("funds-confidence");
        Tooltip tooltip = new Tooltip();
        txConfidenceIndicator.setProgress(0);
        txConfidenceIndicator.setPrefSize(24, 24);
        txConfidenceIndicator.setTooltip(tooltip);

        txConfidenceListener = new TxConfidenceListener(txId) {
            @Override
            public void onTransactionConfidenceChanged(TransactionConfidence confidence) {
                updateConfidence(confidence, tooltip);
            }
        };
    }

    protected TxConfidenceListItem() {
        this.txId = null;
    }

    private void updateConfidence(TransactionConfidence confidence, Tooltip tooltip) {
        if (confidence != null) {
            GUIUtil.updateConfidence(confidence, tooltip, txConfidenceIndicator);
            confirmations = confidence.getDepthInBlocks();
        }
    }

    public void cleanup() {
    }
}

