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
import haveno.core.payment.AustraliaPayidAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.AustraliaPayidAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.AustraliaPayidValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class AustraliaPayidForm extends PaymentMethodForm {
    private final AustraliaPayidAccount australiaPayidAccount;
    private final AustraliaPayidValidator australiaPayidValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((AustraliaPayidAccountPayload) paymentAccountPayload).getBankAccountName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.payid"),
                ((AustraliaPayidAccountPayload) paymentAccountPayload).getPayid());
        return gridRow;
    }

    public AustraliaPayidForm(PaymentAccount paymentAccount,
                              AccountAgeWitnessService accountAgeWitnessService,
                              AustraliaPayidValidator australiaPayidValidator,
                              InputValidator inputValidator,
                              GridPane gridPane,
                              int gridRow,
                              CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.australiaPayidAccount = (AustraliaPayidAccount) paymentAccount;
        this.australiaPayidValidator = australiaPayidValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            australiaPayidAccount.setBankAccountName(newValue);
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.payid"));
        mobileNrInputTextField.setValidator(australiaPayidValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            australiaPayidAccount.setPayid(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = australiaPayidAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(australiaPayidAccount.getPayid());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(australiaPayidAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.payid"),
                australiaPayidAccount.getPayid());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                australiaPayidAccount.getBankAccountName()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = australiaPayidAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && australiaPayidValidator.validate(australiaPayidAccount.getPayid()).isValid
                && inputValidator.validate(australiaPayidAccount.getBankAccountName()).isValid
                && australiaPayidAccount.getTradeCurrencies().size() > 0);
    }
}
