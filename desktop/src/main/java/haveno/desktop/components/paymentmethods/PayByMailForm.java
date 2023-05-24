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

import com.jfoenix.controls.JFXTextArea;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PayByMailAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PayByMailAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.Layout;
import javafx.collections.FXCollections;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelTextFieldWithCopyIcon;

public class PayByMailForm extends PaymentMethodForm {
    private final PayByMailAccount payByMailAccount;
    private TextArea postalAddressTextArea;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        PayByMailAccountPayload cbm = (PayByMailAccountPayload) paymentAccountPayload;
        addTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1,
                Res.get("payment.account.owner"),
                cbm.getHolderName(),
                Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE);

        TextArea textAddress = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.postal.address"), "").second;
        textAddress.setMinHeight(70);
        textAddress.setEditable(false);
        textAddress.setText(cbm.getPostalAddress());

        TextArea textExtraInfo = addCompactTopLabelTextArea(gridPane, gridRow, 1, Res.get("payment.shared.extraInfo"), "").second;
        textExtraInfo.setMinHeight(70);
        textExtraInfo.setEditable(false);
        textExtraInfo.setText(cbm.getExtraInfo());
        return gridRow;
    }

    public PayByMailForm(PaymentAccount paymentAccount,
                                  AccountAgeWitnessService accountAgeWitnessService,
                                  InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.payByMailAccount = (PayByMailAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        addTradeCurrencyComboBox();
        currencyComboBox.setItems(FXCollections.observableArrayList(CurrencyUtil.getAllSortedTraditionalCurrencies()));

        InputTextField contactField = addInputTextField(gridPane, ++gridRow,
                Res.get("payment.payByMail.contact"));
        contactField.setPromptText(Res.get("payment.payByMail.contact.prompt"));
        contactField.setValidator(inputValidator);
        contactField.textProperty().addListener((ov, oldValue, newValue) -> {
            payByMailAccount.setContact(newValue);
            updateFromInputs();
        });

        postalAddressTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.postal.address"), "").second;
        postalAddressTextArea.setMinHeight(70);
        postalAddressTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            payByMailAccount.setPostalAddress(newValue);
            updateFromInputs();
        });

        TextArea extraTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.shared.optionalExtra"), Res.get("payment.payByMail.extraInfo.prompt")).second;
        extraTextArea.setMinHeight(70);
        ((JFXTextArea) extraTextArea).setLabelFloat(false);
        extraTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            payByMailAccount.setExtraInfo(newValue);
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(payByMailAccount.getContact());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(payByMailAccount.getPaymentMethod().getId()));

        TradeCurrency tradeCurrency = paymentAccount.getSingleTradeCurrency();
        String nameAndCode = tradeCurrency != null ? tradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);

        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.f2f.contact"),
                payByMailAccount.getContact());
        TextArea textArea = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.postal.address"), "").second;
        textArea.setText(payByMailAccount.getPostalAddress());
        textArea.setMinHeight(70);
        textArea.setEditable(false);

        TextArea textAreaExtra = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        textAreaExtra.setText(payByMailAccount.getExtraInfo());
        textAreaExtra.setMinHeight(70);
        textAreaExtra.setEditable(false);

        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && !payByMailAccount.getPostalAddress().isEmpty()
                && inputValidator.validate(payByMailAccount.getContact()).isValid
                && paymentAccount.getSingleTradeCurrency() != null);
    }
}
