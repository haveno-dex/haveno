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

package haveno.apitest.scenario;

import haveno.apitest.method.trade.AbstractTradeTest;
import haveno.apitest.method.trade.TakeBuyBTCOfferTest;
import haveno.apitest.method.trade.TakeSellBTCOfferTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import static java.lang.System.getenv;

@EnabledIf("envLongRunningTestEnabled")
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LongRunningTradesTest extends AbstractTradeTest {

    @Test
    @Order(1)
    public void TradeLoop(final TestInfo testInfo) {
        int numTrades = 0;
        while (numTrades < 50) {

            log.info("*******************************************************************");
            log.info("Trade # {}", ++numTrades);
            log.info("*******************************************************************");

            EXPECTED_PROTOCOL_STATUS.init();
            testTakeBuyBTCOffer(testInfo);

            genBtcBlocksThenWait(1, 1000 * 15);

            log.info("*******************************************************************");
            log.info("Trade # {}", ++numTrades);
            log.info("*******************************************************************");

            EXPECTED_PROTOCOL_STATUS.init();
            testTakeSellBTCOffer(testInfo);

            genBtcBlocksThenWait(1, 1000 * 15);
        }
    }

    public void testTakeBuyBTCOffer(final TestInfo testInfo) {
        TakeBuyBTCOfferTest test = new TakeBuyBTCOfferTest();
        setLongRunningTest(true);
        test.testTakeAlicesBuyOffer(testInfo);
        test.testAlicesConfirmPaymentSent(testInfo);
        test.testBobsConfirmPaymentReceived(testInfo);
    }

    public void testTakeSellBTCOffer(final TestInfo testInfo) {
        TakeSellBTCOfferTest test = new TakeSellBTCOfferTest();
        setLongRunningTest(true);
        test.testTakeAlicesSellOffer(testInfo);
        test.testBobsConfirmPaymentSent(testInfo);
        test.testAlicesConfirmPaymentReceived(testInfo);
    }

    protected static boolean envLongRunningTestEnabled() {
        String envName = "LONG_RUNNING_TRADES_TEST_ENABLED";
        String envX = getenv(envName);
        if (envX != null) {
            log.info("Enabled, found {}.", envName);
            return true;
        } else {
            log.info("Skipped, no environment variable {} defined.", envName);
            log.info("To enable on Mac OS or Linux:"
                    + "\tIf running in terminal, export LONG_RUNNING_TRADES_TEST_ENABLED=true in bash shell."
                    + "\tIf running in Intellij, set LONG_RUNNING_TRADES_TEST_ENABLED=true in launcher's Environment variables field.");
            return false;
        }
    }
}
