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

package bisq.desktop.main.overlays.windows;

import bisq.desktop.components.TxIdTextField;
import bisq.desktop.main.overlays.Overlay;

import bisq.core.locale.Res;

import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

import static bisq.desktop.util.FormBuilder.*;

public class TxDetails extends Overlay<TxDetails> {

    protected String txId, address, amount, fee, memo;
    protected TxIdTextField txIdTextField;

    public TxDetails(String txId, String address, String amount, String fee, String memo) {
        type = Type.Attention;
        this.txId = txId;
        this.address = address;
        this.amount = amount;
        this.fee = fee;
        this.memo = memo;
    }

    public void show() {
        rowIndex = -1;
        width = 918;
        if (headLine == null)
            headLine = Res.get("txDetailsWindow.headline");
        createGridPane();
        gridPane.setHgap(15);
        addHeadLine();
        addContent();
        addButtons();
        addDontShowAgainCheckBox();
        applyStyles();
        display();
    }

    protected void addContent() {
        GridPane.setColumnSpan(
                addMultilineLabel(gridPane, ++rowIndex, Res.get("txDetailsWindow.xmr.note"), 0), 2);
        Region spacer = new Region();
        spacer.setMinHeight(20);
        gridPane.add(spacer, 0, ++rowIndex);
        addConfirmationLabelTextFieldWithCopyIcon(gridPane, ++rowIndex, Res.get("txDetailsWindow.sentTo"), address);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.amount"), amount);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.txFee"), fee);
        if (memo != null && !"".equals(memo)) addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("funds.withdrawal.memoLabel"), memo);
        txIdTextField = addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("txDetailsWindow.txId"), txId).second;
    }
}
