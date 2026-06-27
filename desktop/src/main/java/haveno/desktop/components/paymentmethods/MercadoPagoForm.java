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
import haveno.core.locale.Country;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.MercadoPagoAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.MercadoPagoAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import static haveno.desktop.util.FormBuilder.addComboBox;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

public class MercadoPagoForm extends PaymentMethodForm {
    private final MercadoPagoAccount account;
    private ComboBox<Country> countryCombo;

    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, 0, Res.get("payment.account.owner.fullname"),
                ((MercadoPagoAccountPayload) paymentAccountPayload).getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.email.mobile"),
                ((MercadoPagoAccountPayload) paymentAccountPayload).getEmailOrMobileNr());
        return gridRow;
    }

    public MercadoPagoForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                           InputValidator inputValidator, GridPane gridPane,
                           int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.account = (MercadoPagoAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField holderNameField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"));
        holderNameField.setValidator(inputValidator);
        holderNameField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setHolderName(newValue.trim());
            updateFromInputs();
        });

        InputTextField emailOrMobileNrField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.email.mobile"));
        emailOrMobileNrField.setValidator(inputValidator);
        emailOrMobileNrField.textProperty().addListener((ov, oldValue, newValue) -> {
            account.setEmailOrMobileNr(newValue.trim());
            updateFromInputs();
        });

        // the country determines the local Mercado Pago currency
        countryCombo = addComboBox(gridPane, ++gridRow, Res.get("shared.country"));
        countryCombo.setPromptText(Res.get("payment.select.country"));
        countryCombo.setItems(FXCollections.observableArrayList(account.getSupportedCountries()));
        TextField currencyField = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), "").second;
        countryCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Country country) {
                return country.name + " (" + country.code + ")";
            }
            @Override
            public Country fromString(String s) {
                return null;
            }
        });
        countryCombo.setOnAction(e -> {
            Country country = countryCombo.getValue();
            account.setCountry(country);
            TradeCurrency currency = CurrencyUtil.getCurrencyByCountryCode(country.code);
            account.setSingleTradeCurrency(currency);
            currencyField.setText(currency.getNameAndCode());
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(account.getEmailOrMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(account.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner.fullname"), account.getHolderName())
                .second.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email.mobile"), account.getEmailOrMobileNr())
                .second.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.country"),
                account.getCountry() != null ? account.getCountry().name : "");
        String nameAndCode = account.getSingleTradeCurrency() != null ? account.getSingleTradeCurrency().getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(account.getHolderName()).isValid
                && inputValidator.validate(account.getEmailOrMobileNr()).isValid
                && account.getCountry() != null
                && account.getSingleTradeCurrency() != null);
    }
}
