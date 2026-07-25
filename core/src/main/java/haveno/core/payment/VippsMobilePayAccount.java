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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.VippsMobilePayAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class VippsMobilePayAccount extends CountryBasedPaymentAccount {

    // Vipps MobilePay operates in these Nordic countries, each with its own currency.
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("NOK"), // Norway
            new TraditionalCurrency("DKK"), // Denmark
            new TraditionalCurrency("EUR")  // Finland
    );

    private static final List<String> SUPPORTED_COUNTRY_CODES = List.of("NO", "DK", "FI");

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public VippsMobilePayAccount() {
        super(PaymentMethod.VIPPS_MOBILEPAY);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new VippsMobilePayAccountPayload(paymentMethod.getId(), id);
    }

    public void setMobileNr(String mobileNr) {
        ((VippsMobilePayAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((VippsMobilePayAccountPayload) paymentAccountPayload).getMobileNr();
    }

    public void setHolderName(String holderName) {
        ((VippsMobilePayAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((VippsMobilePayAccountPayload) paymentAccountPayload).getHolderName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.vippsMobilePay.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.vippsMobilePay.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.vippsMobilePay.info.account";
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
    public List<Country> getSupportedCountries() {
        return CountryUtil.getCountries(SUPPORTED_COUNTRY_CODES);
    }

    @Override
    public void setCountry(Country country) {
        super.setCountry(country);
        setSingleTradeCurrency(CurrencyUtil.getCurrencyByCountryCode(country.code)); // each Vipps MobilePay market has its own currency
    }
}
