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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;



import bisq.apitest.method.trade.AbstractTradeTest;
import bisq.apitest.method.trade.TakeBuyBTCOfferTest;
import bisq.apitest.method.trade.TakeBuyBTCOfferWithNationalBankAcctTest;
import bisq.apitest.method.trade.TakeBuyXMROfferTest;
import bisq.apitest.method.trade.TakeSellBTCOfferTest;
import bisq.apitest.method.trade.TakeSellXMROfferTest;


@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TradeTest extends AbstractTradeTest {

    @BeforeEach
    public void init() {
        EXPECTED_PROTOCOL_STATUS.init();
    }

    @Test
    @Order(1)
    public void testTakeBuyBTCOffer(final TestInfo testInfo) {
        TakeBuyBTCOfferTest test = new TakeBuyBTCOfferTest();
        test.testTakeAlicesBuyOffer(testInfo);
        test.testAlicesConfirmPaymentStarted(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(2)
    public void testTakeSellBTCOffer(final TestInfo testInfo) {
        TakeSellBTCOfferTest test = new TakeSellBTCOfferTest();
        test.testTakeAlicesSellOffer(testInfo);
        test.testBobsConfirmPaymentStarted(testInfo);
        test.testAlicesConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(4)
    public void testTakeBuyBTCOfferWithNationalBankAcct(final TestInfo testInfo) {
        TakeBuyBTCOfferWithNationalBankAcctTest test = new TakeBuyBTCOfferWithNationalBankAcctTest();
        test.testTakeAlicesBuyOffer(testInfo);
        test.testBankAcctDetailsIncludedInContracts(testInfo);
        test.testAlicesConfirmPaymentStarted(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(6)
    public void testTakeBuyXMROffer(final TestInfo testInfo) {
        TakeBuyXMROfferTest test = new TakeBuyXMROfferTest();
        TakeBuyXMROfferTest.createXmrPaymentAccounts();
        test.testTakeAlicesSellBTCForXMROffer(testInfo);
        test.testBobsConfirmPaymentStarted(testInfo);
        test.testAlicesConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(7)
    public void testTakeSellXMROffer(final TestInfo testInfo) {
        TakeSellXMROfferTest test = new TakeSellXMROfferTest();
        TakeBuyXMROfferTest.createXmrPaymentAccounts();
        test.testTakeAlicesBuyBTCForXMROffer(testInfo);
        test.testAlicesConfirmPaymentStarted(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }
}
