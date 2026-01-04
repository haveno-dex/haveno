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

package haveno.core.payment;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.TransferwiseAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

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
            new TraditionalCurrency("AED"),
            new TraditionalCurrency("ARS"),
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("CHF"),
            new TraditionalCurrency("CLP"),
            new TraditionalCurrency("CZK"),
            new TraditionalCurrency("DKK"),
            new TraditionalCurrency("EGP"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("GEL"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("HUF"),
            new TraditionalCurrency("IDR"),
            new TraditionalCurrency("ILS"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("KES"),
            new TraditionalCurrency("KRW"),
            new TraditionalCurrency("MAD"),
            new TraditionalCurrency("MXN"),
            new TraditionalCurrency("MYR"),
            new TraditionalCurrency("NOK"),
            new TraditionalCurrency("NPR"),
            new TraditionalCurrency("NZD"),
            new TraditionalCurrency("PEN"),
            new TraditionalCurrency("PHP"),
            new TraditionalCurrency("PKR"),
            new TraditionalCurrency("PLN"),
            new TraditionalCurrency("RON"),
            new TraditionalCurrency("RUB"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("THB"),
            new TraditionalCurrency("TRY"),
            new TraditionalCurrency("UGX"),
            new TraditionalCurrency("VND"),
            new TraditionalCurrency("XOF"),
            new TraditionalCurrency("ZAR"),
            new TraditionalCurrency("ZMW")
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
