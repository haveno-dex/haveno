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

package haveno.desktop.main.overlays.windows;

import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.main.funds.transactions.TransactionsListItem;
import haveno.desktop.main.overlays.Overlay;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import monero.wallet.model.MoneroTxWallet;

import static haveno.desktop.util.FormBuilder.addConfirmationLabelLabel;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addLabelTxIdTextField;
import static haveno.desktop.util.FormBuilder.addMultilineLabel;

import java.math.BigInteger;

import com.google.inject.Inject;

public class TxDetailsWindow extends Overlay<TxDetailsWindow> {

    private XmrWalletService xmrWalletService;
    private TransactionsListItem item;

    @Inject
    public TxDetailsWindow(XmrWalletService xmrWalletService) {
        this.xmrWalletService = xmrWalletService;
    }

    public void show(TransactionsListItem item) {
        this.item = item;
        rowIndex = -1;
        width = 918;
        if (headLine == null)
            headLine = Res.get("txDetailsWindow.headline");
        createGridPane();
        gridPane.setHgap(15);
        addHeadLine();
        addContent();
        addButtons();
        applyStyles();
        display();
    }

    protected void addContent() {
        MoneroTxWallet tx = item.getTx();
        String memo = tx.getNote();
        String txKey = null;
        boolean isOutgoing = tx.getOutgoingTransfer() != null;
        if (isOutgoing) {
            try {
                txKey = xmrWalletService.getWallet().getTxKey(tx.getHash());
            } catch (Exception e) {
                // TODO (monero-java): wallet.getTxKey() should return null if key does not exist instead of throwing exception
            }
        }

        // add sent or received note
        String resKey = isOutgoing ? "txDetailsWindow.xmr.noteSent" : "txDetailsWindow.xmr.noteReceived";
        GridPane.setColumnSpan(addMultilineLabel(gridPane, ++rowIndex, Res.get(resKey), 0), 2);
        Region spacer = new Region();
        spacer.setMinHeight(15);
        gridPane.add(spacer, 0, ++rowIndex);

        // add tx fields
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.dateTime"), item.getDateString());
        BigInteger amount;
        if (isOutgoing) {
            addConfirmationLabelTextFieldWithCopyIcon(gridPane, ++rowIndex, Res.get("txDetailsWindow.sentTo"), item.getAddressString());
            amount = tx.getOutgoingAmount();
        } else {
            addConfirmationLabelTextFieldWithCopyIcon(gridPane, ++rowIndex, Res.get("txDetailsWindow.receivedWith"), item.getAddressString());
            amount = tx.getIncomingAmount();
        }
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.amount"),  HavenoUtils.formatXmr(amount));
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.txFee"), HavenoUtils.formatXmr(tx.getFee()));
        if (memo != null && !"".equals(memo)) addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("funds.withdrawal.memoLabel"), memo);
        if (txKey != null && !"".equals(txKey)) addConfirmationLabelTextFieldWithCopyIcon(gridPane, ++rowIndex, Res.get("txDetailsWindow.txKey"), txKey);
        addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("txDetailsWindow.txId"), tx.getHash());
    }
}
