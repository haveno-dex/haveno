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
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.RaastAccountPayload;
import haveno.core.payment.validation.AccountNrValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;
import java.util.Locale;

@EqualsAndHashCode(callSuper = true)
public final class RaastAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("PKR"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public RaastAccount() {
        super(PaymentMethod.RAAST);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new RaastAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setHolderName(String holderName) {
        ((RaastAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((RaastAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setAccountNr(String accountNr) {
        // store the canonical form (no separators, uppercase IBAN) so cosmetic variants map to one account identity
        if (accountNr != null) {
            String stripped = accountNr.replaceAll("[\\s-]", "");
            if (stripped.matches("(?i)PK[0-9]{2}[A-Z0-9]{20}") || stripped.matches("[0-9]{11}")) accountNr = stripped.toUpperCase(Locale.ROOT);
        }
        ((RaastAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((RaastAccountPayload) paymentAccountPayload).getAccountNr();
    }

    public void setBankName(String bankName) {
        ((RaastAccountPayload) paymentAccountPayload).setBankName(bankName);
    }

    public String getBankName() {
        return ((RaastAccountPayload) paymentAccountPayload).getBankName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.raast.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.raast.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.raast.info.account";
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
            case ACCOUNT_NR:
                processValidationResult(new AccountNrValidator("PK").validate(value));
                break;
            default:
                super.validateFormField(form, fieldId, value);
                break;
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        if (fieldId == PaymentAccountFormField.FieldId.BANK_NAME) field.setLabel(Res.get("payment.bank.name"));
        return field;
    }
}
