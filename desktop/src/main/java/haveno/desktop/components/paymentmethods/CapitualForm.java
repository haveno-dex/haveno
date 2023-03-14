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

import haveno.common.util.Tuple2;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.CapitualAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.CapitualAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.CapitualValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelFlowPane;

public class CapitualForm extends PaymentMethodForm {
    private final CapitualAccount capitualAccount;
    private final CapitualValidator capitualValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.capitual.cap"),
                ((CapitualAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }

    public CapitualForm(PaymentAccount paymentAccount,
                        AccountAgeWitnessService accountAgeWitnessService,
                        CapitualValidator capitualValidator,
                        InputValidator inputValidator,
                        GridPane gridPane,
                        int gridRow,
                        CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.capitualAccount = (CapitualAccount) paymentAccount;
        this.capitualValidator = capitualValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField accountNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.capitual.cap"));
        accountNrInputTextField.setValidator(capitualValidator);
        accountNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            capitualAccount.setAccountNr(newValue);
            updateFromInputs();
        });

        addCurrenciesGrid(true);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    private void addCurrenciesGrid(boolean isEditable) {
        final Tuple2<Label, FlowPane> labelFlowPaneTuple2 = addTopLabelFlowPane(gridPane, ++gridRow, Res.get("payment.supportedCurrencies"), 0);

        FlowPane flowPane = labelFlowPaneTuple2.second;

        if (isEditable)
            flowPane.setId("flow-pane-checkboxes-bg");
        else
            flowPane.setId("flow-pane-checkboxes-non-editable-bg");

        paymentAccount.getSupportedCurrencies().forEach(e ->
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, e, capitualAccount));
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(capitualAccount.getAccountNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(capitualAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.capitual.cap"),
                capitualAccount.getAccountNr());
        addLimitations(true);
        addCurrenciesGrid(false);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && capitualValidator.validate(capitualAccount.getAccountNr()).isValid
                && !capitualAccount.getTradeCurrencies().isEmpty());
    }

}
