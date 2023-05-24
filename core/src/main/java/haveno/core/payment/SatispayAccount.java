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
import haveno.core.payment.payload.SatispayAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class SatispayAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("EUR"));

    public SatispayAccount() {
        super(PaymentMethod.SATISPAY);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SatispayAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String accountId) {
        ((SatispayAccountPayload) paymentAccountPayload).setHolderName(accountId);
    }

    public String getHolderName() {
        return ((SatispayAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setMobileNr(String accountId) {
        ((SatispayAccountPayload) paymentAccountPayload).setMobileNr(accountId);
    }

    public String getMobileNr() {
        return ((SatispayAccountPayload) paymentAccountPayload).getMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.satispay.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.satispay.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.satispay.info.account";
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
