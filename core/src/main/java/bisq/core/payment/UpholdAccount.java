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

import haveno.core.locale.CurrencyUtil;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.UpholdAccountPayload;

import lombok.EqualsAndHashCode;

//TODO missing support for selected trade currency
@EqualsAndHashCode(callSuper = true)
public final class UpholdAccount extends PaymentAccount {
    public UpholdAccount() {
        super(PaymentMethod.UPHOLD);
        tradeCurrencies.addAll(CurrencyUtil.getAllUpholdCurrencies());
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new UpholdAccountPayload(paymentMethod.getId(), id);
    }

    public void setAccountId(String accountId) {
        ((UpholdAccountPayload) paymentAccountPayload).setAccountId(accountId);
    }

    public String getAccountId() {
        return ((UpholdAccountPayload) paymentAccountPayload).getAccountId();
    }
}
