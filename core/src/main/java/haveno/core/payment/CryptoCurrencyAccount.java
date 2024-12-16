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
import haveno.core.payment.payload.CryptoCurrencyAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class CryptoCurrencyAccount extends AssetAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
        PaymentAccountFormField.FieldId.ACCOUNT_NAME,
        PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
        PaymentAccountFormField.FieldId.ADDRESS,
        PaymentAccountFormField.FieldId.SALT
    );

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = new ArrayList<>(CurrencyUtil.getAllSortedCryptoCurrencies());

    public CryptoCurrencyAccount() {
        super(PaymentMethod.BLOCK_CHAINS);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CryptoCurrencyAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.TRADE_CURRENCIES) field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
        return field;
    }
}
