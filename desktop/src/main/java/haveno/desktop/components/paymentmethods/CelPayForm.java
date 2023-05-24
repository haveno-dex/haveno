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

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.CelPayAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.CelPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.EmailValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

public class CelPayForm extends PaymentMethodForm {
    private final CelPayAccount account;
    private final EmailValidator validator = new EmailValidator();

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.email"),
                ((CelPayAccountPayload) paymentAccountPayload).getEmail());
        return gridRow;
    }

    public CelPayForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                       InputValidator inputValidator, GridPane gridPane,
                       int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (CelPayAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField emailInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.email"));
        emailInputTextField.setValidator(validator);
        emailInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setEmail(newValue.trim());
            updateFromInputs();
        });

        addCurrenciesGrid(true);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    private void addCurrenciesGrid(boolean isEditable) {
        FlowPane flowPane = FormBuilder.addTopLabelFlowPane(gridPane, ++gridRow,
                Res.get("payment.celpay.supportedCurrenciesForReceiver"), 20, 20).second;

        if (isEditable) {
            flowPane.setId("flow-pane-checkboxes-bg");
        } else {
            flowPane.setId("flow-pane-checkboxes-non-editable-bg");
        }

        account.getSupportedCurrencies().forEach(currency ->
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, currency, account));
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(account.getEmail());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(account.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"),
                account.getEmail()).second;
        field.setMouseTransparent(false);
        addLimitations(true);
        addCurrenciesGrid(false);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && account.getEmail() != null
                && validator.validate(account.getEmail()).isValid
                && account.getTradeCurrencies().size() > 0);
    }
}
