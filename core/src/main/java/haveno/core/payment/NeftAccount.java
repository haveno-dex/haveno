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
import haveno.core.payment.payload.NeftAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class NeftAccount extends IfscBasedAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.HOLDER_NAME,
            PaymentAccountFormField.FieldId.ACCOUNT_NR,
            PaymentAccountFormField.FieldId.BRANCH_ID,
            PaymentAccountFormField.FieldId.SALT
    );

    public NeftAccount() {
        super(PaymentMethod.NEFT);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new NeftAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    public String getMessageForBuyer() {
        return "payment.neft.info.buyer";
    }

    public String getMessageForSeller() {
        return "payment.neft.info.seller";
    }

    public String getMessageForAccountCreation() {
        return "payment.neft.info.account";
    }

}
