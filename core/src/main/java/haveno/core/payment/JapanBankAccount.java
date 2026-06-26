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
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.JapanBankAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.JapanBankAccountNumberValidator;
import haveno.core.payment.validation.JapanBankBranchCodeValidator;
import haveno.core.payment.validation.JapanBankBranchNameValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.util.validation.RegexValidator;
import lombok.NonNull;

import java.util.List;

public final class JapanBankAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("JPY"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.BANK_NAME, // bank is selected from a list; the bank code is derived from the selection
            PaymentAccountFormField.FieldId.BANK_BRANCH_NAME,
            PaymentAccountFormField.FieldId.BANK_BRANCH_CODE,
            PaymentAccountFormField.FieldId.BANK_ACCOUNT_NUMBER,
            PaymentAccountFormField.FieldId.BANK_ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.BANK_ACCOUNT_TYPE,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public JapanBankAccount() {
        super(PaymentMethod.JAPAN_BANK);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new JapanBankAccountPayload(paymentMethod.getId(), id);
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
        // reuse the same Japan specific validators as the desktop JapanBankTransferForm
        switch (fieldId) {
        case BANK_NAME:
            // the bank must be one of the supported Japanese banks (the bank code is derived from the selection)
            if (!JapanBankData.prettyPrintBankList().contains(value)) throw new IllegalArgumentException(Res.get("payment.japan.bank") + ": " + value);
            break;
        case BANK_ACCOUNT_TYPE:
            if (!JapanBankData.accountTypes().contains(value)) throw new IllegalArgumentException(labelFor(fieldId) + ": " + value);
            break;
        case BANK_BRANCH_CODE:
            processValidationResult(new JapanBankBranchCodeValidator().validate(value));
            break;
        case BANK_BRANCH_NAME:
            processValidationResult(new JapanBankBranchNameValidator(new LengthValidator(), new RegexValidator()).validate(value));
            break;
        case BANK_ACCOUNT_NUMBER:
            processValidationResult(new JapanBankAccountNumberValidator().validate(value));
            break;
        default:
            // BANK_NAME and BANK_ACCOUNT_NAME are validated by the base class
            super.validateFormField(form, fieldId, value);
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        // these fields are not handled by the base class, so build them here
        switch (fieldId) {
            case BANK_NAME: {
                // expose the list of Japanese banks as selectable options; the bank code is derived from the selection
                PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
                field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
                field.setLabel(Res.get("payment.japan.bank"));
                field.setSupportedValues(JapanBankData.prettyPrintBankList());
                return field;
            }
            case BANK_ACCOUNT_TYPE: {
                PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
                field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
                field.setLabel(labelFor(fieldId));
                field.setSupportedValues(JapanBankData.accountTypes());
                return field;
            }
            case BANK_BRANCH_NAME:
            case BANK_ACCOUNT_NUMBER: {
                PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
                field.setComponent(PaymentAccountFormField.Component.TEXT);
                field.setLabel(labelFor(fieldId));
                return field;
            }
            default:
                return super.getEmptyFormField(fieldId);
        }
    }

    private static String labelFor(PaymentAccountFormField.FieldId fieldId) {
        switch (fieldId) {
            case BANK_BRANCH_NAME: return Res.get("payment.japan.branch");
            case BANK_ACCOUNT_NUMBER: return Res.get("payment.accountNr");
            case BANK_ACCOUNT_TYPE: return Res.get("payment.accountType");
            default: return "";
        }
    }

    // bank code
    public String getBankCode() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankCode();
    }

    public void setBankCode(String bankCode) {
        if (bankCode == null) bankCode = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankCode(bankCode);
    }

    // bank name
    public String getBankName() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankName();
    }

    public void setBankName(String bankName) {
        if (bankName == null) bankName = "";
        // a selected bank list entry is "<code> <japanese name> [<english name>]" (see JapanBankData.prettyPrintBankList());
        // derive the bank code and keep the clean japanese name, mirroring the desktop JapanBankTransferForm
        if (bankName.matches("^\\d{4} .+")) {
            setBankCode(JapanBankData.bankCodeFromEntry(bankName));
            bankName = JapanBankData.bankNameFromEntry(bankName);
        }
        ((JapanBankAccountPayload) paymentAccountPayload).setBankName(bankName);
    }

    // branch code
    public String getBankBranchCode() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankBranchCode();
    }

    public void setBankBranchCode(String bankBranchCode) {
        if (bankBranchCode == null) bankBranchCode = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankBranchCode(bankBranchCode);
    }

    // branch name
    public String getBankBranchName() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankBranchName();
    }

    public void setBankBranchName(String bankBranchName) {
        if (bankBranchName == null) bankBranchName = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankBranchName(bankBranchName);
    }

    // account type
    public String getBankAccountType() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankAccountType();
    }

    public void setBankAccountType(String bankAccountType) {
        if (bankAccountType == null) bankAccountType = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankAccountType(bankAccountType);
    }

    // account number
    public String getBankAccountNumber() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankAccountNumber();
    }

    public void setBankAccountNumber(String bankAccountNumber) {
        if (bankAccountNumber == null) bankAccountNumber = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankAccountNumber(bankAccountNumber);
    }

    // account name
    public String getBankAccountName() {
        return ((JapanBankAccountPayload) paymentAccountPayload).getBankAccountName();
    }

    public void setBankAccountName(String bankAccountName) {
        if (bankAccountName == null) bankAccountName = "";
        ((JapanBankAccountPayload) paymentAccountPayload).setBankAccountName(bankAccountName);
    }
}
