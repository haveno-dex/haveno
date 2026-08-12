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
import haveno.core.payment.payload.PaparaAccountPayload;
import haveno.core.payment.validation.AccountNrValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;
import java.util.Locale;

@EqualsAndHashCode(callSuper = true)
public final class PaparaAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("TRY"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public PaparaAccount() {
        super(PaymentMethod.PAPARA);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PaparaAccountPayload(paymentMethod.getId(), id);
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
        ((PaparaAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((PaparaAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setAccountNr(String accountNr) {
        // store the canonical form (no separators, uppercase IBAN) so cosmetic variants map to one account identity
        if (accountNr != null) {
            String stripped = accountNr.replaceAll("[\\s-]", "");
            if (stripped.matches("(?i)TR[0-9]{24}") || stripped.matches("[0-9]{10,11}")) accountNr = stripped.toUpperCase(Locale.ROOT);
        }
        ((PaparaAccountPayload) paymentAccountPayload).setAccountNr(accountNr);
    }

    public String getAccountNr() {
        return ((PaparaAccountPayload) paymentAccountPayload).getAccountNr();
    }

    public void setBankName(String bankName) {
        ((PaparaAccountPayload) paymentAccountPayload).setBankName(bankName);
    }

    public String getBankName() {
        return ((PaparaAccountPayload) paymentAccountPayload).getBankName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.papara.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.papara.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.papara.info.account";
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
            case ACCOUNT_NR:
                processValidationResult(new AccountNrValidator("TR").validate(value));
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
