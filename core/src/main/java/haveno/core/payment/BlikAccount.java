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

import java.util.List;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.api.model.PaymentAccountFormField.FieldId;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.payment.payload.BlikAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.NonNull;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public final class BlikAccount extends CountryBasedPaymentAccount {

    private static final TradeCurrency PLN_CURRENCY = new TraditionalCurrency("PLN");
    private static final Country COUNTRY = CountryUtil.getCountry("PL");

    private static final List<Country> SUPPORTED_COUNTRIES = List.of(
        COUNTRY
    );

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
        PLN_CURRENCY
    );

    protected BlikAccount() {
        super(PaymentMethod.BLIK);
        country = COUNTRY;
        acceptedCountries = SUPPORTED_COUNTRIES;
        setSingleTradeCurrency(PLN_CURRENCY);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new BlikAccountPayload(paymentMethod.getId(), id, SUPPORTED_COUNTRIES);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<FieldId> getInputFieldIds() {
        return List.of(
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME
        );
    }

    public void setExtraInfo(String extraInfo) {
        ((BlikAccountPayload) paymentAccountPayload).setExtraInfo(extraInfo);
    }

    public String getExtraInfo() {
        return ((BlikAccountPayload) paymentAccountPayload).getExtraInfo();
    }  
}
