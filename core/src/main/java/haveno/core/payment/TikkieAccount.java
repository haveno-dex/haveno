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

import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.TikkieAccountPayload;
import haveno.core.payment.validation.IBANValidator;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class TikkieAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("EUR"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.IBAN,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public TikkieAccount() {
        super(PaymentMethod.TIKKIE);
        // this payment method is only for Netherlands/EUR
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new TikkieAccountPayload(paymentMethod.getId(), id);
    }

    public void setIban(String iban) {
        ((TikkieAccountPayload) paymentAccountPayload).setIban(iban);
    }

    public String getIban() {
        return ((TikkieAccountPayload) paymentAccountPayload).getIban();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.tikkie.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.tikkie.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.tikkie.info.account";
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    @Nullable
    public List<Country> getSupportedCountries() {
        return Arrays.asList(CountryUtil.findCountryByCode("NL").get());
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
        case IBAN:
            // Tikkie is a Dutch service, so the IBAN must be Dutch (matches the desktop TikkieForm's IBANValidator("NL"))
            processValidationResult(new IBANValidator("NL").validate(value));
            break;
        default:
            super.validateFormField(form, fieldId, value);
        }
    }
}
