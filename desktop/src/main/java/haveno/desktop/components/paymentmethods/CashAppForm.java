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
import haveno.core.payment.CashAppAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.CashAppAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.EmailOrMobileNrOrCashtagValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class CashAppForm extends PaymentMethodForm {
    private final CashAppAccount cashAppAccount;
    private final EmailOrMobileNrOrCashtagValidator cashAppValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.email.mobile.cashtag"),
                ((CashAppAccountPayload) paymentAccountPayload).getEmailOrMobileNrOrCashtag());
        return gridRow;
    }

    public CashAppForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
            EmailOrMobileNrOrCashtagValidator cashAppValidator, InputValidator inputValidator, GridPane gridPane,
            int gridRow,
            CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.cashAppAccount = (CashAppAccount) paymentAccount;
        this.cashAppValidator = cashAppValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile.cashtag"));
        mobileNrInputTextField.setValidator(cashAppValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            cashAppAccount.setEmailOrMobileNrOrCashtag(newValue.trim());
            updateFromInputs();
        });
        final TradeCurrency singleTradeCurrency = cashAppAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(cashAppAccount.getEmailOrMobileNrOrCashtag());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email.mobile.cashtag"),
                cashAppAccount.getEmailOrMobileNrOrCashtag()).second;
        field.setMouseTransparent(false);
        final TradeCurrency singleTradeCurrency = cashAppAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && cashAppValidator.validate(cashAppAccount.getEmailOrMobileNrOrCashtag()).isValid
                && cashAppAccount.getTradeCurrencies().size() > 0);
    }
}
