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
import haveno.core.locale.BankUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PagoMovilAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.PagoMovilValidator;
import haveno.core.util.validation.InputValidator;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class PagoMovilAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("VES"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.HOLDER_TAX_ID,
            PaymentAccountFormField.FieldId.BANK_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public PagoMovilAccount() {
        super(PaymentMethod.PAGO_MOVIL);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PagoMovilAccountPayload(paymentMethod.getId(), id);
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
        ((PagoMovilAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((PagoMovilAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setMobileNr(String mobileNr) {
        ((PagoMovilAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((PagoMovilAccountPayload) paymentAccountPayload).getMobileNr();
    }

    public void setHolderTaxId(String holderTaxId) {
        ((PagoMovilAccountPayload) paymentAccountPayload).setHolderTaxId(holderTaxId);
    }

    public String getHolderTaxId() {
        return ((PagoMovilAccountPayload) paymentAccountPayload).getHolderTaxId();
    }

    public void setBankName(String bankName) {
        ((PagoMovilAccountPayload) paymentAccountPayload).setBankName(bankName);
    }

    public String getBankName() {
        return ((PagoMovilAccountPayload) paymentAccountPayload).getBankName();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.pagoMovil.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.pagoMovil.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.pagoMovil.info.account";
    }

    @Override
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
            case MOBILE_NR:
                processValidationResult(new PagoMovilValidator().validate(value));
                break;
            case HOLDER_TAX_ID:
                processValidationResult(new InputValidator().validate(value));
                break;
            default:
                super.validateFormField(form, fieldId, value);
                break;
        }
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        if (fieldId == PaymentAccountFormField.FieldId.HOLDER_TAX_ID) {
            PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(BankUtil.getHolderIdLabel("VE"));
            return field;
        }
        PaymentAccountFormField field = super.getEmptyFormField(fieldId);
        if (fieldId == PaymentAccountFormField.FieldId.BANK_NAME) field.setLabel(Res.get("payment.bank.name"));
        return field;
    }
}
