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
import haveno.core.payment.ChaseQuickPayAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.ChaseQuickPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.ChaseQuickPayValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class ChaseQuickPayForm extends PaymentMethodForm {

    private final ChaseQuickPayAccount chaseQuickPayAccount;
    private final ChaseQuickPayValidator chaseQuickPayValidator;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                ((ChaseQuickPayAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"),
                ((ChaseQuickPayAccountPayload) paymentAccountPayload).getEmail());
        return gridRow;
    }

    public ChaseQuickPayForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, ChaseQuickPayValidator chaseQuickPayValidator,
                             InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.chaseQuickPayAccount = (ChaseQuickPayAccount) paymentAccount;
        this.chaseQuickPayValidator = chaseQuickPayValidator;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow,
                Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            chaseQuickPayAccount.setHolderName(newValue);
            updateFromInputs();
        });

        InputTextField mobileNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.email"));
        mobileNrInputTextField.setValidator(chaseQuickPayValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            chaseQuickPayAccount.setEmail(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = chaseQuickPayAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(chaseQuickPayAccount.getEmail());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(chaseQuickPayAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                chaseQuickPayAccount.getHolderName());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"),
                chaseQuickPayAccount.getEmail()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = chaseQuickPayAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && chaseQuickPayValidator.validate(chaseQuickPayAccount.getEmail()).isValid
                && inputValidator.validate(chaseQuickPayAccount.getHolderName()).isValid
                && chaseQuickPayAccount.getTradeCurrencies().size() > 0);
    }
}
