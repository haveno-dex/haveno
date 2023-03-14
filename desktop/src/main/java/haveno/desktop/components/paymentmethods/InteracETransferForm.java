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
import haveno.core.payment.InteracETransferAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.InteracETransferAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.InteracETransferValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class InteracETransferForm extends PaymentMethodForm {

    private final InteracETransferAccount interacETransferAccount;
    private final InteracETransferValidator interacETransferValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((InteracETransferAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextField(gridPane, gridRow, 1, Res.get("payment.emailOrMobile"),
                ((InteracETransferAccountPayload) paymentAccountPayload).getEmail());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.secret"),
                ((InteracETransferAccountPayload) paymentAccountPayload).getQuestion());
        addCompactTopLabelTextField(gridPane, gridRow, 1, Res.get("payment.answer"),
                ((InteracETransferAccountPayload) paymentAccountPayload).getAnswer());
        return gridRow;
    }

    public InteracETransferForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InteracETransferValidator interacETransferValidator,
                                InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.interacETransferAccount = (InteracETransferAccount) paymentAccount;
        this.interacETransferValidator = interacETransferValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            interacETransferAccount.setHolderName(newValue);
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.emailOrMobile"));
        mobileNrInputTextField.setValidator(interacETransferValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            interacETransferAccount.setEmail(newValue);
            updateFromInputs();
        });

        InputTextField questionInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.secret"));
        questionInputTextField.setValidator(interacETransferValidator.questionValidator);
        questionInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            interacETransferAccount.setQuestion(newValue);
            updateFromInputs();
        });

        InputTextField answerInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.answer"));
        answerInputTextField.setValidator(interacETransferValidator.answerValidator);
        answerInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            interacETransferAccount.setAnswer(newValue);
            updateFromInputs();
        });
        TradeCurrency singleTradeCurrency = interacETransferAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(interacETransferAccount.getEmail());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(interacETransferAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                interacETransferAccount.getHolderName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"),
                interacETransferAccount.getEmail()).second.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.secret"),
                interacETransferAccount.getQuestion()).second.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.answer"),
                interacETransferAccount.getAnswer()).second.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = interacETransferAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && interacETransferValidator.validate(interacETransferAccount.getEmail()).isValid
                && inputValidator.validate(interacETransferAccount.getHolderName()).isValid
                && interacETransferValidator.questionValidator.validate(interacETransferAccount.getQuestion()).isValid
                && interacETransferValidator.answerValidator.validate(interacETransferAccount.getAnswer()).isValid
                && interacETransferAccount.getTradeCurrencies().size() > 0);
    }
}
