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
import haveno.core.payment.payload.AdvancedCashAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class AdvancedCashAccount extends PaymentAccount {
    public AdvancedCashAccount() {
        super(PaymentMethod.ADVANCED_CASH);
        tradeCurrencies.addAll(CurrencyUtil.getAllAdvancedCashCurrencies());
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new AdvancedCashAccountPayload(paymentMethod.getId(), id);
    }

    public void setAccountNr(String accountNr) {
        ((AdvancedCashAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((AdvancedCashAccountPayload) paymentAccountPayload).getAccountNr();
    }
}
