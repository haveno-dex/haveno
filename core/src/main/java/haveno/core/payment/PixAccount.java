/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.payment;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.PixAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

@EqualsAndHashCode(callSuper = true)
public final class PixAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("BRL"));
    public static final List<Country> SUPPORTED_COUNTRIES = CountryUtil.getCountries(List.of("BR"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.PIX_KEY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.SALT
    );

    public PixAccount() {
        super(PaymentMethod.PIX);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PixAccountPayload(paymentMethod.getId(), id);
    }

    public void setPixKey(String pixKey) {
        ((PixAccountPayload) paymentAccountPayload).setPixKey(pixKey);
    }

    public String getPixKey() {
        return ((PixAccountPayload) paymentAccountPayload).getPixKey();
    }

    public void setHolderName(String value) {
        ((PixAccountPayload) paymentAccountPayload).setHolderName(value);
    }

    public String getHolderName() {
        return ((PixAccountPayload) paymentAccountPayload).getHolderName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.pix.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.pix.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.pix.info.account";
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
    public @NotNull List<Country> getSupportedCountries() {
        return SUPPORTED_COUNTRIES;
    }
}
