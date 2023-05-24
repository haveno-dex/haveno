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
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.UpholdAccountPayload;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

//TODO missing support for selected trade currency
@EqualsAndHashCode(callSuper = true)
public final class UpholdAccount extends PaymentAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_OWNER,
            PaymentAccountFormField.FieldId.ACCOUNT_ID,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.SALT
    );

    // https://support.uphold.com/hc/en-us/articles/202473803-Supported-currencies
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AED"),
            new TraditionalCurrency("ARS"),
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("BRL"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("CHF"),
            new TraditionalCurrency("CNY"),
            new TraditionalCurrency("DKK"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("ILS"),
            new TraditionalCurrency("INR"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("KES"),
            new TraditionalCurrency("MXN"),
            new TraditionalCurrency("NOK"),
            new TraditionalCurrency("NZD"),
            new TraditionalCurrency("PHP"),
            new TraditionalCurrency("PLN"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("USD")
    );

    public UpholdAccount() {
        super(PaymentMethod.UPHOLD);
        tradeCurrencies.addAll(SUPPORTED_CURRENCIES);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new UpholdAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setAccountId(String accountId) {
        ((UpholdAccountPayload) paymentAccountPayload).setAccountId(accountId);
    }

    public String getAccountId() {
        return ((UpholdAccountPayload) paymentAccountPayload).getAccountId();
    }

    public String getAccountOwner() {
        return ((UpholdAccountPayload) paymentAccountPayload).getAccountOwner();
    }

    public void setAccountOwner(String accountOwner) {
        if (accountOwner == null) {
            accountOwner = "";
        }
        ((UpholdAccountPayload) paymentAccountPayload).setAccountOwner(accountOwner);
    }
}
