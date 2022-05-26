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
import bisq.desktop.util.validation.AustraliaPayidValidator;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.locale.Res;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.AustraliaPayidAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.AustraliaPayidAccountPayload;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.validation.InputValidator;

import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static bisq.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static bisq.desktop.util.FormBuilder.addTopLabelTextField;

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
