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

package haveno.core.payment;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class MoneyGramAccount extends PaymentAccount {

    @Nullable
    private Country country;

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.STATE,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.EMAIL,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.SALT
    );

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AED"),
            new TraditionalCurrency("ARS"),
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("BND"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("CHF"),
            new TraditionalCurrency("CZK"),
            new TraditionalCurrency("DKK"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("FJD"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("HUF"),
            new TraditionalCurrency("IDR"),
            new TraditionalCurrency("ILS"),
            new TraditionalCurrency("INR"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("KRW"),
            new TraditionalCurrency("KWD"),
            new TraditionalCurrency("LKR"),
            new TraditionalCurrency("MAD"),
            new TraditionalCurrency("MGA"),
            new TraditionalCurrency("MXN"),
            new TraditionalCurrency("MYR"),
            new TraditionalCurrency("NOK"),
            new TraditionalCurrency("NZD"),
            new TraditionalCurrency("OMR"),
            new TraditionalCurrency("PEN"),
            new TraditionalCurrency("PGK"),
            new TraditionalCurrency("PHP"),
            new TraditionalCurrency("PKR"),
            new TraditionalCurrency("PLN"),
            new TraditionalCurrency("SAR"),
            new TraditionalCurrency("SBD"),
            new TraditionalCurrency("SCR"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("THB"),
            new TraditionalCurrency("TOP"),
            new TraditionalCurrency("TRY"),
            new TraditionalCurrency("TWD"),
            new TraditionalCurrency("USD"),
            new TraditionalCurrency("VND"),
            new TraditionalCurrency("VUV"),
            new TraditionalCurrency("WST"),
            new TraditionalCurrency("XOF"),
            new TraditionalCurrency("XPF"),
            new TraditionalCurrency("ZAR")
    );

    public MoneyGramAccount() {
        super(PaymentMethod.MONEY_GRAM);
        tradeCurrencies.addAll(SUPPORTED_CURRENCIES);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new MoneyGramAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Nullable
    public Country getCountry() {
        if (country == null) {
            final String countryCode = ((MoneyGramAccountPayload) paymentAccountPayload).getCountryCode();
            CountryUtil.findCountryByCode(countryCode).ifPresent(c -> this.country = c);
        }
        return country;
    }

    public void setCountry(@NotNull Country country) {
        this.country = country;
        ((MoneyGramAccountPayload) paymentAccountPayload).setCountryCode(country.code);
    }

    public String getEmail() {
        return ((MoneyGramAccountPayload) paymentAccountPayload).getEmail();
    }

    public void setEmail(String email) {
        ((MoneyGramAccountPayload) paymentAccountPayload).setEmail(email);
    }

    public String getFullName() {
        return ((MoneyGramAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setFullName(String email) {
        ((MoneyGramAccountPayload) paymentAccountPayload).setHolderName(email);
    }

    public String getState() {
        return ((MoneyGramAccountPayload) paymentAccountPayload).getState();
    }

    public void setState(String state) {
        ((MoneyGramAccountPayload) paymentAccountPayload).setState(state);
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.HOLDER_NAME) field.setLabel("Full name (first, middle, last)");
        return field;
    }
}
