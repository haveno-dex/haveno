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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.EmailValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Cash deposit shares all the bank form fields and country based validation with
 * {@link GeneralBankAccount}, adding a required holder email and an optional free
 * text requirements field (mirroring the desktop {@code CashDepositForm}).
 */
@EqualsAndHashCode(callSuper = true)
public final class CashDepositAccount extends GeneralBankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.HOLDER_EMAIL,
            PaymentAccountFormField.FieldId.HOLDER_TAX_ID,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.BANK_ID,
            PaymentAccountFormField.FieldId.BRANCH_ID,
            PaymentAccountFormField.FieldId.NATIONAL_ACCOUNT_ID,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_TYPE,
            PaymentAccountFormField.FieldId.REQUIREMENTS,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public CashDepositAccount() {
        super(PaymentMethod.CASH_DEPOSIT);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new CashDepositAccountPayload(paymentMethod.getId(), id);
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
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
        case HOLDER_EMAIL:
            processValidationResult(new EmailValidator().validate(value));
            break;
        case REQUIREMENTS:
            // optional free text, not validated (matches the desktop form)
            break;
        default:
            // bank fields are validated by GeneralBankAccount
            super.validateFormField(form, fieldId, value);
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        switch (fieldId) {
        case HOLDER_EMAIL: {
            PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setType("email");
            field.setLabel(Res.get("payment.email"));
            return field;
        }
        case REQUIREMENTS: {
            PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel(Res.get("payment.extras"));
            return field;
        }
        default:
            return super.getEmptyFormField(fieldId);
        }
    }

    @Nullable
    public String getRequirements() {
        return ((CashDepositAccountPayload) paymentAccountPayload).getRequirements();
    }

    public void setRequirements(String requirements) {
        ((CashDepositAccountPayload) paymentAccountPayload).setRequirements(requirements);
    }
}
