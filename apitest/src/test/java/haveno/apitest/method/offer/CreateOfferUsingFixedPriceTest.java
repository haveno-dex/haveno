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

package haveno.apitest.method.offer;

import haveno.core.payment.PaymentAccount;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static haveno.apitest.config.ApiTestConfig.EUR;
import static haveno.apitest.config.ApiTestConfig.USD;
import static haveno.apitest.config.ApiTestConfig.XMR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CreateOfferUsingFixedPriceTest extends AbstractOfferTest {

    @Test
    @Order(1)
    public void testCreateAUDBTCBuyOfferUsingFixedPrice16000() {
        PaymentAccount audAccount = createDummyF2FAccount(aliceClient, "AU");
        var newOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                "aud",
                10_000_000L,
                10_000_000L,
                "36000",
                defaultBuyerSecurityDepositPct.get(),
                audAccount.getId());
        log.debug("Offer #1:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("36000.0000", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals("3600", newOffer.getVolume());
        assertEquals("3600", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(audAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals("AUD", newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("36000.0000", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals("3600", newOffer.getVolume());
        assertEquals("3600", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(audAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals("AUD", newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(2)
    public void testCreateUSDBTCBuyOfferUsingFixedPrice100001234() {
        PaymentAccount usdAccount = createDummyF2FAccount(aliceClient, "US");
        var newOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                "usd",
                10_000_000L,
                10_000_000L,
                "30000.1234",
                defaultBuyerSecurityDepositPct.get(),
                usdAccount.getId());
        log.debug("Offer #2:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("30000.1234", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals("3000", newOffer.getVolume());
        assertEquals("3000", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(usdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(USD, newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("30000.1234", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals("3000", newOffer.getVolume());
        assertEquals("3000", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(usdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(USD, newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(3)
    public void testCreateEURBTCSellOfferUsingFixedPrice95001234() {
        PaymentAccount eurAccount = createDummyF2FAccount(aliceClient, "FR");
        var newOffer = aliceClient.createFixedPricedOffer(SELL.name(),
                "eur",
                10_000_000L,
                5_000_000L,
                "29500.1234",
                defaultBuyerSecurityDepositPct.get(),
                eurAccount.getId());
        log.debug("Offer #3:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(SELL.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("29500.1234", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals("2950", newOffer.getVolume());
        assertEquals("1475", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(eurAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(EUR, newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(SELL.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("29500.1234", newOffer.getPrice());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals("2950", newOffer.getVolume());
        assertEquals("1475", newOffer.getMinVolume());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(eurAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(EUR, newOffer.getCounterCurrencyCode());
    }
}
