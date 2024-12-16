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
import haveno.core.payment.VenmoAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.VenmoAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.EmailOrMobileNrOrUsernameValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class VenmoForm extends PaymentMethodForm {
    private final VenmoAccount venmoAccount;
    private final EmailOrMobileNrOrUsernameValidator venmoValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.email.mobile.username"), ((VenmoAccountPayload) paymentAccountPayload).getEmailOrMobileNrOrUsername());
        return gridRow;
    }

    public VenmoForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
            EmailOrMobileNrOrUsernameValidator venmoValidator, InputValidator inputValidator, GridPane gridPane,
            int gridRow,
            CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.venmoAccount = (VenmoAccount) paymentAccount;
        this.venmoValidator = venmoValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile.username"));
        mobileNrInputTextField.setValidator(venmoValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            venmoAccount.setNameOrUsernameOrEmailOrMobileNr(newValue.trim());
            updateFromInputs();
        });
        final TradeCurrency singleTradeCurrency = venmoAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(venmoAccount.getNameOrUsernameOrEmailOrMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(venmoAccount.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile.username"),
                venmoAccount.getNameOrUsernameOrEmailOrMobileNr()).second;
        field.setMouseTransparent(false);
        final TradeCurrency singleTradeCurrency = venmoAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && venmoValidator.validate(venmoAccount.getNameOrUsernameOrEmailOrMobileNr()).isValid
                && venmoAccount.getTradeCurrencies().size() > 0);
    }
}
