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
import haveno.core.payment.payload.SepaInstantAccountPayload;
import haveno.core.payment.validation.SepaIBANValidator;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class SepaInstantAccount extends CountryBasedPaymentAccount implements BankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("EUR"));

    public SepaInstantAccount() {
        super(PaymentMethod.SEPA_INSTANT);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new SepaInstantAccountPayload(paymentMethod.getId(), id,
                CountryUtil.getAllSepaInstantCountries());
    }

    @Override
    public String getBankId() {
        return ((SepaInstantAccountPayload) paymentAccountPayload).getBic();
    }

    public void setHolderName(String holderName) {
        ((SepaInstantAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((SepaInstantAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setIban(String iban) {
        ((SepaInstantAccountPayload) paymentAccountPayload).setIban(iban);
    }

    public String getIban() {
        return ((SepaInstantAccountPayload) paymentAccountPayload).getIban();
    }

    public void setBic(String bic) {
        ((SepaInstantAccountPayload) paymentAccountPayload).setBic(bic);
    }

    public String getBic() {
        return ((SepaInstantAccountPayload) paymentAccountPayload).getBic();
    }

    public List<String> getAcceptedCountryCodes() {
        return ((SepaInstantAccountPayload) paymentAccountPayload).getAcceptedCountryCodes();
    }

    public void addAcceptedCountry(String countryCode) {
        ((SepaInstantAccountPayload) paymentAccountPayload).addAcceptedCountry(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        ((SepaInstantAccountPayload) paymentAccountPayload).removeAcceptedCountry(countryCode);
    }

    @Override
    public void onPersistChanges() {
        super.onPersistChanges();
        ((SepaInstantAccountPayload) paymentAccountPayload).onPersistChanges();
    }

    @Override
    public void revertChanges() {
        super.revertChanges();
        ((SepaInstantAccountPayload) paymentAccountPayload).revertChanges();
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return SepaAccount.INPUT_FIELD_IDS;
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
