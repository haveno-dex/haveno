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
import haveno.core.locale.FiatCurrency;
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
            new FiatCurrency("AED"),
            new FiatCurrency("ARS"),
            new FiatCurrency("AUD"),
            new FiatCurrency("BND"),
            new FiatCurrency("CAD"),
            new FiatCurrency("CHF"),
            new FiatCurrency("CZK"),
            new FiatCurrency("DKK"),
            new FiatCurrency("EUR"),
            new FiatCurrency("FJD"),
            new FiatCurrency("GBP"),
            new FiatCurrency("HKD"),
            new FiatCurrency("HUF"),
            new FiatCurrency("IDR"),
            new FiatCurrency("ILS"),
            new FiatCurrency("INR"),
            new FiatCurrency("JPY"),
            new FiatCurrency("KRW"),
            new FiatCurrency("KWD"),
            new FiatCurrency("LKR"),
            new FiatCurrency("MAD"),
            new FiatCurrency("MGA"),
            new FiatCurrency("MXN"),
            new FiatCurrency("MYR"),
            new FiatCurrency("NOK"),
            new FiatCurrency("NZD"),
            new FiatCurrency("OMR"),
            new FiatCurrency("PEN"),
            new FiatCurrency("PGK"),
            new FiatCurrency("PHP"),
            new FiatCurrency("PKR"),
            new FiatCurrency("PLN"),
            new FiatCurrency("SAR"),
            new FiatCurrency("SBD"),
            new FiatCurrency("SCR"),
            new FiatCurrency("SEK"),
            new FiatCurrency("SGD"),
            new FiatCurrency("THB"),
            new FiatCurrency("TOP"),
            new FiatCurrency("TRY"),
            new FiatCurrency("TWD"),
            new FiatCurrency("USD"),
            new FiatCurrency("VND"),
            new FiatCurrency("VUV"),
            new FiatCurrency("WST"),
            new FiatCurrency("XOF"),
            new FiatCurrency("XPF"),
            new FiatCurrency("ZAR")
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
