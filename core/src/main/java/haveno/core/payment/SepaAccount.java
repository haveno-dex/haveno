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
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.SepaAccountPayload;
import haveno.core.payment.validation.SepaIBANValidator;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class SepaAccount extends CountryBasedPaymentAccount implements BankAccount {

    protected static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.IBAN,
            PaymentAccountFormField.FieldId.BIC,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.ACCEPTED_COUNTRY_CODES,
            PaymentAccountFormField.FieldId.SALT
    );

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("EUR"));

    public SepaAccount() {
        super(PaymentMethod.SEPA);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SepaAccountPayload(paymentMethod.getId(), id,
                CountryUtil.getAllSepaCountries());
    }

    @Override
    public String getBankId() {
        return ((SepaAccountPayload) paymentAccountPayload).getBic();
    }

    public void setHolderName(String holderName) {
        ((SepaAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((SepaAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setIban(String iban) {
        ((SepaAccountPayload) paymentAccountPayload).setIban(iban);
    }

    public String getIban() {
        return ((SepaAccountPayload) paymentAccountPayload).getIban();
    }

    public void setBic(String bic) {
        ((SepaAccountPayload) paymentAccountPayload).setBic(bic);
    }

    public String getBic() {
        return ((SepaAccountPayload) paymentAccountPayload).getBic();
    }

    public List<String> getAcceptedCountryCodes() {
        return ((SepaAccountPayload) paymentAccountPayload).getAcceptedCountryCodes();
    }

    public void setAcceptedCountryCodes(List<String> acceptedCountryCodes) {
        ((SepaAccountPayload) paymentAccountPayload).setAcceptedCountryCodes(acceptedCountryCodes);
    }

    public void addAcceptedCountry(String countryCode) {
        ((SepaAccountPayload) paymentAccountPayload).addAcceptedCountry(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        ((SepaAccountPayload) paymentAccountPayload).removeAcceptedCountry(countryCode);
    }

    @Override
    public void onPersistChanges() {
        super.onPersistChanges();
        ((SepaAccountPayload) paymentAccountPayload).onPersistChanges();
    }

    @Override
    public void revertChanges() {
        super.revertChanges();
        ((SepaAccountPayload) paymentAccountPayload).revertChanges();
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    @Nullable
    public List<Country> getSupportedCountries() {
        return CountryUtil.getAllSepaCountries();
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
        case IBAN:
            processValidationResult(new SepaIBANValidator().validate(value));
            break;
        default:
            super.validateFormField(form, fieldId, value);
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        switch (fieldId) {
        case ACCEPTED_COUNTRY_CODES:
            field.setSupportedSepaEuroCountries(CountryUtil.getAllSepaEuroCountries());
            field.setSupportedSepaNonEuroCountries(CountryUtil.getAllSepaNonEuroCountries());
            break;
        default:
            // no action
        }
        return field;
    }
}
