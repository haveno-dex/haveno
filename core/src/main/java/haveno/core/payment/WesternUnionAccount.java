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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import lombok.NonNull;

import java.util.List;

public final class WesternUnionAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllTraditionalCurrencies();

    public WesternUnionAccount() {
        super(PaymentMethod.WESTERN_UNION);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new WesternUnionAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public String getEmail() {
        return ((WesternUnionAccountPayload) paymentAccountPayload).getEmail();
    }

    public void setEmail(String email) {
        ((WesternUnionAccountPayload) paymentAccountPayload).setEmail(email);
    }

    public String getFullName() {
        return ((WesternUnionAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setFullName(String email) {
        ((WesternUnionAccountPayload) paymentAccountPayload).setHolderName(email);
    }

    public String getCity() {
        return ((WesternUnionAccountPayload) paymentAccountPayload).getCity();
    }

    public void setCity(String email) {
        ((WesternUnionAccountPayload) paymentAccountPayload).setCity(email);
    }

    public String getState() {
        return ((WesternUnionAccountPayload) paymentAccountPayload).getState();
    }

    public void setState(String email) {
        ((WesternUnionAccountPayload) paymentAccountPayload).setState(email);
    }
}
