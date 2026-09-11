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

import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.util.VolumeUtil;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OfferTest {

    @Test
    public void testErgoFixedPriceWorksWithoutMarketFeed() throws Exception {
        OfferPayload payload = ergoPayload(false);
        when(payload.getPrice()).thenReturn(12312345678L);
        Offer offer = new Offer(payload);

        assertEquals("XMR", offer.getBaseCurrencyCode());
        assertEquals("ERG", offer.getCounterCurrencyCode());
        assertEquals(12312345678L, offer.getPrice().getValue());
        offer.verifyTradePrice(12312345678L);
        assertThrows(IllegalArgumentException.class, () -> offer.verifyTradePrice(12312345677L));
    }

    @Test
    public void testErgoIndexedPriceRequiresRecentExternalQuote() {
        Offer offer = new Offer(ergoPayload(true));
        PriceFeedService feed = mock(PriceFeedService.class);
        offer.setPriceFeedService(feed);

        assertNull(offer.getPrice());
        assertThrows(MarketPriceNotAvailableException.class, () -> offer.verifyTradePrice(100000000L));

        long now = Instant.now().toEpochMilli();
        when(feed.getMarketPrice("ERG")).thenReturn(new MarketPrice("ERG", 123.12345678,
                now - MarketPrice.MARKET_PRICE_MAX_AGE_MS - 1000, true));
        assertNull(offer.getPrice());
        assertThrows(MarketPriceNotAvailableException.class, () -> offer.verifyTradePrice(12312345678L));

        when(feed.getMarketPrice("ERG")).thenReturn(new MarketPrice("ERG", 123.12345678, now, false));
        assertNull(offer.getPrice());
        assertThrows(MarketPriceNotAvailableException.class, () -> offer.verifyTradePrice(12312345678L));

        when(feed.getMarketPrice("ERG")).thenReturn(new MarketPrice("ERG", 0, now, true));
        assertNull(offer.getPrice());
    }

    @Test
    public void testErgoIndexedPriceAppliesDirectionAndMargin() throws Exception {
        OfferPayload payload = ergoPayload(true);
        when(payload.getMarketPriceMarginPct()).thenReturn(0.05);
        Offer offer = new Offer(payload);
        PriceFeedService feed = mock(PriceFeedService.class);
        when(feed.getMarketPrice("ERG")).thenReturn(new MarketPrice("ERG", 200, Instant.now().toEpochMilli(), true));
        offer.setPriceFeedService(feed);

        assertEquals(19000000000L, offer.getPrice().getValue());
        offer.verifyTradePrice(19000000000L);
        when(payload.getDirection()).thenReturn(OfferDirection.SELL);
        assertEquals(21000000000L, offer.getPrice().getValue());
        offer.verifyTradePrice(21000000000L);
    }

    @Test
    public void testErgoOfferSerializationPreservesPaymentAmount() throws Exception {
        PubKeyRing pubKeyRing = new PubKeyRing(Sig.generateKeyPair().getPublic(), Encryption.generateKeyPair().getPublic());
        protobuf.OfferPayload payload = protobuf.OfferPayload.newBuilder()
                .setId("erg-fixed-offer")
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setBaseCurrencyCode("XMR")
                .setCounterCurrencyCode("ERG")
                .setDirection(protobuf.OfferDirection.BUY)
                .setPaymentMethodId(PaymentMethod.BLOCK_CHAINS_ID)
                .setPrice(12312345678L)
                .setAmount(1000000000000L)
                .setMinAmount(1000000000000L)
                .build();
        Offer original = new Offer(OfferPayload.fromProto(payload));
        Offer restored = Offer.fromProto(protobuf.Offer.parseFrom(original.toProtoMessage().toByteArray()));

        assertEquals(original.toProtoMessage(), restored.toProtoMessage());
        assertEquals("XMR", restored.getBaseCurrencyCode());
        assertEquals("ERG", restored.getCounterCurrencyCode());
        assertEquals(PaymentMethod.BLOCK_CHAINS_ID, restored.getPaymentMethodId());
        assertEquals(12312345678L, restored.getPrice().getValue());
        assertEquals("123.12345678 ERG", VolumeUtil.formatVolumeWithCode(restored.getVolume()));
        assertEquals(original.getAmount(), restored.getAmount());
    }

    private OfferPayload ergoPayload(boolean marketBased) {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getId()).thenReturn("erg-price-test");
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("ERG");
        when(payload.isUseMarketBasedPrice()).thenReturn(marketBased);
        when(payload.getDirection()).thenReturn(OfferDirection.BUY);
        return payload;
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
