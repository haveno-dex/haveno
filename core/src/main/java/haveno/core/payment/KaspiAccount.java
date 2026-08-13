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

import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.KaspiAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.KaspiValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class KaspiAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("KZT"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public KaspiAccount() {
        super(PaymentMethod.KASPI);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0)); // this payment method is only for Kazakhstan/KZT
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new KaspiAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((KaspiAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((KaspiAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setAccountNr(String accountNr) {
        // store the canonical form (+7 mobile or bare card digits) so cosmetic variants map to one account identity
        if (accountNr != null) {
            String stripped = accountNr.replaceAll("[\\s-]", "");
            if (stripped.matches("(\\+?7|8)7[0-9]{9}")) accountNr = "+7" + stripped.substring(stripped.length() - 10);
            else if (stripped.matches("[0-9]{16}")) accountNr = stripped;
        }
        ((KaspiAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((KaspiAccountPayload) paymentAccountPayload).getAccountNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.kaspi.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.kaspi.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.kaspi.info.account";
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
            case ACCOUNT_NR:
                processValidationResult(new KaspiValidator().validate(value));
                break;
            default:
                super.validateFormField(form, fieldId, value);
                break;
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        if (fieldId == PaymentAccountFormField.FieldId.ACCOUNT_NR) field.setLabel(Res.get("payment.kaspi.accountId"));
        return field;
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
        return Arrays.asList(CountryUtil.findCountryByCode("KZ").get());
    }
}
