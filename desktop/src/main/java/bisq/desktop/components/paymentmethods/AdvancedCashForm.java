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

package bisq.desktop.components.paymentmethods;

import bisq.desktop.components.InputTextField;
import bisq.desktop.util.FormBuilder;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.locale.Res;
import bisq.core.payment.AdvancedCashAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.AdvancedCashAccountPayload;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.validation.AdvancedCashValidator;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.validation.InputValidator;

import bisq.common.util.Tuple2;

import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import static bisq.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static bisq.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static bisq.desktop.util.FormBuilder.addTopLabelFlowPane;

@Deprecated
public class AdvancedCashForm extends PaymentMethodForm {
    private final AdvancedCashAccount advancedCashAccount;
    private final AdvancedCashValidator advancedCashValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.wallet"),
                ((AdvancedCashAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }

    public AdvancedCashForm(PaymentAccount paymentAccount,
                            AccountAgeWitnessService accountAgeWitnessService,
                            AdvancedCashValidator advancedCashValidator,
                            InputValidator inputValidator,
                            GridPane gridPane,
                            int gridRow,
                            CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.advancedCashAccount = (AdvancedCashAccount) paymentAccount;
        this.advancedCashValidator = advancedCashValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField accountNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.wallet"));
        accountNrInputTextField.setValidator(advancedCashValidator);
        accountNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            advancedCashAccount.setAccountNr(newValue);
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
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, e, advancedCashAccount));
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(advancedCashAccount.getAccountNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(advancedCashAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.wallet"),
                advancedCashAccount.getAccountNr());
        addLimitations(true);
        addCurrenciesGrid(false);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && advancedCashValidator.validate(advancedCashAccount.getAccountNr()).isValid
                && advancedCashAccount.getTradeCurrencies().size() > 0);
    }

}
