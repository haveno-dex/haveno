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

package haveno.core.offer;

import haveno.core.payment.payload.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OfferTest {

    @Test
    public void testInvalidMaxTradePeriodFallsBackToPaymentMethod() {
        for (long invalidPeriod : new long[]{Long.MAX_VALUE, PaymentMethod.ZELLE.getMaxTradePeriod() * 2, 1L, 0L}) {
            OfferPayload payload = mock(OfferPayload.class);
            when(payload.getPaymentMethodId()).thenReturn(PaymentMethod.ZELLE_ID);
            when(payload.getMaxTradePeriod()).thenReturn(invalidPeriod);

            assertEquals(PaymentMethod.ZELLE.getMaxTradePeriod(), new Offer(payload).getMaxTradePeriod());
        }
    }

    @Test
    public void testGrandfatheredMaxTradePeriodIsPreserved() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getPaymentMethodId()).thenReturn(PaymentMethod.ZELLE_ID);
        when(payload.getMaxTradePeriod()).thenReturn(PaymentMethod.ZELLE.getMaxTradePeriod() * 4);

        assertEquals(PaymentMethod.ZELLE.getMaxTradePeriod() * 4, new Offer(payload).getMaxTradePeriod());
    }

    @Test
    public void testHasNoRange() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getMinAmount()).thenReturn(1000L);
        when(payload.getAmount()).thenReturn(1000L);

        Offer offer = new Offer(payload);
        assertFalse(offer.isRange());
    }

    @Test
    public void testHasRange() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getMinAmount()).thenReturn(1000L);
        when(payload.getAmount()).thenReturn(2000L);

        Offer offer = new Offer(payload);
        assertTrue(offer.isRange());
    }
}
