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
import haveno.core.payment.payload.CapitualAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class CapitualAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("BRL"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("USD")
    );

    public CapitualAccount() {
        super(PaymentMethod.CAPITUAL);
        tradeCurrencies.addAll(SUPPORTED_CURRENCIES);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CapitualAccountPayload(paymentMethod.getId(), id);
    }

    @NotNull
    @Override
    public List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @NotNull
    @Override
    public List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public void setAccountNr(String accountNr) {
        ((CapitualAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((CapitualAccountPayload) paymentAccountPayload).getAccountNr();
    }
}
