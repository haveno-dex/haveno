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
import haveno.core.payment.payload.VenmoAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

// Removed due too high chargeback risk
// Cannot be deleted as it would break old trade history entries
@Deprecated
@EqualsAndHashCode(callSuper = true)
public final class VenmoAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("USD"));

    public VenmoAccount() {
        super(PaymentMethod.VENMO);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new VenmoAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public void setVenmoUserName(String venmoUserName) {
        ((VenmoAccountPayload) paymentAccountPayload).setVenmoUserName(venmoUserName);
    }

    public String getVenmoUserName() {
        return ((VenmoAccountPayload) paymentAccountPayload).getVenmoUserName();
    }

    public void setHolderName(String holderName) {
        ((VenmoAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((VenmoAccountPayload) paymentAccountPayload).getHolderName();
    }
}
