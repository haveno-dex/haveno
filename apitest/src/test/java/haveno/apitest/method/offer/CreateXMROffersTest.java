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

import haveno.proto.grpc.OfferInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static haveno.apitest.config.ApiTestConfig.BTC;
import static haveno.apitest.config.ApiTestConfig.XMR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

@SuppressWarnings("ConstantConditions")
@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CreateXMROffersTest extends AbstractOfferTest {

    private static final String MAKER_FEE_CURRENCY_CODE = BTC;

    @BeforeAll
    public static void setUp() {
        AbstractOfferTest.setUp();
        createXmrPaymentAccounts();
    }

    @Test
    @Order(1)
    public void testCreateFixedPriceBuy1BTCFor200KXMROffer() {
        // Remember alt coin trades are BTC trades.  When placing an offer, you are
        // offering to buy or sell BTC, not ETH, XMR, etc.  In this test case,
        // Alice places an offer to BUY BTC.
        var newOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                XMR,
                100_000_000L,
                75_000_000L,
                "0.005",   // FIXED PRICE IN BTC FOR 1 XMR
                defaultBuyerSecurityDepositPct.get(),
                alicesXmrAcct.getId());
        log.debug("Sell XMR (Buy BTC) offer:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("0.00500000", newOffer.getPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(75_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());

        genBtcBlockAndWaitForOfferPreparation();

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("0.00500000", newOffer.getPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(75_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(2)
    public void testCreateFixedPriceSell1BTCFor200KXMROffer() {
        // Alice places an offer to SELL BTC for XMR.
        var newOffer = aliceClient.createFixedPricedOffer(SELL.name(),
                XMR,
                100_000_000L,
                50_000_000L,
                "0.005",   // FIXED PRICE IN BTC (satoshis) FOR 1 XMR
                defaultBuyerSecurityDepositPct.get(),
                alicesXmrAcct.getId());
        log.debug("Buy XMR (Sell BTC) offer:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(SELL.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("0.00500000", newOffer.getPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(50_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());

        genBtcBlockAndWaitForOfferPreparation();

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(SELL.name(), newOffer.getDirection());
        assertFalse(newOffer.getUseMarketBasedPrice());
        assertEquals("0.00500000", newOffer.getPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(50_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(3)
    public void testCreatePriceMarginBasedBuy1BTCOfferWithTriggerPrice() {
        double priceMarginPctInput = 1.00;
        double mktPriceAsDouble = aliceClient.getBtcPrice(XMR);
        String triggerPrice = calcPriceAsString(mktPriceAsDouble, Double.parseDouble("-0.001"), 8);
        var newOffer = aliceClient.createMarketBasedPricedOffer(BUY.name(),
                XMR,
                100_000_000L,
                75_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                alicesXmrAcct.getId(),
                triggerPrice);
        log.debug("Pending Sell XMR (Buy BTC) offer:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());

        // There is no trigger price while offer is pending.
        assertEquals(NO_TRIGGER_PRICE, newOffer.getTriggerPrice());

        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(75_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());

        genBtcBlockAndWaitForOfferPreparation();

        newOffer = aliceClient.getOffer(newOfferId);
        log.debug("Available Sell XMR (Buy BTC) offer:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());

        // The trigger price should exist on the prepared offer.
        assertEquals(triggerPrice, newOffer.getTriggerPrice());

        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(75_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(4)
    public void testCreatePriceMarginBasedSell1BTCOffer() {
        // Alice places an offer to SELL BTC for XMR.
        double priceMarginPctInput = 0.50;
        var newOffer = aliceClient.createMarketBasedPricedOffer(SELL.name(),
                XMR,
                100_000_000L,
                50_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                alicesXmrAcct.getId(),
                NO_TRIGGER_PRICE);
        log.debug("Buy XMR (Sell BTC) offer:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(50_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());

        genBtcBlockAndWaitForOfferPreparation();

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(100_000_000L, newOffer.getAmount());
        assertEquals(50_000_000L, newOffer.getMinAmount());
        assertEquals(.15, newOffer.getBuyerSecurityDepositPct());
        assertEquals(.15, newOffer.getSellerSecurityDepositPct());
        assertEquals(alicesXmrAcct.getId(), newOffer.getPaymentAccountId());
        assertEquals(XMR, newOffer.getBaseCurrencyCode());
        assertEquals(BTC, newOffer.getCounterCurrencyCode());
    }

    @Test
    @Order(5)
    public void testGetAllMyXMROffers() {
        List<OfferInfo> offers = aliceClient.getMyOffersSortedByDate(XMR);
        log.debug("All of Alice's XMR offers:\n{}", toOffersTable.apply(offers));
        assertEquals(4, offers.size());
        log.debug("Alice's balances\n{}", formatBalancesTbls(aliceClient.getBalances()));
    }

    @Test
    @Order(6)
    public void testGetAvailableXMROffers() {
        List<OfferInfo> offers = bobClient.getOffersSortedByDate(XMR);
        log.debug("All of Bob's available XMR offers:\n{}", toOffersTable.apply(offers));
        assertEquals(4, offers.size());
        log.debug("Bob's balances\n{}", formatBalancesTbls(bobClient.getBalances()));
    }

    private void genBtcBlockAndWaitForOfferPreparation() {
        genBtcBlocksThenWait(1, 5000);
    }
}
