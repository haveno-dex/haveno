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
import haveno.core.payment.payload.PayByMailAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.NonNull;

import java.util.List;

public final class PayByMailAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllTraditionalCurrencies();

    public PayByMailAccount() {
        super(PaymentMethod.PAY_BY_MAIL);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PayByMailAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public void setPostalAddress(String postalAddress) {
        ((PayByMailAccountPayload) paymentAccountPayload).setPostalAddress(postalAddress);
    }

    public String getPostalAddress() {
        return ((PayByMailAccountPayload) paymentAccountPayload).getPostalAddress();
    }

    public void setContact(String contact) {
        ((PayByMailAccountPayload) paymentAccountPayload).setContact(contact);
    }

    public String getContact() {
        return ((PayByMailAccountPayload) paymentAccountPayload).getContact();
    }

    public void setExtraInfo(String extraInfo) {
        ((PayByMailAccountPayload) paymentAccountPayload).setExtraInfo(extraInfo);
    }

    public String getExtraInfo() {
        return ((PayByMailAccountPayload) paymentAccountPayload).getExtraInfo();
    }
}
