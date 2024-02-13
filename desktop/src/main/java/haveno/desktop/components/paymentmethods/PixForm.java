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
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PixAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PixAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.Layout;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextFieldWithCopyIcon;

public class PixForm extends PaymentMethodForm {
    private final PixAccount account;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.pix.key"),
                ((PixAccountPayload) paymentAccountPayload).getPixKey(), Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE);
        return gridRow;
    }

    public PixForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                     InputValidator inputValidator, GridPane gridPane,
                     int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (PixAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        // this payment method is only for Brazil/BRL
        account.setSingleTradeCurrency(account.getSupportedCurrencies().get(0));
        CountryUtil.findCountryByCode("BR").ifPresent(c -> account.setCountry(c));

        gridRowFrom = gridRow + 1;

        InputTextField pixKeyInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.pix.key"));
        pixKeyInputTextField.setValidator(inputValidator);
        pixKeyInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setPixKey(newValue.trim());
            updateFromInputs();
        });

        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), account.getSingleTradeCurrency().getNameAndCode());
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), account.getCountry().name);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(account.getPixKey());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(account.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.pix.key"),
                account.getPixKey()).second;
        field.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), account.getSingleTradeCurrency().getNameAndCode());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), account.getCountry().name);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(account.getPixKey()).isValid);
    }
}
