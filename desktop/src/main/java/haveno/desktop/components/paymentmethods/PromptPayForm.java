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

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PromptPayAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PromptPayAccountPayload;
import haveno.core.payment.validation.PromptPayValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

public class PromptPayForm extends PaymentMethodForm {
    private final PromptPayAccount promptPayAccount;
    private final PromptPayValidator promptPayValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.promptPay.promptPayId"),
                ((PromptPayAccountPayload) paymentAccountPayload).getPromptPayId());
        return gridRow;
    }

    public PromptPayForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, PromptPayValidator promptPayValidator,
                       InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.promptPayAccount = (PromptPayAccount) paymentAccount;
        this.promptPayValidator = promptPayValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField promptPayIdInputTextField = addInputTextField(gridPane, ++gridRow,
                Res.get("payment.promptPay.promptPayId"));
        promptPayIdInputTextField.setValidator(promptPayValidator);
        promptPayIdInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            promptPayAccount.setPromptPayId(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = promptPayAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(promptPayAccount.getPromptPayId());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(promptPayAccount.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.promptPay.promptPayId"),
                promptPayAccount.getPromptPayId()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = promptPayAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && promptPayValidator.validate(promptPayAccount.getPromptPayId()).isValid
                && promptPayAccount.getTradeCurrencies().size() > 0);
    }
}
