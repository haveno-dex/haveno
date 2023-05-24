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

import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.payment.payload.CountryBasedPaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public abstract class CountryBasedPaymentAccount extends PaymentAccount {
    @Nullable
    protected Country country;
    @Nullable
    protected List<Country> acceptedCountries;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected CountryBasedPaymentAccount(PaymentMethod paymentMethod) {
        super(paymentMethod);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter, Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public Country getCountry() {
        if (country == null) {
            final String countryCode = ((CountryBasedPaymentAccountPayload) paymentAccountPayload).getCountryCode();
            CountryUtil.findCountryByCode(countryCode).ifPresent(c -> this.country = c);
        }
        return country;
    }

    public void setCountry(@NotNull Country country) {
        this.country = country;
        ((CountryBasedPaymentAccountPayload) paymentAccountPayload).setCountryCode(country.code);
    }

    @Nullable
    public List<Country> getAcceptedCountries() {
        if (acceptedCountries == null) {
            final List<String> acceptedCountryCodes = ((CountryBasedPaymentAccountPayload) paymentAccountPayload).getAcceptedCountryCodes();
            acceptedCountries = CountryUtil.getCountries(acceptedCountryCodes);
        }
        return acceptedCountries;
    }

    public void setAcceptedCountries(List<Country> acceptedCountries) {
        this.acceptedCountries = acceptedCountries;
        ((CountryBasedPaymentAccountPayload) paymentAccountPayload).setAcceptedCountryCodes(CountryUtil.getCountryCodes(acceptedCountries));
    }

    @Nullable
    public List<Country> getSupportedCountries() {
        return null; // support all countries by default
    }
}
