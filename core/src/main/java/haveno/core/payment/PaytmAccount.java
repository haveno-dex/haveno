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

import java.util.List;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.PaytmAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

@EqualsAndHashCode(callSuper = true)
public final class PaytmAccount extends IfscBasedAccount {
    
    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.SALT
    );

    public PaytmAccount() {
        super(PaymentMethod.PAYTM);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new PaytmAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public void setEmailOrMobileNr(String emailOrMobileNr) {
        ((PaytmAccountPayload) paymentAccountPayload).setEmailOrMobileNr(emailOrMobileNr);
    }

    public String getEmailOrMobileNr() {
        return ((PaytmAccountPayload) paymentAccountPayload).getEmailOrMobileNr();
    }

    public String getMessageForBuyer() {
        return "payment.paytm.info.buyer";
    }

    public String getMessageForSeller() {
        return "payment.paytm.info.seller";
    }

    public String getMessageForAccountCreation() {
        return "payment.paytm.info.account";
    }
}
