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
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.SwishAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.SwishAccountPayload;
import haveno.core.payment.validation.SwishValidator;
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
public class SwishForm extends PaymentMethodForm {
    private final SwishAccount swishAccount;
    private final SwishValidator swishValidator;

    public SwishForm(PaymentAccount paymentAccount,
                     AccountAgeWitnessService accountAgeWitnessService,
                     SwishValidator swishValidator,
                     InputValidator inputValidator,
                     GridPane gridPane,
                     int gridRow,
                     CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.swishAccount = (SwishAccount) paymentAccount;
        this.swishValidator = swishValidator;
    }

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((SwishAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.mobile"),
                ((SwishAccountPayload) paymentAccountPayload).getMobileNr());
        return gridRow;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            swishAccount.setHolderName(newValue);
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.mobile"));
        mobileNrInputTextField.setValidator(swishValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            swishAccount.setMobileNr(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = swishAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(swishAccount.getMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(swishAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                swishAccount.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.mobile"),
                swishAccount.getMobileNr()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = swishAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        if (swishValidator.validate(swishAccount.getMobileNr()).isValid) {
            swishAccount.setMobileNr(swishValidator.getNormalizedPhoneNumber());
        }
        allInputsValid.set(isAccountNameValid()
                && swishValidator.validate(swishAccount.getMobileNr()).isValid
                && inputValidator.validate(swishAccount.getHolderName()).isValid
                && swishAccount.getTradeCurrencies().size() > 0);
    }
}
