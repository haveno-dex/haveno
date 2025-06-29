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

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.BankUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.AchTransferAccountPayload;
import haveno.core.payment.payload.BankAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class AchTransferAccount extends CountryBasedPaymentAccount implements SameCountryRestrictedBankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("USD"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.HOLDER_ADDRESS,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.BRANCH_ID,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.ACCOUNT_TYPE,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public AchTransferAccount() {
        super(PaymentMethod.ACH_TRANSFER);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new AchTransferAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public String getBankId() {
        return ((BankAccountPayload) paymentAccountPayload).getBankId();
    }

    @Override
    public String getCountryCode() {
        return getCountry() != null ? getCountry().code : "";
    }

    public AchTransferAccountPayload getPayload() {
        return (AchTransferAccountPayload) paymentAccountPayload;
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.achTransfer.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.achTransfer.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.achTransfer.info.account";
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
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        if (field.getId() == PaymentAccountFormField.FieldId.TRADE_CURRENCIES) field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
        if (field.getId() == PaymentAccountFormField.FieldId.BRANCH_ID) field.setLabel(BankUtil.getBranchIdLabel("US"));
        if (field.getId() == PaymentAccountFormField.FieldId.ACCOUNT_TYPE) field.setLabel(BankUtil.getAccountTypeLabel("US"));
        return field;
    }
}
