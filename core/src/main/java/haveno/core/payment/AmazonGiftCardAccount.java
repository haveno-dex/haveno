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
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.AmazonGiftCardAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

public final class AmazonGiftCardAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("INR"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("SAR"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("TRY"),
            new TraditionalCurrency("USD")
    );

    @Nullable
    private Country country;

    public AmazonGiftCardAccount() {
        super(PaymentMethod.AMAZON_GIFT_CARD);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new AmazonGiftCardAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }

    public String getEmailOrMobileNr() {
        return getAmazonGiftCardAccountPayload().getEmailOrMobileNr();
    }

    public void setEmailOrMobileNr(String emailOrMobileNr) {
        getAmazonGiftCardAccountPayload().setEmailOrMobileNr(emailOrMobileNr);
    }

    public boolean countryNotSet() {
        return (getAmazonGiftCardAccountPayload()).countryNotSet();
    }

    @Nullable
    public Country getCountry() {
        if (country == null) {
            final String countryCode = getAmazonGiftCardAccountPayload().getCountryCode();
            CountryUtil.findCountryByCode(countryCode).ifPresent(c -> this.country = c);
        }
        return country;
    }

    public void setCountry(@NotNull Country country) {
        this.country = country;
        getAmazonGiftCardAccountPayload().setCountryCode(country.code);
    }

    private AmazonGiftCardAccountPayload getAmazonGiftCardAccountPayload() {
        return (AmazonGiftCardAccountPayload) paymentAccountPayload;
    }
}
