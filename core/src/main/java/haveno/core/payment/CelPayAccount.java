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
import haveno.core.payment.payload.CelPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class CelPayAccount extends PaymentAccount {

    // https://github.com/bisq-network/growth/issues/231
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("USD")
    );

    public CelPayAccount() {
        super(PaymentMethod.CELPAY);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CelPayAccountPayload(paymentMethod.getId(), id);
    }

    public void setEmail(String accountId) {
        ((CelPayAccountPayload) paymentAccountPayload).setEmail(accountId);
    }

    public String getEmail() {
        return ((CelPayAccountPayload) paymentAccountPayload).getEmail();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.celpay.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.celpay.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.celpay.info.account";
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
