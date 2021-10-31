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
import haveno.desktop.util.validation.AustraliaPayidValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.AustraliaPayid;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.AustraliaPayidPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class AustraliaPayidForm extends PaymentMethodForm {
    private final AustraliaPayid australiaPayid;
    private final AustraliaPayidValidator australiaPayidValidator;
    private InputTextField mobileNrInputTextField;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((AustraliaPayidPayload) paymentAccountPayload).getBankAccountName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.payid"),
                ((AustraliaPayidPayload) paymentAccountPayload).getPayid());
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
        this.australiaPayid = (AustraliaPayid) paymentAccount;
        this.australiaPayidValidator = australiaPayidValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            australiaPayid.setBankAccountName(newValue);
            updateFromInputs();
        });

        mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.payid"));
        mobileNrInputTextField.setValidator(australiaPayidValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            australiaPayid.setPayid(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = australiaPayid.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(mobileNrInputTextField.getText());
    }

    @Override
    public void addFormForDisplayAccount() {
        gridRowFrom = gridRow;
        addTopLabelTextField(gridPane, gridRow, Res.get("payment.account.name"),
                australiaPayid.getAccountName(), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(australiaPayid.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.payid"),
                australiaPayid.getPayid());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                australiaPayid.getBankAccountName()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = australiaPayid.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && australiaPayidValidator.validate(australiaPayid.getPayid()).isValid
                && inputValidator.validate(australiaPayid.getBankAccountName()).isValid
                && australiaPayid.getTradeCurrencies().size() > 0);
    }
}
