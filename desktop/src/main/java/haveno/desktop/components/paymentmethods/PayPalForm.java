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

package haveno.desktop.components.paymentmethods;

import com.jfoenix.controls.JFXTextArea;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.PayPalAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PayPalAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.EmailOrMobileNrOrUsernameValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.Layout;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelFlowPane;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;

public class PayPalForm extends PaymentMethodForm {
    private final PayPalAccount paypalAccount;
    private final EmailOrMobileNrOrUsernameValidator paypalValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.email.mobile.username"), ((PayPalAccountPayload) paymentAccountPayload).getEmailOrMobileNrOrUsername());

        PayPalAccountPayload payId = (PayPalAccountPayload) paymentAccountPayload;
        TextArea textExtraInfo = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        textExtraInfo.setMinHeight(70);
        textExtraInfo.setEditable(false);
        textExtraInfo.setText(payId.getExtraInfo());

        return gridRow;
    }

    public PayPalForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
            EmailOrMobileNrOrUsernameValidator paypalValidator, InputValidator inputValidator, GridPane gridPane,
            int gridRow,
            CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.paypalAccount = (PayPalAccount) paymentAccount;
        this.paypalValidator = paypalValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile.username"));
        mobileNrInputTextField.setValidator(paypalValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            paypalAccount.setEmailOrMobileNrOrUsername(newValue.trim());
            updateFromInputs();
        });

        TextArea extraTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.shared.optionalExtra"), Res.get("payment.shared.extraInfo.prompt")).second;
        extraTextArea.setMinHeight(70);
        ((JFXTextArea) extraTextArea).setLabelFloat(false);
        extraTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            paypalAccount.setExtraInfo(newValue);
            updateFromInputs();
        });

        addCurrenciesGrid(true);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    private void addCurrenciesGrid(boolean isEditable) {
        FlowPane flowPane = addTopLabelFlowPane(gridPane, ++gridRow,
                Res.get("payment.supportedCurrencies"), Layout.FLOATING_LABEL_DISTANCE * 3,
                Layout.FLOATING_LABEL_DISTANCE * 3).second;

        if (isEditable)
            flowPane.setId("flow-pane-checkboxes-bg");
        else
            flowPane.setId("flow-pane-checkboxes-non-editable-bg");

        paypalAccount.getSupportedCurrencies().forEach(e ->
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, e, paypalAccount));
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(paypalAccount.getEmailOrMobileNrOrUsername());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(paypalAccount.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile.username"),
                paypalAccount.getEmailOrMobileNrOrUsername()).second;

        TextArea textAreaExtra = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        textAreaExtra.setText(paypalAccount.getExtraInfo());
        textAreaExtra.setMinHeight(70);
        textAreaExtra.setEditable(false);

        field.setMouseTransparent(false);
        addLimitations(true);
        addCurrenciesGrid(false);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && paypalValidator.validate(paypalAccount.getEmailOrMobileNrOrUsername()).isValid
                && paypalAccount.getTradeCurrencies().size() > 0);
    }
}
