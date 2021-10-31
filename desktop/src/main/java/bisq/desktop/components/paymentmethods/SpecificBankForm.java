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

import haveno.desktop.components.InputTextField;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.SpecificBanksAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

import haveno.common.util.Tuple3;

import com.google.common.base.Joiner;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;

import javafx.beans.binding.Bindings;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextFieldButton;
import static haveno.desktop.util.FormBuilder.addTopLabelTextFieldButton;

public class SpecificBankForm extends BankForm {
    private final SpecificBanksAccountPayload specificBanksAccountPayload;
    private TextField acceptedBanksTextField;
    private Tooltip acceptedBanksTooltip;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        return BankForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
    }

    public SpecificBankForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InputValidator inputValidator,
                            GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.specificBanksAccountPayload = (SpecificBanksAccountPayload) paymentAccount.paymentAccountPayload;
    }

    @Override
    protected void addAcceptedBanksForAddAccount() {
        Tuple3<Label, InputTextField, Button> addBankTuple = addTopLabelInputTextFieldButton(gridPane, ++gridRow,
                Res.get("payment.nameOfAcceptedBank"), Res.get("payment.addAcceptedBank"));
        InputTextField addBankInputTextField = addBankTuple.second;
        Button addButton = addBankTuple.third;
        addButton.setMinWidth(200);
        addButton.disableProperty().bind(Bindings.createBooleanBinding(() -> addBankInputTextField.getText().isEmpty(),
                addBankInputTextField.textProperty()));

        Tuple3<Label, TextField, Button> acceptedBanksTuple = addTopLabelTextFieldButton(gridPane, ++gridRow,
                Res.get("payment.accepted.banks"), Res.get("payment.clearAcceptedBanks"));
        acceptedBanksTextField = acceptedBanksTuple.second;
        acceptedBanksTextField.setMouseTransparent(false);
        acceptedBanksTooltip = new Tooltip();
        acceptedBanksTextField.setTooltip(acceptedBanksTooltip);
        Button clearButton = acceptedBanksTuple.third;
        clearButton.setMinWidth(200);
        clearButton.setDefaultButton(false);
        clearButton.disableProperty().bind(Bindings.createBooleanBinding(() -> acceptedBanksTextField.getText().isEmpty(), acceptedBanksTextField.textProperty()));
        addButton.setOnAction(e -> {
            specificBanksAccountPayload.addAcceptedBank(addBankInputTextField.getText());
            addBankInputTextField.setText("");
            String value = Joiner.on(", ").join(specificBanksAccountPayload.getAcceptedBanks());
            acceptedBanksTextField.setText(value);
            acceptedBanksTooltip.setText(value);
            updateAllInputsValid();
        });

        clearButton.setOnAction(e -> resetAcceptedBanks());
    }

    private void resetAcceptedBanks() {
        specificBanksAccountPayload.clearAcceptedBanks();
        acceptedBanksTextField.setText("");
        acceptedBanksTooltip.setText("");
        updateAllInputsValid();
    }

    @Override
    protected void onCountryChanged() {
        resetAcceptedBanks();
    }

    @Override
    public void addAcceptedBanksForDisplayAccount() {
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.accepted.banks"),
                Joiner.on(", ").join(specificBanksAccountPayload.getAcceptedBanks())).second.setMouseTransparent(false);
    }

    @Override
    public void updateAllInputsValid() {
        super.updateAllInputsValid();
        allInputsValid.set(allInputsValid.get() && inputValidator.validate(acceptedBanksTextField.getText()).isValid);
    }

}
