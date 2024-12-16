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

package haveno.apitest.scenario;


import haveno.apitest.method.offer.AbstractOfferTest;
import haveno.apitest.method.offer.CancelOfferTest;
import haveno.apitest.method.offer.CreateOfferUsingFixedPriceTest;
import haveno.apitest.method.offer.CreateOfferUsingMarketPriceMarginTest;
import haveno.apitest.method.offer.CreateXMROffersTest;
import haveno.apitest.method.offer.ValidateCreateOfferTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OfferTest extends AbstractOfferTest {

    @BeforeAll
    public static void setUp() {
        setUp(false); // Use setUp(true) for running API daemons in remote debug mode.
    }

    @Test
    @Order(1)
    public void testCreateOfferValidation() {
        ValidateCreateOfferTest test = new ValidateCreateOfferTest();
        test.testAmtTooLargeShouldThrowException();
        test.testNoMatchingEURPaymentAccountShouldThrowException();
        test.testNoMatchingCADPaymentAccountShouldThrowException();
    }

    @Test
    @Order(2)
    public void testCancelOffer() {
        CancelOfferTest test = new CancelOfferTest();
        test.testCancelOffer();
    }

    @Test
    @Order(3)
    public void testCreateOfferUsingFixedPrice() {
        CreateOfferUsingFixedPriceTest test = new CreateOfferUsingFixedPriceTest();
        test.testCreateAUDBTCBuyOfferUsingFixedPrice16000();
        test.testCreateUSDBTCBuyOfferUsingFixedPrice100001234();
        test.testCreateEURBTCSellOfferUsingFixedPrice95001234();
    }

    @Test
    @Order(4)
    public void testCreateOfferUsingMarketPriceMarginPct() {
        CreateOfferUsingMarketPriceMarginTest test = new CreateOfferUsingMarketPriceMarginTest();
        test.testCreateUSDBTCBuyOffer5PctPriceMargin();
        test.testCreateNZDBTCBuyOfferMinus2PctPriceMargin();
        test.testCreateGBPBTCSellOfferMinus1Point5PctPriceMargin();
        test.testCreateBRLBTCSellOffer6Point55PctPriceMargin();
        test.testCreateUSDBTCBuyOfferWithTriggerPrice();
    }

    @Test
    @Order(6)
    public void testCreateXMROffers() {
        CreateXMROffersTest test = new CreateXMROffersTest();
        CreateXMROffersTest.createXmrPaymentAccounts();
        test.testCreateFixedPriceBuy1BTCFor200KXMROffer();
        test.testCreateFixedPriceSell1BTCFor200KXMROffer();
        test.testCreatePriceMarginBasedBuy1BTCOfferWithTriggerPrice();
        test.testCreatePriceMarginBasedSell1BTCOffer();
        test.testGetAllMyXMROffers();
        test.testGetAvailableXMROffers();
    }
}
