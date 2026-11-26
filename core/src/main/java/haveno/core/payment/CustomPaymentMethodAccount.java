/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.payment;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.CustomPaymentMethodAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

// Account for a payment method which Haveno does not support. The user names the method and gives the account data.
@EqualsAndHashCode(callSuper = true)
public final class CustomPaymentMethodAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.PAYMENT_METHOD_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_DATA,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public CustomPaymentMethodAccount() {
        super(PaymentMethod.CUSTOM);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CustomPaymentMethodAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    // texts are stored without characters which are not allowed, since pasted text can carry invisible ones
    public void setPaymentMethodName(String paymentMethodName) {
        ((CustomPaymentMethodAccountPayload) paymentAccountPayload).setPaymentMethodName(CustomPaymentMethodUtil.cleanName(paymentMethodName));
    }

    public String getPaymentMethodName() {
        return ((CustomPaymentMethodAccountPayload) paymentAccountPayload).getPaymentMethodName();
    }

    public void setAccountData(String accountData) {
        ((CustomPaymentMethodAccountPayload) paymentAccountPayload).setAccountData(CustomPaymentMethodUtil.cleanAccountData(accountData));
    }

    public String getAccountData() {
        return ((CustomPaymentMethodAccountPayload) paymentAccountPayload).getAccountData();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.custom.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.custom.info.seller";
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.TRADE_CURRENCIES) field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
        return field;
    }
}
