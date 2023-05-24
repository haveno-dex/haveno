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
import haveno.core.payment.payload.OKPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

// Cannot be deleted as it would break old trade history entries
@Deprecated
@EqualsAndHashCode(callSuper = true)
public final class OKPayAccount extends PaymentAccount {

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

    public OKPayAccount() {
        super(PaymentMethod.OK_PAY);

        tradeCurrencies.addAll(SUPPORTED_CURRENCIES);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new OKPayAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public void setAccountNr(String accountNr) {
        ((OKPayAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((OKPayAccountPayload) paymentAccountPayload).getAccountNr();
    }
}
