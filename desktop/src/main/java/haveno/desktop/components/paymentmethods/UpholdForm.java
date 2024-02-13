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

package haveno.desktop.components.paymentmethods;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.UpholdAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.UpholdAccountPayload;
import haveno.core.payment.validation.UpholdValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

public class UpholdForm extends PaymentMethodForm {
    private final UpholdAccount upholdAccount;
    private final UpholdValidator upholdValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        String accountOwner = ((UpholdAccountPayload) paymentAccountPayload).getAccountOwner();
        if (accountOwner.isEmpty()) {
            accountOwner = Res.get("payment.ask");
        }
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.owner"),
                accountOwner);

        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.uphold.accountId"),
                ((UpholdAccountPayload) paymentAccountPayload).getAccountId());

        return gridRow;
    }

    public UpholdForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                      UpholdValidator upholdValidator, InputValidator inputValidator, GridPane gridPane,
                      int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.upholdAccount = (UpholdAccount) paymentAccount;
        this.upholdValidator = upholdValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            upholdAccount.setAccountOwner(newValue);
            updateFromInputs();
        });

        InputTextField accountIdInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.uphold.accountId"));
        accountIdInputTextField.setValidator(upholdValidator);
        accountIdInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            upholdAccount.setAccountId(newValue.trim());
            updateFromInputs();
        });

        addCurrenciesGrid(true);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    private void addCurrenciesGrid(boolean isEditable) {

        FlowPane flowPane = FormBuilder.addTopLabelFlowPane(gridPane, ++gridRow,
                Res.get("payment.supportedCurrencies"), 0).second;

        if (isEditable)
            flowPane.setId("flow-pane-checkboxes-bg");
        else
            flowPane.setId("flow-pane-checkboxes-non-editable-bg");

        paymentAccount.getSupportedCurrencies().forEach(e ->
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, e, upholdAccount));
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(upholdAccount.getAccountId());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(upholdAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                Res.get(upholdAccount.getAccountOwner()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.uphold.accountId"),
                upholdAccount.getAccountId()).second;
        field.setMouseTransparent(false);
        addLimitations(true);
        addCurrenciesGrid(false);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && upholdValidator.validate(upholdAccount.getAccountId()).isValid
                && upholdAccount.getTradeCurrencies().size() > 0);
    }
}
