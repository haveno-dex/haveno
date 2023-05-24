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
import haveno.core.payment.payload.TikkieAccountPayload;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class TikkieAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("EUR"));

    public TikkieAccount() {
        super(PaymentMethod.TIKKIE);
        // this payment method is only for Netherlands/EUR
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new TikkieAccountPayload(paymentMethod.getId(), id);
    }

    public void setIban(String iban) {
        ((TikkieAccountPayload) paymentAccountPayload).setIban(iban);
    }

    public String getIban() {
        return ((TikkieAccountPayload) paymentAccountPayload).getIban();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.tikkie.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.tikkie.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.tikkie.info.account";
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }
}
