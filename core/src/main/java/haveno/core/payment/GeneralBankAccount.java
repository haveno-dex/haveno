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
import haveno.core.locale.BankUtil;
import haveno.core.payment.payload.BankAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.BankIdValidator;
import haveno.core.payment.validation.BranchIdValidator;
import haveno.core.payment.validation.NationalAccountIdValidator;
import haveno.core.util.validation.InputValidator;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Base class for bank account types which share the same set of form fields and
 * country based validation (e.g. {@link NationalBankAccount}). This mirrors the
 * desktop {@code GeneralBankForm}/{@code BankForm} hierarchy so the gRPC form API
 * and the desktop form reuse the same validators.
 *
 * The form exposes the superset of bank fields. Country specific fields (bank id,
 * branch id, national account id, account type, holder tax id) are only validated
 * for the countries which require them, exactly as the desktop form does.
 */
@EqualsAndHashCode(callSuper = true)
public abstract class GeneralBankAccount extends CountryBasedPaymentAccount implements SameCountryRestrictedBankAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.HOLDER_TAX_ID,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.BANK_ID,
            PaymentAccountFormField.FieldId.BRANCH_ID,
            PaymentAccountFormField.FieldId.NATIONAL_ACCOUNT_ID,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_TYPE,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    protected GeneralBankAccount(PaymentMethod paymentMethod) {
        super(paymentMethod);
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {

        // bank specific fields are only validated for countries which use validation,
        // exactly matching the desktop GeneralBankForm/BankForm behavior
        String countryCode = form.getValue(PaymentAccountFormField.FieldId.COUNTRY);
        if (countryCode == null) countryCode = "";
        boolean useValidation = BankUtil.useValidation(countryCode);

        switch (fieldId) {
        case BANK_NAME:
            if (useValidation && BankUtil.isBankNameRequired(countryCode)) processValidationResult(new InputValidator().validate(value));
            break;
        case BANK_ID:
            if (useValidation && BankUtil.isBankIdRequired(countryCode)) processValidationResult(new BankIdValidator(countryCode).validate(value));
            break;
        case BRANCH_ID:
            if (useValidation && BankUtil.isBranchIdRequired(countryCode)) processValidationResult(new BranchIdValidator(countryCode).validate(value));
            break;
        case NATIONAL_ACCOUNT_ID:
            if (useValidation && BankUtil.isNationalAccountIdRequired(countryCode)) processValidationResult(new NationalAccountIdValidator(countryCode).validate(value));
            break;
        case ACCOUNT_TYPE:
            if (useValidation && BankUtil.isAccountTypeRequired(countryCode)) processValidationResult(new InputValidator().validate(value));
            break;
        case HOLDER_TAX_ID:
            if (useValidation && BankUtil.isHolderIdRequired(countryCode)) processValidationResult(new InputValidator().validate(value));
            break;
        default:
            // ACCOUNT_NR is validated by CountryBasedPaymentAccount using the same AccountNrValidator,
            // which falls back to a non-empty check for non-validation countries (as the desktop form does)
            super.validateFormField(form, fieldId, value);
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {

        // these fields are not handled by the base class, so build them here
        switch (fieldId) {
        case BANK_ID:
        case NATIONAL_ACCOUNT_ID:
        case HOLDER_TAX_ID: {
            PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            if (fieldId == PaymentAccountFormField.FieldId.BANK_ID) field.setLabel(BankUtil.getBankIdLabel(""));
            else if (fieldId == PaymentAccountFormField.FieldId.NATIONAL_ACCOUNT_ID) field.setLabel(BankUtil.getNationalAccountIdLabel(""));
            else field.setLabel(BankUtil.getHolderIdLabel(""));
            return field;
        }
        default:
            // handled below
        }

        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        switch (fieldId) {
        case TRADE_CURRENCIES:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            break;
        case BANK_NAME:
            field.setLabel(BankUtil.getBankNameLabel(""));
            break;
        case BRANCH_ID:
            field.setLabel(BankUtil.getBranchIdLabel(""));
            break;
        default:
            // no action
        }
        return field;
    }

    @Override
    public String getBankId() {
        return ((BankAccountPayload) paymentAccountPayload).getBankId();
    }

    @Override
    public String getCountryCode() {
        return getCountry() != null ? getCountry().code : "";
    }
}
