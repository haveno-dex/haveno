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

import bisq.core.locale.CountryUtil;
import bisq.core.locale.FiatCurrency;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.payload.SepaAccountPayload;

import java.util.List;

import lombok.EqualsAndHashCode;

import org.jetbrains.annotations.NotNull;

@EqualsAndHashCode(callSuper = true)
public final class SepaAccount extends CountryBasedPaymentAccount implements BankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new FiatCurrency("EUR"));

    public SepaAccount() {
        super(PaymentMethod.SEPA);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SepaAccountPayload(paymentMethod.getId(), id,
                CountryUtil.getAllSepaCountries());
    }

    @Override
    public String getBankId() {
        return ((SepaAccountPayload) paymentAccountPayload).getBic();
    }

    public void setHolderName(String holderName) {
        ((SepaAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((SepaAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setIban(String iban) {
        ((SepaAccountPayload) paymentAccountPayload).setIban(iban);
    }

    public String getIban() {
        return ((SepaAccountPayload) paymentAccountPayload).getIban();
    }

    public void setBic(String bic) {
        ((SepaAccountPayload) paymentAccountPayload).setBic(bic);
    }

    public String getBic() {
        return ((SepaAccountPayload) paymentAccountPayload).getBic();
    }

    public List<String> getAcceptedCountryCodes() {
        return ((SepaAccountPayload) paymentAccountPayload).getAcceptedCountryCodes();
    }

    public void addAcceptedCountry(String countryCode) {
        ((SepaAccountPayload) paymentAccountPayload).addAcceptedCountry(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        ((SepaAccountPayload) paymentAccountPayload).removeAcceptedCountry(countryCode);
    }

    @Override
    public void onPersistChanges() {
        super.onPersistChanges();
        ((SepaAccountPayload) paymentAccountPayload).onPersistChanges();
    }

    @Override
    public void revertChanges() {
        super.revertChanges();
        ((SepaAccountPayload) paymentAccountPayload).revertChanges();
    }

    @NotNull
    @Override
    public List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }
}
