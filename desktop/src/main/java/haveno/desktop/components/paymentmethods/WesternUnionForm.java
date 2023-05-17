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

import haveno.common.util.Tuple2;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.BankUtil;
import haveno.core.locale.Country;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.CountryBasedPaymentAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.payment.validation.EmailValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.GridPane;
import lombok.extern.slf4j.Slf4j;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

@Slf4j
public class WesternUnionForm extends PaymentMethodForm {
    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload) {
        final WesternUnionAccountPayload payload = (WesternUnionAccountPayload) paymentAccountPayload;
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.fullName"),
                payload.getHolderName());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.email"),
                payload.getEmail());
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.city"),
                payload.getCity());
        if (BankUtil.isStateRequired(payload.getCountryCode()))
            addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.account.state"),
                    payload.getState());

        return gridRow;
    }

    private final WesternUnionAccountPayload westernUnionAccountPayload;
    private InputTextField cityInputTextField;
    private InputTextField stateInputTextField;
    private final EmailValidator emailValidator;
    private Country selectedCountry;

    public WesternUnionForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InputValidator inputValidator,
                            GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.westernUnionAccountPayload = (WesternUnionAccountPayload) paymentAccount.paymentAccountPayload;

        emailValidator = new EmailValidator();
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;

        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(paymentAccount.getPaymentMethod().getId()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.country"),
                getCountryBasedPaymentAccount().getCountry() != null ? getCountryBasedPaymentAccount().getCountry().name : "");
        TradeCurrency singleTradeCurrency = paymentAccount.getSingleTradeCurrency();
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "null";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"),
                nameAndCode);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.fullName"),
                westernUnionAccountPayload.getHolderName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.city"),
                westernUnionAccountPayload.getCity()).second.setMouseTransparent(false);
        if (BankUtil.isStateRequired(westernUnionAccountPayload.getCountryCode()))
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.state"),
                    westernUnionAccountPayload.getState()).second.setMouseTransparent(false);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.email"),
                westernUnionAccountPayload.getEmail());
        addLimitations(true);
    }

    private void onTradeCurrencySelected(TradeCurrency tradeCurrency) {
        TraditionalCurrency defaultCurrency = CurrencyUtil.getCurrencyByCountryCode(selectedCountry.code);
        applyTradeCurrency(tradeCurrency, defaultCurrency);
    }

    private void onCountrySelected(Country country) {
        selectedCountry = country;
        if (country != null) {
            getCountryBasedPaymentAccount().setCountry(country);
            String countryCode = country.code;
            TradeCurrency currency = CurrencyUtil.getCurrencyByCountryCode(countryCode);
            paymentAccount.setSingleTradeCurrency(currency);
            currencyComboBox.setDisable(false);
            currencyComboBox.getSelectionModel().select(currency);
            updateFromInputs();
            applyIsStateRequired();
            cityInputTextField.setText("");
            stateInputTextField.setText("");
        }
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        Tuple2<ComboBox<TradeCurrency>, Integer> tuple = GUIUtil.addRegionCountryTradeCurrencyComboBoxes(gridPane, gridRow, this::onCountrySelected, this::onTradeCurrencySelected);
        currencyComboBox = tuple.first;
        gridRow = tuple.second;

        InputTextField holderNameInputTextField = FormBuilder.addInputTextField(gridPane,
                ++gridRow, Res.get("payment.account.fullName"));
        holderNameInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            westernUnionAccountPayload.setHolderName(newValue);
            updateFromInputs();
        });
        holderNameInputTextField.setValidator(inputValidator);

        cityInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.account.city"));
        cityInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            westernUnionAccountPayload.setCity(newValue);
            updateFromInputs();

        });

        stateInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.account.state"));
        stateInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            westernUnionAccountPayload.setState(newValue);
            updateFromInputs();

        });
        applyIsStateRequired();

        InputTextField emailInputTextField = FormBuilder.addInputTextField(gridPane, ++gridRow, Res.get("payment.email"));
        emailInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            westernUnionAccountPayload.setEmail(newValue);
            updateFromInputs();
        });
        emailInputTextField.setValidator(emailValidator);

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();

        updateFromInputs();
    }

    private void applyIsStateRequired() {
        final boolean stateRequired = BankUtil.isStateRequired(westernUnionAccountPayload.getCountryCode());
        stateInputTextField.setManaged(stateRequired);
        stateInputTextField.setVisible(stateRequired);
    }

    private CountryBasedPaymentAccount getCountryBasedPaymentAccount() {
        return (CountryBasedPaymentAccount) this.paymentAccount;
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(westernUnionAccountPayload.getHolderName() == null ? "" : westernUnionAccountPayload.getHolderName());
    }

    @Override
    public void updateAllInputsValid() {
        boolean result = isAccountNameValid()
                && paymentAccount.getSingleTradeCurrency() != null
                && getCountryBasedPaymentAccount().getCountry() != null
                && inputValidator.validate(westernUnionAccountPayload.getHolderName()).isValid
                && inputValidator.validate(westernUnionAccountPayload.getCity()).isValid
                && emailValidator.validate(westernUnionAccountPayload.getEmail()).isValid;
        allInputsValid.set(result);
    }
}
