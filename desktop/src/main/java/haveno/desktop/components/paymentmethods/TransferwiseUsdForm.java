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
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.TransferwiseUsdAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.TransferwiseUsdAccountPayload;
import haveno.core.payment.validation.EmailValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.Layout;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;

import static haveno.common.util.Utilities.cleanString;
import static haveno.desktop.util.FormBuilder.*;

public class TransferwiseUsdForm extends PaymentMethodForm {
    private final TransferwiseUsdAccount account;
    private final LengthValidator addressValidator = new LengthValidator(0, 100);
    private final EmailValidator emailValidator = new EmailValidator();

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {

        addTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.account.owner"),
                ((TransferwiseUsdAccountPayload) paymentAccountPayload).getHolderName(),
                Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE);

        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, 1, Res.get("payment.email"),
                ((TransferwiseUsdAccountPayload) paymentAccountPayload).getEmail());

        String address = ((TransferwiseUsdAccountPayload) paymentAccountPayload).getBeneficiaryAddress();
        if (address.length() > 0) {
            TextArea textAddress = addCompactTopLabelTextArea(gridPane, gridRow, 0, Res.get("payment.account.address"), "").second;
            textAddress.setMinHeight(70);
            textAddress.setEditable(false);
            textAddress.setText(address);
        }

        return gridRow;
    }

    public TransferwiseUsdForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                      InputValidator inputValidator, GridPane gridPane,
                      int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (TransferwiseUsdAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {

        CountryUtil.findCountryByCode("US").ifPresent(account::setCountry);

        gridRowFrom = gridRow + 1;

        InputTextField emailField = addInputTextField(gridPane, ++gridRow, Res.get("payment.email"));
        emailField.setValidator(emailValidator);
        emailField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setEmail(newValue.trim());
            updateFromInputs();
        });

        InputTextField holderNameField = addInputTextField(gridPane, ++gridRow, Res.get("payment.account.owner"));
        holderNameField.setValidator(inputValidator);
        holderNameField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setHolderName(newValue.trim());
            updateFromInputs();
        });

        String addressLabel = Res.get("payment.account.owner.address") + Res.get("payment.transferwiseUsd.address");
        TextArea addressTextArea = addTopLabelTextArea(gridPane, ++gridRow, addressLabel, addressLabel).second;
        addressTextArea.setMinHeight(70);
        addressTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setBeneficiaryAddress(newValue.trim());
            updateFromInputs();
        });

        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), account.getSingleTradeCurrency().getNameAndCode());
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), account.getCountry().name);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(account.getHolderName());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(account.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"), account.getEmail());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"), account.getHolderName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.address"), cleanString(account.getBeneficiaryAddress()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), account.getSingleTradeCurrency().getNameAndCode());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), account.getCountry().name);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && emailValidator.validate(account.getEmail()).isValid
                && inputValidator.validate(account.getHolderName()).isValid
                && addressValidator.validate(account.getBeneficiaryAddress()).isValid
        );
    }
}
