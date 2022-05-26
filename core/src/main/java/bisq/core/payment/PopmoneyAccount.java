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

import bisq.core.locale.FiatCurrency;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.payload.PopmoneyAccountPayload;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.NonNull;

//TODO missing support for selected trade currency
@EqualsAndHashCode(callSuper = true)
public final class PopmoneyAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new FiatCurrency("USD"));

    public PopmoneyAccount() {
        super(PaymentMethod.POPMONEY);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PopmoneyAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    public void setAccountId(String accountId) {
        ((PopmoneyAccountPayload) paymentAccountPayload).setAccountId(accountId);
    }

    public String getAccountId() {
        return ((PopmoneyAccountPayload) paymentAccountPayload).getAccountId();
    }

    public void setHolderName(String holderName) {
        ((PopmoneyAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((PopmoneyAccountPayload) paymentAccountPayload).getHolderName();
    }
}
