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
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.payment.BlikAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.BlikAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

public class BlikForm extends PaymentMethodForm {
    private final BlikAccount blikAccount;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        TextArea textExtraInfo = addCompactTopLabelTextArea(gridPane, ++gridRow, 0, Res.get("payment.shared.extraInfo"), "").second;
        textExtraInfo.setMinHeight(70);
        textExtraInfo.setEditable(false);
        textExtraInfo.setText(((BlikAccountPayload) paymentAccountPayload).getExtraInfo());
        return gridRow;
    }

    public BlikForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                    InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.blikAccount = (BlikAccount) paymentAccount;
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(blikAccount.getPaymentMethod().getId());
    }

    @Override
    public void addFormForAddAccount() {
        // this payment method is only for Poland/PLN
        blikAccount.setSingleTradeCurrency(blikAccount.getSupportedCurrencies().get(0));
        CountryUtil.findCountryByCode("PL").ifPresent(c -> blikAccount.setCountry(c));

        gridRowFrom = gridRow + 1;

        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), blikAccount.getSingleTradeCurrency().getNameAndCode());
        addTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), blikAccount.getCountry().name);

        TextArea extraTextArea = addTopLabelTextArea(gridPane, ++gridRow,
                Res.get("payment.shared.optionalExtra"), Res.get("payment.shared.extraInfo.prompt.paymentAccount")).second;
        extraTextArea.setMinHeight(70);
        ((JFXTextArea) extraTextArea).setLabelFloat(false);
        extraTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            blikAccount.setExtraInfo(newValue);
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"), Res.get(blikAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), blikAccount.getSingleTradeCurrency().getNameAndCode());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), blikAccount.getCountry().name);

        TextArea extraInfoTxtArea = addCompactTopLabelTextArea(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), "").second;
        extraInfoTxtArea.setText(blikAccount.getExtraInfo());
        extraInfoTxtArea.setMinHeight(70);
        extraInfoTxtArea.setEditable(false);

        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid() && blikAccount.getSingleTradeCurrency() != null);
    }
}
