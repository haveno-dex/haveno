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
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PopmoneyAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PopmoneyAccountPayload;
import haveno.core.payment.validation.PopmoneyValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class PopmoneyForm extends PaymentMethodForm {
    private final PopmoneyAccount account;
    private final PopmoneyValidator validator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((PopmoneyAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.popmoney.accountId"), ((PopmoneyAccountPayload) paymentAccountPayload).getAccountId());
        return gridRow;
    }

    public PopmoneyForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, PopmoneyValidator popmoneyValidator, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (PopmoneyAccount) paymentAccount;
        this.validator = popmoneyValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setHolderName(newValue.trim());
            updateFromInputs();
        });

        InputTextField accountIdInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.popmoney.accountId"));
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
        setAccountNameWithString(account.getAccountId());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"), Res.get(account.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                account.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.popmoney.accountId"), account.getAccountId()).second;
        field.setMouseTransparent(false);
        final TradeCurrency singleTradeCurrency = account.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(account.getHolderName()).isValid
                && validator.validate(account.getAccountId()).isValid
                && account.getTradeCurrencies().size() > 0);
    }
}
