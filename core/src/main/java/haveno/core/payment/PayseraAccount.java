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
import haveno.core.payment.payload.PayseraAccountPayload;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class PayseraAccount extends PaymentAccount {

    // https://github.com/bisq-network/growth/issues/233
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("BYN"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("CHF"),
            new TraditionalCurrency("CNY"),
            new TraditionalCurrency("CZK"),
            new TraditionalCurrency("DKK"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("GEL"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("HUF"),
            new TraditionalCurrency("ILS"),
            new TraditionalCurrency("INR"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("KZT"),
            new TraditionalCurrency("MXN"),
            new TraditionalCurrency("NOK"),
            new TraditionalCurrency("NZD"),
            new TraditionalCurrency("PHP"),
            new TraditionalCurrency("PLN"),
            new TraditionalCurrency("RON"),
            new TraditionalCurrency("RSD"),
            new TraditionalCurrency("RUB"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("THB"),
            new TraditionalCurrency("TRY"),
            new TraditionalCurrency("USD"),
            new TraditionalCurrency("ZAR")
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
