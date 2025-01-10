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

import com.jfoenix.controls.JFXTextArea;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.ZelleAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.EmailOrMobileNrValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class ZelleForm extends PaymentMethodForm {
    private final ZelleAccount zelleAccount;
    private final EmailOrMobileNrValidator zelleValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((ZelleAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.email.mobile"),
                ((ZelleAccountPayload) paymentAccountPayload).getEmailOrMobileNr());
        TextArea textExtraInfo = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        textExtraInfo.setMinHeight(70);
        textExtraInfo.setEditable(false);
        textExtraInfo.setText(((ZelleAccountPayload) paymentAccountPayload).getExtraInfo());
        return gridRow;
    }

    public ZelleForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, EmailOrMobileNrValidator zelleValidator, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.zelleAccount = (ZelleAccount) paymentAccount;
        this.zelleValidator = zelleValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            zelleAccount.setHolderName(newValue.trim());
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile"));
        mobileNrInputTextField.setValidator(zelleValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            zelleAccount.setEmailOrMobileNr(newValue.trim());
            updateFromInputs();
        });
        final TradeCurrency singleTradeCurrency = zelleAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);

        TextArea extraTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.shared.optionalExtra"), Res.get("payment.shared.extraInfo.prompt")).second;
        extraTextArea.setMinHeight(70);
        ((JFXTextArea) extraTextArea).setLabelFloat(false);
        extraTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            zelleAccount.setExtraInfo(newValue);
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(zelleAccount.getEmailOrMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(zelleAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                zelleAccount.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email.mobile"),
                zelleAccount.getEmailOrMobileNr()).second;
        field.setMouseTransparent(false);
        final TradeCurrency singleTradeCurrency = zelleAccount.getSingleTradeCurrency();
        final String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        TextArea textAreaExtra = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        textAreaExtra.setText(zelleAccount.getExtraInfo());
        textAreaExtra.setMinHeight(70);
        textAreaExtra.setEditable(false);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && zelleValidator.validate(zelleAccount.getEmailOrMobileNr()).isValid
                && inputValidator.validate(zelleAccount.getHolderName()).isValid
                && zelleAccount.getTradeCurrencies().size() > 0);
    }
}
