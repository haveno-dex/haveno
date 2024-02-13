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

import haveno.apitest.method.trade.AbstractTradeTest;
import haveno.apitest.method.trade.TakeBuyBTCOfferTest;
import haveno.apitest.method.trade.TakeBuyBTCOfferWithNationalBankAcctTest;
import haveno.apitest.method.trade.TakeBuyXMROfferTest;
import haveno.apitest.method.trade.TakeSellBTCOfferTest;
import haveno.apitest.method.trade.TakeSellXMROfferTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;


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
        test.testAlicesConfirmPaymentSent(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(2)
    public void testTakeSellBTCOffer(final TestInfo testInfo) {
        TakeSellBTCOfferTest test = new TakeSellBTCOfferTest();
        test.testTakeAlicesSellOffer(testInfo);
        test.testBobsConfirmPaymentSent(testInfo);
        test.testAlicesConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(4)
    public void testTakeBuyBTCOfferWithNationalBankAcct(final TestInfo testInfo) {
        TakeBuyBTCOfferWithNationalBankAcctTest test = new TakeBuyBTCOfferWithNationalBankAcctTest();
        test.testTakeAlicesBuyOffer(testInfo);
        test.testBankAcctDetailsIncludedInContracts(testInfo);
        test.testAlicesConfirmPaymentSent(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(6)
    public void testTakeBuyXMROffer(final TestInfo testInfo) {
        TakeBuyXMROfferTest test = new TakeBuyXMROfferTest();
        TakeBuyXMROfferTest.createXmrPaymentAccounts();
        test.testTakeAlicesSellBTCForXMROffer(testInfo);
        test.testBobsConfirmPaymentSent(testInfo);
        test.testAlicesConfirmPaymentReceived(testInfo);
    }

    @Test
    @Order(7)
    public void testTakeSellXMROffer(final TestInfo testInfo) {
        TakeSellXMROfferTest test = new TakeSellXMROfferTest();
        TakeBuyXMROfferTest.createXmrPaymentAccounts();
        test.testTakeAlicesBuyBTCForXMROffer(testInfo);
        test.testAlicesConfirmPaymentSent(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }
}
