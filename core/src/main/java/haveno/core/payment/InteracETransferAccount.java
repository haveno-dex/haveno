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
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.InteracETransferAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.InteracETransferValidator;
import haveno.core.trade.HavenoUtils;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class InteracETransferAccount extends PaymentAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("CAD"));

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.EMAIL_OR_MOBILE_NR,
            PaymentAccountFormField.FieldId.QUESTION,
            PaymentAccountFormField.FieldId.ANSWER,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    public InteracETransferAccount() {
        super(PaymentMethod.INTERAC_E_TRANSFER);
        setSingleTradeCurrency(SUPPORTED_CURRENCIES.get(0));
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new InteracETransferAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setEmail(String email) {
        ((InteracETransferAccountPayload) paymentAccountPayload).setEmailOrMobileNr(email);
    }

    public String getEmail() {
        return ((InteracETransferAccountPayload) paymentAccountPayload).getEmailOrMobileNr();
    }

    public void setAnswer(String answer) {
        ((InteracETransferAccountPayload) paymentAccountPayload).setAnswer(answer);
    }

    public String getAnswer() {
        return ((InteracETransferAccountPayload) paymentAccountPayload).getAnswer();
    }

    public void setQuestion(String question) {
        ((InteracETransferAccountPayload) paymentAccountPayload).setQuestion(question);
    }

    public String getQuestion() {
        return ((InteracETransferAccountPayload) paymentAccountPayload).getQuestion();
    }

    public void setHolderName(String holderName) {
        ((InteracETransferAccountPayload) paymentAccountPayload).setHolderName(holderName);
    }

    public String getHolderName() {
        return ((InteracETransferAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        InteracETransferValidator interacETransferValidator = HavenoUtils.corePaymentAccountService.interacETransferValidator;
        switch (fieldId) {
            case QUESTION:
                processValidationResult(interacETransferValidator.questionValidator.validate(value));
                break;
            case ANSWER:
                processValidationResult(interacETransferValidator.answerValidator.validate(value));
                break;
            default:
                super.validateFormField(form, fieldId, value);
        }

    }
}
