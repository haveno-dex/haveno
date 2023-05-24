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
import haveno.proto.grpc.OfferInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.text.DecimalFormat;

import static haveno.apitest.config.ApiTestConfig.BTC;
import static haveno.apitest.config.ApiTestConfig.USD;
import static haveno.common.util.MathUtils.roundDouble;
import static haveno.common.util.MathUtils.scaleDownByPowerOf10;
import static haveno.common.util.MathUtils.scaleUpByPowerOf10;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
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
public class CreateOfferUsingMarketPriceMarginTest extends AbstractOfferTest {

    private static final DecimalFormat PCT_FORMAT = new DecimalFormat("##0.00");
    private static final double MKT_PRICE_MARGIN_ERROR_TOLERANCE = 0.0050;      // 0.50%
    private static final double MKT_PRICE_MARGIN_WARNING_TOLERANCE = 0.0001;    // 0.01%

    private static final String MAKER_FEE_CURRENCY_CODE = BTC;

    @Test
    @Order(1)
    public void testCreateUSDBTCBuyOffer5PctPriceMargin() {
        PaymentAccount usdAccount = createDummyF2FAccount(aliceClient, "US");
        double priceMarginPctInput = 5.00d;
        var newOffer = aliceClient.createMarketBasedPricedOffer(BUY.name(),
                "usd",
                10_000_000L,
                10_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                usdAccount.getId(),
                NO_TRIGGER_PRICE);
        log.debug("Offer #1:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(usdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals(USD, newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(usdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals(USD, newOffer.getCounterCurrencyCode());

        assertCalculatedPriceIsCorrect(newOffer, priceMarginPctInput);
    }

    @Test
    @Order(2)
    public void testCreateNZDBTCBuyOfferMinus2PctPriceMargin() {
        PaymentAccount nzdAccount = createDummyF2FAccount(aliceClient, "NZ");
        double priceMarginPctInput = -2.00d;    // -2%
        var newOffer = aliceClient.createMarketBasedPricedOffer(BUY.name(),
                "nzd",
                10_000_000L,
                10_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                nzdAccount.getId(),
                NO_TRIGGER_PRICE);
        log.debug("Offer #2:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(nzdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("NZD", newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(BUY.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(10_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(nzdAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("NZD", newOffer.getCounterCurrencyCode());

        assertCalculatedPriceIsCorrect(newOffer, priceMarginPctInput);
    }

    @Test
    @Order(3)
    public void testCreateGBPBTCSellOfferMinus1Point5PctPriceMargin() {
        PaymentAccount gbpAccount = createDummyF2FAccount(aliceClient, "GB");
        double priceMarginPctInput = -1.5;
        var newOffer = aliceClient.createMarketBasedPricedOffer(SELL.name(),
                "gbp",
                10_000_000L,
                5_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                gbpAccount.getId(),
                NO_TRIGGER_PRICE);
        log.debug("Offer #3:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(gbpAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("GBP", newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(gbpAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("GBP", newOffer.getCounterCurrencyCode());

        assertCalculatedPriceIsCorrect(newOffer, priceMarginPctInput);
    }

    @Test
    @Order(4)
    public void testCreateBRLBTCSellOffer6Point55PctPriceMargin() {
        PaymentAccount brlAccount = createDummyF2FAccount(aliceClient, "BR");
        double priceMarginPctInput = 6.55;
        var newOffer = aliceClient.createMarketBasedPricedOffer(SELL.name(),
                "brl",
                10_000_000L,
                5_000_000L,
                priceMarginPctInput,
                defaultBuyerSecurityDepositPct.get(),
                brlAccount.getId(),
                NO_TRIGGER_PRICE);
        log.debug("Offer #4:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        String newOfferId = newOffer.getId();
        assertNotEquals("", newOfferId);
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(brlAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("BRL", newOffer.getCounterCurrencyCode());

        newOffer = aliceClient.getOffer(newOfferId);
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(newOfferId, newOffer.getId());
        assertEquals(SELL.name(), newOffer.getDirection());
        assertTrue(newOffer.getUseMarketBasedPrice());
        assertEquals(priceMarginPctInput, newOffer.getMarketPriceMarginPct());
        assertEquals(10_000_000, newOffer.getAmount());
        assertEquals(5_000_000, newOffer.getMinAmount());
        assertEquals(1_500_000, newOffer.getBuyerSecurityDeposit());
        assertEquals(brlAccount.getId(), newOffer.getPaymentAccountId());
        assertEquals(BTC, newOffer.getBaseCurrencyCode());
        assertEquals("BRL", newOffer.getCounterCurrencyCode());

        assertCalculatedPriceIsCorrect(newOffer, priceMarginPctInput);
    }

    @Test
    @Order(5)
    public void testCreateUSDBTCBuyOfferWithTriggerPrice() {
        PaymentAccount usdAccount = createDummyF2FAccount(aliceClient, "US");
        double mktPriceAsDouble = aliceClient.getBtcPrice("usd");
        String triggerPrice = calcPriceAsString(mktPriceAsDouble, Double.parseDouble("1000.9999"), 4);
        var newOffer = aliceClient.createMarketBasedPricedOffer(BUY.name(),
                "usd",
                10_000_000L,
                5_000_000L,
                0.0,
                defaultBuyerSecurityDepositPct.get(),
                usdAccount.getId(),
                triggerPrice);
        assertTrue(newOffer.getIsMyOffer());
        assertFalse(newOffer.getIsActivated());

        genBtcBlocksThenWait(1, 4000); // give time to add to offer book
        newOffer = aliceClient.getOffer(newOffer.getId());
        log.debug("Offer #5:\n{}", toOfferTable.apply(newOffer));
        assertTrue(newOffer.getIsMyOffer());
        assertTrue(newOffer.getIsActivated());
        assertEquals(triggerPrice, newOffer.getTriggerPrice());
    }

    private void assertCalculatedPriceIsCorrect(OfferInfo offer, double priceMarginPctInput) {
        assertTrue(() -> {
            String counterCurrencyCode = offer.getCounterCurrencyCode();
            double mktPrice = aliceClient.getBtcPrice(counterCurrencyCode);
            double priceAsDouble = Double.parseDouble(offer.getPrice());
            double expectedDiffPct = scaleDownByPowerOf10(priceMarginPctInput, 2);
            double actualDiffPct = offer.getDirection().equals(BUY.name())
                    ? getPercentageDifference(priceAsDouble, mktPrice)
                    : getPercentageDifference(mktPrice, priceAsDouble);
            double pctDiffDelta = abs(expectedDiffPct) - abs(actualDiffPct);
            return isCalculatedPriceWithinErrorTolerance(pctDiffDelta,
                    expectedDiffPct,
                    actualDiffPct,
                    mktPrice,
                    priceAsDouble,
                    offer);
        });
    }

    private double getPercentageDifference(double price1, double price2) {
        return BigDecimal.valueOf(roundDouble((1 - (price1 / price2)), 5))
                .setScale(4, HALF_UP)
                .doubleValue();
    }

    private boolean isCalculatedPriceWithinErrorTolerance(double delta,
                                                          double expectedDiffPct,
                                                          double actualDiffPct,
                                                          double mktPrice,
                                                          double scaledOfferPrice,
                                                          OfferInfo offer) {
        if (abs(delta) > MKT_PRICE_MARGIN_ERROR_TOLERANCE) {
            logCalculatedPricePoppedErrorTolerance(expectedDiffPct,
                    actualDiffPct,
                    mktPrice,
                    scaledOfferPrice);
            log.error(offer.toString());
            return false;
        }

        if (abs(delta) >= MKT_PRICE_MARGIN_WARNING_TOLERANCE) {
            logCalculatedPricePoppedWarningTolerance(expectedDiffPct,
                    actualDiffPct,
                    mktPrice,
                    scaledOfferPrice);
            log.trace(offer.toString());
        }

        return true;
    }

    private void logCalculatedPricePoppedWarningTolerance(double expectedDiffPct,
                                                          double actualDiffPct,
                                                          double mktPrice,
                                                          double scaledOfferPrice) {
        log.warn(format("Calculated price %.4f & mkt price %.4f differ by ~ %s%s,"
                        + " not by %s%s, outside the %s%s warning tolerance,"
                        + " but within the %s%s error tolerance.",
                scaledOfferPrice, mktPrice,
                PCT_FORMAT.format(scaleUpByPowerOf10(actualDiffPct, 2)), "%",
                PCT_FORMAT.format(scaleUpByPowerOf10(expectedDiffPct, 2)), "%",
                PCT_FORMAT.format(scaleUpByPowerOf10(MKT_PRICE_MARGIN_WARNING_TOLERANCE, 2)), "%",
                PCT_FORMAT.format(scaleUpByPowerOf10(MKT_PRICE_MARGIN_ERROR_TOLERANCE, 2)), "%"));
    }

    private void logCalculatedPricePoppedErrorTolerance(double expectedDiffPct,
                                                        double actualDiffPct,
                                                        double mktPrice,
                                                        double scaledOfferPrice) {
        log.error(format("Calculated price %.4f & mkt price %.4f differ by ~ %s%s,"
                        + " not by %s%s, outside the %s%s error tolerance.",
                scaledOfferPrice, mktPrice,
                PCT_FORMAT.format(scaleUpByPowerOf10(actualDiffPct, 2)), "%",
                PCT_FORMAT.format(scaleUpByPowerOf10(expectedDiffPct, 2)), "%",
                PCT_FORMAT.format(scaleUpByPowerOf10(MKT_PRICE_MARGIN_ERROR_TOLERANCE, 2)), "%"));
    }
}
