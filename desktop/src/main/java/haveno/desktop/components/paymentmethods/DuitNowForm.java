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
import haveno.core.payment.DuitNowAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.DuitNowAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.AccountNrValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import lombok.extern.slf4j.Slf4j;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

@Slf4j
public class DuitNowForm extends PaymentMethodForm {
    private final DuitNowAccount duitnowAccount;
    private final AccountNrValidator accountNrValidator;

    public DuitNowForm(PaymentAccount paymentAccount,
                    AccountAgeWitnessService accountAgeWitnessService,
                    InputValidator inputValidator,
                    GridPane gridPane,
                    int gridRow,
                    CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.duitnowAccount = (DuitNowAccount) paymentAccount;
        this.accountNrValidator = new AccountNrValidator("MY");
    }

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"),
                ((DuitNowAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.accountNr"),
                ((DuitNowAccountPayload) paymentAccountPayload).getAccountNr());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.bank.name"),
                ((DuitNowAccountPayload) paymentAccountPayload).getBankName());
        return gridRow;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner.fullname"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            duitnowAccount.setHolderName(newValue);
            updateFromInputs();
        });

        InputTextField accountNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.accountNr"));
        accountNrInputTextField.setValidator(accountNrValidator);
        accountNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            duitnowAccount.setAccountNr(newValue);
            updateFromInputs();
        });

        InputTextField bankNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.bank.name"));
        bankNameInputTextField.setValidator(inputValidator);
        bankNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            duitnowAccount.setBankName(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = duitnowAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(duitnowAccount.getAccountNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(duitnowAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"),
                duitnowAccount.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.accountNr"),
                duitnowAccount.getAccountNr()).second;
        field.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.bank.name"),
                duitnowAccount.getBankName());
        TradeCurrency singleTradeCurrency = duitnowAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(duitnowAccount.getHolderName()).isValid
                && accountNrValidator.validate(duitnowAccount.getAccountNr()).isValid
                && inputValidator.validate(duitnowAccount.getBankName()).isValid
                && duitnowAccount.getTradeCurrencies().size() > 0);
    }
}
