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

import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.locale.Country;
import bisq.core.locale.CountryUtil;
import bisq.core.locale.FiatCurrency;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.payload.StrikeAccountPayload;
import java.util.List;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;

import org.jetbrains.annotations.NotNull;

@EqualsAndHashCode(callSuper = true)
public final class StrikeAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new FiatCurrency("USD"));
    public static final List<Country> SUPPORTED_COUNTRIES = CountryUtil.getCountries(List.of("US"));
    
    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public StrikeAccount() {
        super(PaymentMethod.STRIKE);
        // this payment method is currently restricted to United States/USD
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new StrikeAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String accountId) {
        ((StrikeAccountPayload) paymentAccountPayload).setHolderName(accountId);
    }

    public String getHolderName() {
        return ((StrikeAccountPayload) paymentAccountPayload).getHolderName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.strike.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.strike.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.strike.info.account";
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }
    
    @Override
    @Nullable
    public @NotNull List<Country> getSupportedCountries() {
        System.out.println("STIKE RETURNING SUPPORTED COUNTRIES: " + SUPPORTED_COUNTRIES);
        return SUPPORTED_COUNTRIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }
}
