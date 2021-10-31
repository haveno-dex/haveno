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

import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.support.dispute.DisputeSummaryVerification;

import haveno.core.locale.Res;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;

import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import javafx.geometry.HPos;
import javafx.geometry.Insets;

import lombok.extern.slf4j.Slf4j;

import static haveno.desktop.util.FormBuilder.addMultilineLabel;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

@Slf4j
public class VerifyDisputeResultSignatureWindow extends Overlay<VerifyDisputeResultSignatureWindow> {
    private TextArea textArea;
    private TextField resultTextField;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;

    public VerifyDisputeResultSignatureWindow(MediatorManager mediatorManager, RefundAgentManager refundAgentManager) {
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;

        type = Type.Attention;
    }

    public void show() {
        if (headLine == null)
            headLine = Res.get("support.sigCheck.popup.header");

        width = 1050;
        createGridPane();
        addHeadLine();
        addContent();
        addButtons();

        applyStyles();
        display();

        textArea.textProperty().addListener((observable, oldValue, newValue) -> {
            resultTextField.setText(DisputeSummaryVerification.verifySignature(newValue,
                    mediatorManager,
                    refundAgentManager));
        });
    }

    @Override
    protected void createGridPane() {
        gridPane = new GridPane();
        gridPane.setHgap(5);
        gridPane.setVgap(5);
        gridPane.setPadding(new Insets(64, 64, 64, 64));
        gridPane.setPrefWidth(width);

        ColumnConstraints columnConstraints1 = new ColumnConstraints();
        columnConstraints1.setHalignment(HPos.RIGHT);
        columnConstraints1.setHgrow(Priority.SOMETIMES);
        gridPane.getColumnConstraints().addAll(columnConstraints1);
    }

    private void addContent() {
        addMultilineLabel(gridPane, ++rowIndex, Res.get("support.sigCheck.popup.info"), 0, width);
        textArea = addTopLabelTextArea(gridPane, ++rowIndex, Res.get("support.sigCheck.popup.msg.label"),
                Res.get("support.sigCheck.popup.msg.prompt")).second;
        resultTextField = addTopLabelTextField(gridPane, ++rowIndex, Res.get("support.sigCheck.popup.result")).second;
    }
}
