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
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.SbpAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class SbpAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("RUB"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public SbpAccount() {
        super(PaymentMethod.SBP);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0)); // this payment method is only for Russia/RUB
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SbpAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((SbpAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((SbpAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setMobileNr(String mobileNr) {
        ((SbpAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((SbpAccountPayload) paymentAccountPayload).getMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.sbp.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.sbp.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.sbp.info.account";
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
    @Nullable
    public List<Country> getSupportedCountries() {
        return Arrays.asList(CountryUtil.findCountryByCode("RU").get());
    }
}
