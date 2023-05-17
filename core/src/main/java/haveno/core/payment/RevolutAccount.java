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
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.RevolutAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class RevolutAccount extends PaymentAccount {

    private static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.USER_NAME,
            PaymentAccountFormField.FieldId.TRADE_CURRENCIES,
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.SALT
    );

    // https://www.revolut.com/help/getting-started/exchanging-currencies/what-fiat-currencies-are-supported-for-holding-and-exchange
    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = List.of(
            new TraditionalCurrency("AED"),
            new TraditionalCurrency("AUD"),
            new TraditionalCurrency("BGN"),
            new TraditionalCurrency("CAD"),
            new TraditionalCurrency("CHF"),
            new TraditionalCurrency("CZK"),
            new TraditionalCurrency("DKK"),
            new TraditionalCurrency("EUR"),
            new TraditionalCurrency("GBP"),
            new TraditionalCurrency("HKD"),
            new TraditionalCurrency("HRK"),
            new TraditionalCurrency("HUF"),
            new TraditionalCurrency("ILS"),
            new TraditionalCurrency("ISK"),
            new TraditionalCurrency("JPY"),
            new TraditionalCurrency("MAD"),
            new TraditionalCurrency("MXN"),
            new TraditionalCurrency("NOK"),
            new TraditionalCurrency("NZD"),
            new TraditionalCurrency("PLN"),
            new TraditionalCurrency("QAR"),
            new TraditionalCurrency("RON"),
            new TraditionalCurrency("RSD"),
            new TraditionalCurrency("RUB"),
            new TraditionalCurrency("SAR"),
            new TraditionalCurrency("SEK"),
            new TraditionalCurrency("SGD"),
            new TraditionalCurrency("THB"),
            new TraditionalCurrency("TRY"),
            new TraditionalCurrency("USD"),
            new TraditionalCurrency("ZAR")
    );

    public RevolutAccount() {
        super(PaymentMethod.REVOLUT);
        tradeCurrencies.addAll(getSupportedCurrencies());
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new RevolutAccountPayload(paymentMethod.getId(), id);
    }

    public void setUserName(String userName) {
        revolutAccountPayload().setUserName(userName);
    }

    public String getUserName() {
        return (revolutAccountPayload()).getUserName();
    }

    public String getAccountId() {
        return (revolutAccountPayload()).getAccountId();
    }

    public boolean userNameNotSet() {
        return (revolutAccountPayload()).userNameNotSet();
    }

    public boolean hasOldAccountId() {
        return (revolutAccountPayload()).hasOldAccountId();
    }

    private RevolutAccountPayload revolutAccountPayload() {
        return (RevolutAccountPayload) paymentAccountPayload;
    }

    @Override
    public void onAddToUser() {
        super.onAddToUser();

        // At save we apply the userName to accountId in case it is empty for backward compatibility
        revolutAccountPayload().maybeApplyUserNameToAccountId();
    }

    @Override
    public @NonNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public @NonNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }
}
