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
import haveno.desktop.util.validation.MoneyBeamValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.MoneyBeamAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.MoneyBeamAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class MoneyBeamForm extends PaymentMethodForm {
    private final MoneyBeamAccount account;
    private final MoneyBeamValidator validator;
    private InputTextField accountIdInputTextField;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.moneyBeam.accountId"), ((MoneyBeamAccountPayload) paymentAccountPayload).getAccountId());
        return gridRow;
    }

    public MoneyBeamForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, MoneyBeamValidator moneyBeamValidator, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (MoneyBeamAccount) paymentAccount;
        this.validator = moneyBeamValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        accountIdInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.moneyBeam.accountId"));
        accountIdInputTextField.setValidator(validator);
        accountIdInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setAccountId(newValue.trim());
            updateFromInputs();
        });

        final TradeCurrency singleTradeCurrency = account.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(accountIdInputTextField.getText());
    }

    @Override
    public void addFormForDisplayAccount() {
        gridRowFrom = gridRow;
        addTopLabelTextField(gridPane, gridRow, Res.get("payment.account.name"), account.getAccountName(), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"), Res.get(account.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.moneyBeam.accountId"), account.getAccountId()).second;
        field.setMouseTransparent(false);
        final TradeCurrency singleTradeCurrency = account.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && validator.validate(account.getAccountId()).isValid
                && account.getTradeCurrencies().size() > 0);
    }
}
