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

import com.google.common.collect.Lists;
import haveno.core.locale.CryptoCurrency;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReceiptPredicatesTest {
    private final ReceiptPredicates predicates = new ReceiptPredicates();

    @Test
    public void testIsMatchingCurrency() {
        Offer offer = mock(Offer.class);
        when(offer.getCurrencyCode()).thenReturn("USD");

        PaymentAccount account = mock(PaymentAccount.class);
        when(account.getTradeCurrencies()).thenReturn(Lists.newArrayList(
                new CryptoCurrency("BTC", "Bitcoin"),
                new CryptoCurrency("ETH", "Ether")));

        assertFalse(predicates.isMatchingCurrency(offer, account));
    }

    @Test
    public void testIsMatchingSepaOffer() {
        Offer offer = mock(Offer.class);
        PaymentMethod.SEPA = mock(PaymentMethod.class);
        when(offer.getPaymentMethod()).thenReturn(PaymentMethod.SEPA);

        assertTrue(predicates.isMatchingSepaOffer(offer, mock(SepaInstantAccount.class)));
        assertTrue(predicates.isMatchingSepaOffer(offer, mock(SepaAccount.class)));
    }

    @Test
    public void testIsMatchingSepaInstant() {
        Offer offer = mock(Offer.class);
        PaymentMethod.SEPA_INSTANT = mock(PaymentMethod.class);
        when(offer.getPaymentMethod()).thenReturn(PaymentMethod.SEPA_INSTANT);

        assertTrue(predicates.isMatchingSepaInstant(offer, mock(SepaInstantAccount.class)));
        assertFalse(predicates.isMatchingSepaInstant(offer, mock(SepaAccount.class)));
    }

    @Test
    public void testIsMatchingCountryCodes() {
        CountryBasedPaymentAccount account = mock(CountryBasedPaymentAccount.class);
        when(account.getCountry()).thenReturn(null);

        assertFalse(predicates.isMatchingCountryCodes(mock(Offer.class), account));
    }

    @Test
    public void testIsSameOrSpecificBank() {
        PaymentMethod.SAME_BANK = mock(PaymentMethod.class);

        Offer offer = mock(Offer.class);
        when(offer.getPaymentMethod()).thenReturn(PaymentMethod.SAME_BANK);

        assertTrue(predicates.isOfferRequireSameOrSpecificBank(offer, mock(NationalBankAccount.class)));
    }

    @Test
    public void testIsEqualPaymentMethods() {
        PaymentMethod method = PaymentMethod.getDummyPaymentMethod("1");

        Offer offer = mock(Offer.class);
        when(offer.getPaymentMethod()).thenReturn(method);

        PaymentAccount account = mock(PaymentAccount.class);
        when(account.getPaymentMethod()).thenReturn(method);

        assertTrue(predicates.isEqualPaymentMethods(offer, account));
    }
}
