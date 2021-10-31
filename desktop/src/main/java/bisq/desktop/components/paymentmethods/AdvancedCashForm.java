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

import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.Layout;
import haveno.desktop.util.validation.AdvancedCashValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.payment.AdvancedCashAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.AdvancedCashAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

import haveno.common.util.Tuple2;

import org.apache.commons.lang3.StringUtils;

import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelFlowPane;

@Deprecated
public class AdvancedCashForm extends PaymentMethodForm {
    private static final Logger log = LoggerFactory.getLogger(AdvancedCashForm.class);

    private final AdvancedCashAccount advancedCashAccount;
    private final AdvancedCashValidator advancedCashValidator;
    private InputTextField accountNrInputTextField;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.wallet"),
                ((AdvancedCashAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }

    public AdvancedCashForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, AdvancedCashValidator advancedCashValidator,
                     InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.advancedCashAccount = (AdvancedCashAccount) paymentAccount;
        this.advancedCashValidator = advancedCashValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        accountNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.wallet"));
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

        CurrencyUtil.getAllAdvancedCashCurrencies().stream().forEach(e ->
                fillUpFlowPaneWithCurrencies(isEditable, flowPane, e, advancedCashAccount));
    }

    @Override
    protected void autoFillNameTextField() {
        if (useCustomAccountNameToggleButton != null && !useCustomAccountNameToggleButton.isSelected()) {
            String accountNr = accountNrInputTextField.getText();
            accountNr = StringUtils.abbreviate(accountNr, 9);
            String method = Res.get(paymentAccount.getPaymentMethod().getId());
            accountNameTextField.setText(method.concat(": ").concat(accountNr));
        }
    }

    @Override
    public void addFormForDisplayAccount() {
        gridRowFrom = gridRow;
        addCompactTopLabelTextField(gridPane, gridRow, Res.get("payment.account.name"),
                advancedCashAccount.getAccountName(), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
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
