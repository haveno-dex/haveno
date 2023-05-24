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
import haveno.core.payment.payload.BankAccountPayload;
import haveno.core.payment.payload.DomesticWireTransferAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class DomesticWireTransferAccount extends CountryBasedPaymentAccount implements SameCountryRestrictedBankAccount {

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(new TraditionalCurrency("USD"));

    public DomesticWireTransferAccount() {
        super(PaymentMethod.DOMESTIC_WIRE_TRANSFER);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new DomesticWireTransferAccountPayload(paymentMethod.getId(), id);
    }

    @Override
    public String getBankId() {
        return ((BankAccountPayload) paymentAccountPayload).getBankId();
    }

    @Override
    public String getCountryCode() {
        return getCountry() != null ? getCountry().code : "";
    }

    public DomesticWireTransferAccountPayload getPayload() {
        return (DomesticWireTransferAccountPayload) paymentAccountPayload;
    }

    @Override
    public String getMessageForBuyer() {
        return "payment.domesticWire.info.buyer";
    }

    @Override
    public String getMessageForSeller() {
        return "payment.domesticWire.info.seller";
    }

    @Override
    public String getMessageForAccountCreation() {
        return "payment.domesticWire.info.account";
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
