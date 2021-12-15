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

package bisq.apitest.scenario;


import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;



import bisq.apitest.method.offer.AbstractOfferTest;
import bisq.apitest.method.offer.CancelOfferTest;
import bisq.apitest.method.offer.CreateOfferUsingFixedPriceTest;
import bisq.apitest.method.offer.CreateOfferUsingMarketPriceMarginTest;
import bisq.apitest.method.offer.ValidateCreateOfferTest;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OfferTest extends AbstractOfferTest {

    @Test
    @Order(1)
    public void testAmtTooLargeShouldThrowException() {
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
        test.testCreateAUDXMRBuyOfferUsingFixedPrice16000();
        test.testCreateUSDXMRBuyOfferUsingFixedPrice100001234();
        test.testCreateEURXMRSellOfferUsingFixedPrice95001234();
    }

    @Test
    @Order(4)
    public void testCreateOfferUsingMarketPriceMargin() {
        CreateOfferUsingMarketPriceMarginTest test = new CreateOfferUsingMarketPriceMarginTest();
        test.testCreateUSDXMRBuyOffer5PctPriceMargin();
        test.testCreateNZDXMRBuyOfferMinus2PctPriceMargin();
        test.testCreateGBPXMRSellOfferMinus1Point5PctPriceMargin();
        test.testCreateBRLXMRSellOffer6Point55PctPriceMargin();
    }
}
