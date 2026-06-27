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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.MercadoPagoAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class MercadoPagoAccount extends CountryBasedPaymentAccount {

    // Mercado Pago operates in these Latin American countries, each with its own currency.
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("ARS"), // Argentina
            new TraditionalCurrency("BRL"), // Brazil
            new TraditionalCurrency("MXN"), // Mexico
            new TraditionalCurrency("CLP"), // Chile
            new TraditionalCurrency("COP"), // Colombia
            new TraditionalCurrency("PEN"), // Peru
            new TraditionalCurrency("UYU")  // Uruguay
    );

    private static final List<String> SUPPORTED_COUNTRY_CODES = List.of(
            "AR", "BR", "MX", "CL", "CO", "PE", "UY");

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.EMAIL_OR_MOBILE_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public MercadoPagoAccount() {
        super(PaymentMethod.MERCADO_PAGO);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new MercadoPagoAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((MercadoPagoAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((MercadoPagoAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setEmailOrMobileNr(String emailOrMobileNr) {
        ((MercadoPagoAccountPayload) paymentAccountPayload).setEmailOrMobileNr(emailOrMobileNr);
    }

    public String getEmailOrMobileNr() {
        return ((MercadoPagoAccountPayload) paymentAccountPayload).getEmailOrMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.mercadoPago.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.mercadoPago.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.mercadoPago.info.account";
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
    public void setCountry(Country country) {
        super.setCountry(country);
        setSingleTradeCurrency(CurrencyUtil.getCurrencyByCountryCode(country.code)); // each Mercado Pago market has its own currency
    }
}
