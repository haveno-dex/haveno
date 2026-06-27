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
import haveno.core.payment.payload.MpesaAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class MpesaAccount extends CountryBasedPaymentAccount {

    // M-Pesa operates natively via local telecom partnerships in these countries, each with its own currency
    // (the DR Congo is dual-currency, processing both Congolese francs and US dollars).
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("KES"), // Kenya
            new TraditionalCurrency("TZS"), // Tanzania
            new TraditionalCurrency("ETB"), // Ethiopia
            new TraditionalCurrency("EGP"), // Egypt
            new TraditionalCurrency("MZN"), // Mozambique
            new TraditionalCurrency("LSL"), // Lesotho
            new TraditionalCurrency("GHS"), // Ghana
            new TraditionalCurrency("CDF"), // DR Congo
            new TraditionalCurrency("USD")  // DR Congo (dual-currency)
    );

    private static final List<String> SUPPORTED_COUNTRY_CODES = List.of(
            "KE", "TZ", "ET", "EG", "MZ", "LS", "GH", "CD");

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public MpesaAccount() {
        super(PaymentMethod.MPESA);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new MpesaAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((MpesaAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((MpesaAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setMobileNr(String mobileNr) {
        ((MpesaAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((MpesaAccountPayload) paymentAccountPayload).getMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.mpesa.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.mpesa.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.mpesa.info.account";
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public List<Country> getSupportedCountries() {
        return CountryUtil.getCountries(SUPPORTED_COUNTRY_CODES);
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        // each M-Pesa account holds a single currency (the local currency of the selected country), so offer one choice
        if (field.getId() == PaymentAccountFormField.FieldId.TRADE_CURRENCIES) field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
        return field;
    }
}
