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
import haveno.core.locale.BankUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PagoMovilAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PagoMovilAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.PagoMovilValidator;
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
public class PagoMovilForm extends PaymentMethodForm {
    private final PagoMovilAccount pagoMovilAccount;
    private final PagoMovilValidator pagoMovilValidator;

    public PagoMovilForm(PaymentAccount paymentAccount,
                         AccountAgeWitnessService accountAgeWitnessService,
                         PagoMovilValidator pagoMovilValidator,
                         InputValidator inputValidator,
                         GridPane gridPane,
                         int gridRow,
                         CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.pagoMovilAccount = (PagoMovilAccount) paymentAccount;
        this.pagoMovilValidator = pagoMovilValidator;
    }

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"),
                ((PagoMovilAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.mobile"),
                ((PagoMovilAccountPayload) paymentAccountPayload).getMobileNr());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, BankUtil.getHolderIdLabel("VE"),
                ((PagoMovilAccountPayload) paymentAccountPayload).getHolderTaxId());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.bank.name"),
                ((PagoMovilAccountPayload) paymentAccountPayload).getBankName());
        return gridRow;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner.fullname"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            pagoMovilAccount.setHolderName(newValue);
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.mobile"));
        mobileNrInputTextField.setValidator(pagoMovilValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            pagoMovilAccount.setMobileNr(newValue);
            updateFromInputs();
        });

        InputTextField holderTaxIdInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                BankUtil.getHolderIdLabel("VE"));
        holderTaxIdInputTextField.setValidator(inputValidator);
        holderTaxIdInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            pagoMovilAccount.setHolderTaxId(newValue);
            updateFromInputs();
        });

        InputTextField bankNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.bank.name"));
        bankNameInputTextField.setValidator(inputValidator);
        bankNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            pagoMovilAccount.setBankName(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = pagoMovilAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(pagoMovilAccount.getMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(pagoMovilAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"),
                pagoMovilAccount.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.mobile"),
                pagoMovilAccount.getMobileNr()).second;
        field.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, BankUtil.getHolderIdLabel("VE"),
                pagoMovilAccount.getHolderTaxId());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.bank.name"),
                pagoMovilAccount.getBankName());
        TradeCurrency singleTradeCurrency = pagoMovilAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        if (pagoMovilValidator.validate(pagoMovilAccount.getMobileNr()).isValid) {
            pagoMovilAccount.setMobileNr(pagoMovilValidator.getNormalizedPhoneNumber());
        }
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(pagoMovilAccount.getHolderName()).isValid
                && pagoMovilValidator.validate(pagoMovilAccount.getMobileNr()).isValid
                && inputValidator.validate(pagoMovilAccount.getHolderTaxId()).isValid
                && inputValidator.validate(pagoMovilAccount.getBankName()).isValid
                && pagoMovilAccount.getTradeCurrencies().size() > 0);
    }
}
