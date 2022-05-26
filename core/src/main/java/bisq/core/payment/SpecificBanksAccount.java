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

import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.payload.SpecificBanksAccountPayload;

import java.util.ArrayList;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.NonNull;

@EqualsAndHashCode(callSuper = true)
public final class SpecificBanksAccount extends CountryBasedPaymentAccount implements BankNameRestrictedBankAccount, SameCountryRestrictedBankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    public SpecificBanksAccount() {
        super(PaymentMethod.SPECIFIC_BANKS);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SpecificBanksAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    // TODO change to List
    public ArrayList<String> getAcceptedBanks() {
        return ((SpecificBanksAccountPayload) paymentAccountPayload).getAcceptedBanks();
    }

    @Override
    public String getBankId() {
        return ((SpecificBanksAccountPayload) paymentAccountPayload).getBankId();
    }

    @Override
    public String getCountryCode() {
        return getCountry() != null ? getCountry().code : "";
    }
}
