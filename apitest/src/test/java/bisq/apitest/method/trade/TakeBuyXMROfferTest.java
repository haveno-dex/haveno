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

package bisq.apitest.method.trade;

import io.grpc.StatusRuntimeException;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.config.ApiTestConfig.XMR;
import static bisq.cli.table.builder.TableType.OFFER_TBL;
import static bisq.core.trade.Trade.Phase.PAYMENT_RECEIVED;
import static bisq.core.trade.Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static protobuf.Offer.State.OFFER_FEE_RESERVED;
import static protobuf.OfferDirection.SELL;



import bisq.apitest.method.offer.AbstractOfferTest;
import bisq.cli.table.builder.TableBuilder;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TakeBuyXMROfferTest extends AbstractTradeTest {

    // Alice is maker / xmr buyer (btc seller), Bob is taker / xmr seller (btc buyer).

    @BeforeAll
    public static void setUp() {
        AbstractOfferTest.setUp();
        createXmrPaymentAccounts();
        EXPECTED_PROTOCOL_STATUS.init();
    }

    @Test
    @Order(1)
    public void testTakeAlicesSellBTCForXMROffer(final TestInfo testInfo) {
        try {
            // Alice is going to BUY XMR, but the Offer direction = SELL because it is a
            // BTC trade;  Alice will SELL BTC for XMR.  Bob will send Alice XMR.
            // Confused me, but just need to remember there are only BTC offers.
            var btcTradeDirection = SELL.name();
            var alicesOffer = aliceClient.createFixedPricedOffer(btcTradeDirection,
                    XMR,
                    15_000_000L,
                    7_500_000L,
                    "0.00455500",   // FIXED PRICE IN BTC (satoshis) FOR 1 XMR
                    defaultBuyerSecurityDepositPct.get(),
                    alicesXmrAcct.getId());
            log.debug("Alice's BUY XMR (SELL BTC) Offer:\n{}", new TableBuilder(OFFER_TBL, alicesOffer).build());
            genBtcBlocksThenWait(1, 5000);
            var offerId = alicesOffer.getId();

            var alicesXmrOffers = aliceClient.getMyOffers(btcTradeDirection, XMR);
            assertEquals(1, alicesXmrOffers.size());
            var trade = takeAlicesOffer(offerId, bobsXmrAcct.getId());
            alicesXmrOffers = aliceClient.getMyOffersSortedByDate(XMR);
            assertEquals(0, alicesXmrOffers.size());
            genBtcBlocksThenWait(1, 2_500);
            waitForDepositUnlocked(log, testInfo, bobClient, trade.getTradeId());

            trade = bobClient.getTrade(tradeId);
            verifyTakerDepositConfirmed(trade);
            logTrade(log, testInfo, "Alice's Maker/Buyer View", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Seller View", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(2)
    public void testBobsConfirmPaymentStarted(final TestInfo testInfo) {
        try {
            var trade = bobClient.getTrade(tradeId);

            verifyTakerDepositConfirmed(trade);
            log.debug("Bob sends XMR payment to Alice for trade {}", trade.getTradeId());
            bobClient.confirmPaymentStarted(trade.getTradeId());
            sleep(3500);
            waitForBuyerSeesPaymentInitiatedMessage(log, testInfo, bobClient, tradeId);

            logTrade(log, testInfo, "Alice's Maker/Buyer View (Payment Sent)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Seller View (Payment Sent)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(3)
    public void testAlicesConfirmPaymentReceived(final TestInfo testInfo) {
        try {
            waitForSellerSeesPaymentInitiatedMessage(log, testInfo, aliceClient, tradeId);

            sleep(2_000);
            var trade = aliceClient.getTrade(tradeId);
            // If we were trading BSQ, Alice would verify payment has been sent to her
            // Bisq wallet, but we can do no such checks for XMR payments.
            // All XMR transfers are done outside Bisq.
            log.debug("Alice verifies XMR payment was received from Bob, for trade {}", trade.getTradeId());
            aliceClient.confirmPaymentReceived(trade.getTradeId());
            sleep(3_000);

            trade = aliceClient.getTrade(tradeId);
            assertEquals(OFFER_FEE_RESERVED.name(), trade.getOffer().getState());
            EXPECTED_PROTOCOL_STATUS.setState(SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG)
                    .setPhase(PAYMENT_RECEIVED)
                    .setPayoutPublished(true)
                    .setPaymentReceivedMessageSent(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's Maker/Buyer View (Payment Received)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Seller View (Payment Received)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }
}
