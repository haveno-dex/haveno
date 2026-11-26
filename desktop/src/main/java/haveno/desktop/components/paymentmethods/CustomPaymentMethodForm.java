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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.payment.CustomPaymentMethodAccount;
import haveno.core.payment.CustomPaymentMethodUtil;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.CustomPaymentMethodAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.CustomPaymentMethodNameValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import javafx.collections.FXCollections;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;

public class CustomPaymentMethodForm extends PaymentMethodForm {
    private final CustomPaymentMethodAccount customPaymentMethodAccount;
    private final CustomPaymentMethodNameValidator nameValidator = new CustomPaymentMethodNameValidator();

    // the name is taken from the offer, which both traders agreed to
    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload, Offer offer) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, 0, Res.get("payment.custom.name"),
                offer.getCustomPaymentMethodName());

        TextArea accountDataTextArea = addCompactTopLabelTextArea(gridPane, gridRow, 1, Res.get("payment.custom.accountData"), "").second;
        accountDataTextArea.setMinHeight(70);
        accountDataTextArea.setEditable(false);
        accountDataTextArea.setText(((CustomPaymentMethodAccountPayload) paymentAccountPayload).getAccountData());
        return gridRow;
    }

    public CustomPaymentMethodForm(PaymentAccount paymentAccount,
                                   AccountAgeWitnessService accountAgeWitnessService,
                                   InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.customPaymentMethodAccount = (CustomPaymentMethodAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        addTradeCurrencyComboBox();
        currencyComboBox.setItems(FXCollections.observableArrayList(CurrencyUtil.getAllSortedFiatCurrencies()));

        InputTextField nameField = addInputTextField(gridPane, ++gridRow, Res.get("payment.custom.name"));
        nameField.setPromptText(Res.get("payment.custom.name.prompt", CustomPaymentMethodUtil.MAX_NAME_LENGTH));
        nameField.setValidator(nameValidator);
        nameField.textProperty().addListener((ov, oldValue, newValue) -> {
            customPaymentMethodAccount.setPaymentMethodName(newValue);
            updateFromInputs();
        });

        TextArea accountDataTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.custom.accountData"),
                Res.get("payment.custom.accountData.prompt", CustomPaymentMethodUtil.MAX_ACCOUNT_DATA_LENGTH)).second;
        accountDataTextArea.setMinHeight(100);
        ((JFXTextArea) accountDataTextArea).setLabelFloat(false);
        accountDataTextArea.setTextFormatter(new TextFormatter<String>(CustomPaymentMethodForm::cutToMaxLength));
        accountDataTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            customPaymentMethodAccount.setAccountData(newValue);
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    // typed or pasted text is cut so the account data stays within its max length
    private static TextFormatter.Change cutToMaxLength(TextFormatter.Change change) {
        int excess = change.getControlNewText().length() - CustomPaymentMethodUtil.MAX_ACCOUNT_DATA_LENGTH;
        if (change.isContentChange() && excess > 0) {
            String text = change.getText();
            change.setText(text.substring(0, Math.max(0, text.length() - excess)));
        }
        return change;
    }

    // the full name and the currency tell custom payment method accounts apart
    @Override
    protected void autoFillNameTextField() {
        if (useCustomAccountNameToggleButton != null && !useCustomAccountNameToggleButton.isSelected()) {
            TradeCurrency tradeCurrency = paymentAccount.getSingleTradeCurrency();
            accountNameTextField.setText(paymentAccount.getPaymentMethod().getShortName() + ": " +
                    customPaymentMethodAccount.getPaymentMethodName() + (tradeCurrency == null ? "" : " (" + tradeCurrency.getCode() + ")"));
        }
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(customPaymentMethodAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.custom.name"),
                customPaymentMethodAccount.getPaymentMethodName());

        TradeCurrency tradeCurrency = paymentAccount.getSingleTradeCurrency();
        String nameAndCode = tradeCurrency != null ? tradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);

        TextArea accountDataTextArea = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.custom.accountData"), "").second;
        accountDataTextArea.setText(customPaymentMethodAccount.getAccountData());
        accountDataTextArea.setMinHeight(100);
        accountDataTextArea.setEditable(false);

        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && nameValidator.validate(customPaymentMethodAccount.getPaymentMethodName()).isValid
                && CustomPaymentMethodUtil.isValidAccountData(customPaymentMethodAccount.getAccountData())
                && paymentAccount.getSingleTradeCurrency() != null);
    }
}
