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

package bisq.core.payment;
import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.F2FAccountPayload;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

@EqualsAndHashCode(callSuper = true)
public final class F2FAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.CONTACT, // TODO: contact is not used anywhere?
            PaymentAccountFormField.FieldId.CITY,
            PaymentAccountFormField.FieldId.EXTRA_INFO,
            PaymentAccountFormField.FieldId.SALT
    );

    public F2FAccount() {
        super(PaymentMethod.F2F);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new F2FAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setContact(String contact) {
        ((F2FAccountPayload) paymentAccountPayload).setContact(contact);
    }

    public String getContact() {
        return ((F2FAccountPayload) paymentAccountPayload).getContact();
    }

    public void setCity(String city) {
        ((F2FAccountPayload) paymentAccountPayload).setCity(city);
    }

    public String getCity() {
        return ((F2FAccountPayload) paymentAccountPayload).getCity();
    }

    public void setExtraInfo(String extraInfo) {
        ((F2FAccountPayload) paymentAccountPayload).setExtraInfo(extraInfo);
    }

    public String getExtraInfo() {
        return ((F2FAccountPayload) paymentAccountPayload).getExtraInfo();
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.CITY) field.setLabel(Res.get("payment.f2f.city"));
        if (field.getId() == PaymentAccountFormField.FieldId.CONTACT) field.setLabel(Res.get("payment.f2f.contact"));
        if (field.getId() == PaymentAccountFormField.FieldId.EXTRA_INFO) field.setLabel(Res.get("payment.shared.extraInfo.prompt"));
        return field;
    }
}
