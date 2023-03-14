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
import haveno.core.locale.FiatCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.PayseraAccountPayload;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class PayseraAccount extends PaymentAccount {

    // https://github.com/bisq-network/growth/issues/233
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new FiatCurrency("AUD"),
            new FiatCurrency("BGN"),
            new FiatCurrency("BYN"),
            new FiatCurrency("CAD"),
            new FiatCurrency("CHF"),
            new FiatCurrency("CNY"),
            new FiatCurrency("CZK"),
            new FiatCurrency("DKK"),
            new FiatCurrency("EUR"),
            new FiatCurrency("GBP"),
            new FiatCurrency("GEL"),
            new FiatCurrency("HKD"),
            new FiatCurrency("HRK"),
            new FiatCurrency("HUF"),
            new FiatCurrency("ILS"),
            new FiatCurrency("INR"),
            new FiatCurrency("JPY"),
            new FiatCurrency("KZT"),
            new FiatCurrency("MXN"),
            new FiatCurrency("NOK"),
            new FiatCurrency("NZD"),
            new FiatCurrency("PHP"),
            new FiatCurrency("PLN"),
            new FiatCurrency("RON"),
            new FiatCurrency("RSD"),
            new FiatCurrency("RUB"),
            new FiatCurrency("SEK"),
            new FiatCurrency("SGD"),
            new FiatCurrency("THB"),
            new FiatCurrency("TRY"),
            new FiatCurrency("USD"),
            new FiatCurrency("ZAR")
    );

    public PayseraAccount() {
        super(PaymentMethod.PAYSERA);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PayseraAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public void setEmail(String accountId) {
        ((PayseraAccountPayload) paymentAccountPayload).setEmail(accountId);
    }

    public String getEmail() {
        return ((PayseraAccountPayload) paymentAccountPayload).getEmail();
    }
}
