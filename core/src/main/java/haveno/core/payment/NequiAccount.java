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
import haveno.core.payment.payload.NequiAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class NequiAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("COP"));

    public NequiAccount() {
        super(PaymentMethod.NEQUI);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new NequiAccountPayload(paymentMethod.getId(), id);
    }

    public void setMobileNr(String mobileNr) {
        ((NequiAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((NequiAccountPayload) paymentAccountPayload).getMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.nequi.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.nequi.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.nequi.info.account";
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }
}
