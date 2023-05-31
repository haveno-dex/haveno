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
import haveno.core.payment.payload.CashByAtmAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.NonNull;

import java.util.List;

public final class CashByAtmAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
        PaymentAccountFormField.FieldId.EXTRA_INFO
    );

    public CashByAtmAccount() {
        super(PaymentMethod.CASH_BY_ATM);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CashByAtmAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setExtraInfo(String extraInfo) {
        ((CashByAtmAccountPayload) paymentAccountPayload).setExtraInfo(extraInfo);
    }

    public String getExtraInfo() {
        return ((CashByAtmAccountPayload) paymentAccountPayload).getExtraInfo();
    }
}
