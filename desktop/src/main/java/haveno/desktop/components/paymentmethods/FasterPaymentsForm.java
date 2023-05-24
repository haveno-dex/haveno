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
import haveno.core.payment.FasterPaymentsAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.validation.AccountNrValidator;
import haveno.core.payment.validation.BranchIdValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class FasterPaymentsForm extends PaymentMethodForm {
    private static final String UK_SORT_CODE = "UK sort code";

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        if (!((FasterPaymentsAccountPayload) paymentAccountPayload).getHolderName().isEmpty()) {
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                    ((FasterPaymentsAccountPayload) paymentAccountPayload).getHolderName());
        }
        // do not translate as it is used in English only
        addCompactTopLabelTextField(gridPane, ++gridRow, UK_SORT_CODE,
                ((FasterPaymentsAccountPayload) paymentAccountPayload).getSortCode());
        addCompactTopLabelTextField(gridPane, gridRow, 1, Res.get("payment.accountNr"),
                ((FasterPaymentsAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }


    private final FasterPaymentsAccount fasterPaymentsAccount;
    private InputTextField holderNameInputTextField;
    private InputTextField accountNrInputTextField;
    private InputTextField sortCodeInputTextField;
    private final BranchIdValidator branchIdValidator;
    private final AccountNrValidator accountNrValidator;

    public FasterPaymentsForm(PaymentAccount paymentAccount,
                              AccountAgeWitnessService accountAgeWitnessService,
                              InputValidator inputValidator,
                              GridPane gridPane,
                              int gridRow,
                              CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.fasterPaymentsAccount = (FasterPaymentsAccount) paymentAccount;
        this.branchIdValidator = new BranchIdValidator("GB");
        this.accountNrValidator = new AccountNrValidator("GB");
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;
        holderNameInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.account.owner"));
        holderNameInputTextField.setValidator(inputValidator);
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            fasterPaymentsAccount.setHolderName(newValue);
            updateFromInputs();
        });

        // do not translate as it is used in English only
        sortCodeInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, UK_SORT_CODE);
        sortCodeInputTextField.setValidator(inputValidator);
        sortCodeInputTextField.setValidator(branchIdValidator);
        sortCodeInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            fasterPaymentsAccount.setSortCode(newValue);
            updateFromInputs();
        });

        accountNrInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.accountNr"));
        accountNrInputTextField.setValidator(accountNrValidator);
        accountNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            fasterPaymentsAccount.setAccountNr(newValue);
            updateFromInputs();
        });

        TradeCurrency singleTradeCurrency = fasterPaymentsAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(fasterPaymentsAccount.getAccountNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(fasterPaymentsAccount.getPaymentMethod().getId()));
        if (!fasterPaymentsAccount.getHolderName().isEmpty()) {
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"),
                    fasterPaymentsAccount.getHolderName());
        }
        // do not translate as it is used in English only
        addCompactTopLabelTextField(gridPane, ++gridRow, UK_SORT_CODE, fasterPaymentsAccount.getSortCode());
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.accountNr"),
                fasterPaymentsAccount.getAccountNr()).second;
        field.setMouseTransparent(false);
        TradeCurrency singleTradeCurrency = fasterPaymentsAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(fasterPaymentsAccount.getHolderName()).isValid
                && branchIdValidator.validate(fasterPaymentsAccount.getSortCode()).isValid
                && accountNrValidator.validate(fasterPaymentsAccount.getAccountNr()).isValid
                && fasterPaymentsAccount.getTradeCurrencies().size() > 0);
    }
}
