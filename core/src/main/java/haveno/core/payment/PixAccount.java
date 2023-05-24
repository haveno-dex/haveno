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
import haveno.core.payment.payload.PixAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class PixAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("BRL"));

    public PixAccount() {
        super(PaymentMethod.PIX);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PixAccountPayload(paymentMethod.getId(), id);
    }

    public void setPixKey(String pixKey) {
        ((PixAccountPayload) paymentAccountPayload).setPixKey(pixKey);
    }

    public String getPixKey() {
        return ((PixAccountPayload) paymentAccountPayload).getPixKey();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.pix.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.pix.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.pix.info.account";
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
