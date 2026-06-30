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
import haveno.core.payment.payload.SpeiAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class SpeiAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("MXN"));
    public static final List<Country> SUPPORTED_COUNTRIES = CountryUtil.getCountries(List.of("MX"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.CLABE,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public SpeiAccount() {
        super(PaymentMethod.SPEI);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0)); // this payment method is only for Mexico/MXN
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SpeiAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((SpeiAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((SpeiAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setClabe(String clabe) {
        ((SpeiAccountPayload) paymentAccountPayload).setClabe(clabe);
    }

    public String getClabe() {
        return ((SpeiAccountPayload) paymentAccountPayload).getClabe();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.spei.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.spei.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.spei.info.account";
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
    public @NotNull List<Country> getSupportedCountries() {
        return SUPPORTED_COUNTRIES;
    }
}
