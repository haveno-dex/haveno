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
import haveno.core.payment.payload.UkraineCardAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.UkraineCardValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class UkraineCardAccount extends CountryBasedPaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("UAH"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public UkraineCardAccount() {
        super(PaymentMethod.UKRAINE_CARD);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0)); // this payment method is only for Ukraine/UAH
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new UkraineCardAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String holderName) {
        ((UkraineCardAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((UkraineCardAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setAccountNr(String accountNr) {
        // store the canonical form (no separators) so cosmetic variants map to one account identity
        if (accountNr != null) {
            String stripped = accountNr.replaceAll("[\\s-]", "");
            if (stripped.matches("[0-9]{16,19}")) accountNr = stripped;
        }
        ((UkraineCardAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((UkraineCardAccountPayload) paymentAccountPayload).getAccountNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.ukraineCard.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.ukraineCard.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.ukraineCard.info.account";
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
            case ACCOUNT_NR:
                processValidationResult(new UkraineCardValidator().validate(value));
                break;
            default:
                super.validateFormField(form, fieldId, value);
                break;
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        if (fieldId == PaymentAccountFormField.FieldId.ACCOUNT_NR) field.setLabel(Res.get("payment.cardNr"));
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
        return Arrays.asList(CountryUtil.findCountryByCode("UA").get());
    }
}
