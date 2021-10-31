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

import haveno.core.locale.FiatCurrency;
import haveno.core.payment.payload.AliPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class AliPayAccount extends PaymentAccount {
    public AliPayAccount() {
        super(PaymentMethod.ALI_PAY);
        setSingleTradeCurrency(new FiatCurrency("CNY"));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new AliPayAccountPayload(paymentMethod.getId(), id);
    }

    public void setAccountNr(String accountNr) {
        ((AliPayAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((AliPayAccountPayload) paymentAccountPayload).getAccountNr();
    }
}
