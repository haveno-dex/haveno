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
import haveno.desktop.util.Layout;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.AmazonGiftCardAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import javafx.collections.FXCollections;

import javafx.util.StringConverter;

import java.util.HashMap;

import lombok.extern.slf4j.Slf4j;

import static haveno.desktop.util.FormBuilder.*;

@Slf4j
public class AmazonGiftCardForm extends PaymentMethodForm {
    private InputTextField accountNrInputTextField;
    ComboBox<Country> countryCombo;
    private final AmazonGiftCardAccount amazonGiftCardAccount;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        AmazonGiftCardAccountPayload amazonGiftCardAccountPayload = (AmazonGiftCardAccountPayload) paymentAccountPayload;

        addTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1, Res.get("payment.amazon.site"),
                countryToAmazonSite(amazonGiftCardAccountPayload.getCountryCode()),
                Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE);
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.email.mobile"),
                amazonGiftCardAccountPayload.getEmailOrMobileNr());
        String countryText = CountryUtil.getNameAndCode(amazonGiftCardAccountPayload.getCountryCode());
        if (countryText.isEmpty()) {
            countryText = Res.get("payment.ask");
        }
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 1,
                Res.get("shared.country"),
                countryText);
        return gridRow;
    }

    public AmazonGiftCardForm(PaymentAccount paymentAccount,
                              AccountAgeWitnessService accountAgeWitnessService,
                              InputValidator inputValidator,
                              GridPane gridPane,
                              int gridRow,
                              CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);

        this.amazonGiftCardAccount = (AmazonGiftCardAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        accountNrInputTextField = addInputTextField(gridPane, ++gridRow, Res.get("payment.email.mobile"));
        accountNrInputTextField.setValidator(inputValidator);
        accountNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            amazonGiftCardAccount.setEmailOrMobileNr(newValue);
            updateFromInputs();
        });

        countryCombo = addComboBox(gridPane, ++gridRow, Res.get("shared.country"));
        countryCombo.setPromptText(Res.get("payment.select.country"));
        countryCombo.setItems(FXCollections.observableArrayList(CountryUtil.getAllAmazonGiftCardCountries()));
        TextField ccyField = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), "").second;
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
            Country countryCode = countryCombo.getValue();
            amazonGiftCardAccount.setCountry(countryCode);
            TradeCurrency currency = CurrencyUtil.getCurrencyByCountryCode(countryCode.code);
            paymentAccount.setSingleTradeCurrency(currency);
            ccyField.setText(currency.getNameAndCode());
            updateFromInputs();
        });

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(accountNrInputTextField.getText());
    }

    @Override
    public void addFormForDisplayAccount() {
        addFormForAccountNumberDisplayAccount(paymentAccount.getAccountName(), paymentAccount.getPaymentMethod(),
                amazonGiftCardAccount.getEmailOrMobileNr(),
                paymentAccount.getSingleTradeCurrency());
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(amazonGiftCardAccount.getEmailOrMobileNr()).isValid
                && paymentAccount.getTradeCurrencies().size() > 0);
    }

    private void addFormForAccountNumberDisplayAccount(String accountName,
                                                       PaymentMethod paymentMethod,
                                                       String accountNr,
                                                       TradeCurrency singleTradeCurrency) {
        gridRowFrom = gridRow;
        addTopLabelTextField(gridPane, gridRow, Res.get("payment.account.name"), accountName,
                Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"),
                Res.get(paymentMethod.getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow,
                Res.get("payment.email.mobile"), accountNr).second;
        field.setMouseTransparent(false);

        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.country"),
                amazonGiftCardAccount.getCountry() != null ? amazonGiftCardAccount.getCountry().name : "");
        String nameAndCode = singleTradeCurrency != null ? singleTradeCurrency.getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), nameAndCode);

        addLimitations(true);
    }

    private static String countryToAmazonSite(String countryCode) {
        HashMap<String, String> mapCountryToSite = new HashMap<>() {{
            put("AU", "https://www.amazon.au");
            put("CA", "https://www.amazon.ca");
            put("FR", "https://www.amazon.fr");
            put("DE", "https://www.amazon.de");
            put("IT", "https://www.amazon.it");
            put("NL", "https://www.amazon.nl");
            put("ES", "https://www.amazon.es");
            put("UK", "https://www.amazon.co.uk");
            put("IN", "https://www.amazon.in");
            put("JP", "https://www.amazon.co.jp");
            put("SA", "https://www.amazon.sa");
            put("SE", "https://www.amazon.se");
            put("SG", "https://www.amazon.sg");
            put("TR", "https://www.amazon.tr");
            put("US", "https://www.amazon.com");
            put("",   Res.get("payment.ask"));
        }};
        return mapCountryToSite.get(countryCode);
    }
}
