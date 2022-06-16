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

package bisq.core.payment;

import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.locale.FiatCurrency;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.payload.TransferwiseAccountPayload;

import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

@EqualsAndHashCode(callSuper = true)
public final class TransferwiseAccount extends PaymentAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.EMAIL,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    // https://github.com/bisq-network/proposals/issues/243
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new FiatCurrency("AED"),
            new FiatCurrency("ARS"),
            new FiatCurrency("AUD"),
            new FiatCurrency("BGN"),
            new FiatCurrency("CAD"),
            new FiatCurrency("CHF"),
            new FiatCurrency("CLP"),
            new FiatCurrency("CZK"),
            new FiatCurrency("DKK"),
            new FiatCurrency("EGP"),
            new FiatCurrency("EUR"),
            new FiatCurrency("GBP"),
            new FiatCurrency("GEL"),
            new FiatCurrency("HKD"),
            new FiatCurrency("HRK"),
            new FiatCurrency("HUF"),
            new FiatCurrency("IDR"),
            new FiatCurrency("ILS"),
            new FiatCurrency("JPY"),
            new FiatCurrency("KES"),
            new FiatCurrency("KRW"),
            new FiatCurrency("MAD"),
            new FiatCurrency("MXN"),
            new FiatCurrency("MYR"),
            new FiatCurrency("NOK"),
            new FiatCurrency("NPR"),
            new FiatCurrency("NZD"),
            new FiatCurrency("PEN"),
            new FiatCurrency("PHP"),
            new FiatCurrency("PKR"),
            new FiatCurrency("PLN"),
            new FiatCurrency("RON"),
            new FiatCurrency("RUB"),
            new FiatCurrency("SEK"),
            new FiatCurrency("SGD"),
            new FiatCurrency("THB"),
            new FiatCurrency("TRY"),
            new FiatCurrency("UGX"),
            new FiatCurrency("VND"),
            new FiatCurrency("XOF"),
            new FiatCurrency("ZAR"),
            new FiatCurrency("ZMW")
    );

    public TransferwiseAccount() {
        super(PaymentMethod.TRANSFERWISE);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new TransferwiseAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    public void setEmail(String accountId) {
        ((TransferwiseAccountPayload) paymentAccountPayload).setEmail(accountId);
    }

    public String getEmail() {
        return ((TransferwiseAccountPayload) paymentAccountPayload).getEmail();
    }
    
    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.TRADE_CURRENCIES) field.setLabel("Currencies for receiving funds");
        return field;
    }
}
