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

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.MoneseAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class MoneseAccount extends PaymentAccount {

    // https://github.com/bisq-network/growth/issues/227
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("RON")
    );

    public MoneseAccount() {
        super(PaymentMethod.MONESE);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new MoneseAccountPayload(paymentMethod.getId(), id);
    }

    public void setHolderName(String accountId) {
        ((MoneseAccountPayload) paymentAccountPayload).setHolderName(accountId);
    }

    public String getHolderName() {
        return ((MoneseAccountPayload) paymentAccountPayload).getHolderName();
    }

    public void setMobileNr(String accountId) {
        ((MoneseAccountPayload) paymentAccountPayload).setMobileNr(accountId);
    }

    public String getMobileNr() {
        return ((MoneseAccountPayload) paymentAccountPayload).getMobileNr();
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.monese.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.monese.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.monese.info.account";
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        throw new RuntimeException("Not implemented");
    }
}
