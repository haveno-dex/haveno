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

import static bisq.apitest.config.ApiTestConfig.BTC;
import static bisq.apitest.config.ApiTestConfig.XMR;
import static bisq.cli.table.builder.TableType.OFFER_TBL;
import static bisq.core.trade.Trade.Phase.PAYMENT_RECEIVED;
import static bisq.core.trade.Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static protobuf.OfferDirection.BUY;



import bisq.apitest.method.offer.AbstractOfferTest;
import bisq.cli.table.builder.TableBuilder;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TakeSellXMROfferTest extends AbstractTradeTest {

    // Alice is maker / xmr seller (btc buyer), Bob is taker / xmr buyer (btc seller).

    // Maker and Taker fees are in BTC.
    private static final String TRADE_FEE_CURRENCY_CODE = BTC;

    private static final String WITHDRAWAL_TX_MEMO = "Bob's trade withdrawal";

    @BeforeAll
    public static void setUp() {
        AbstractOfferTest.setUp();
        createXmrPaymentAccounts();
        EXPECTED_PROTOCOL_STATUS.init();
    }

    @Test
    @Order(1)
    public void testTakeAlicesBuyBTCForXMROffer(final TestInfo testInfo) {
        try {
            // Alice is going to SELL XMR, but the Offer direction = BUY because it is a
            // BTC trade;  Alice will BUY BTC for XMR.  Alice will send Bob XMR.
            // Confused me, but just need to remember there are only BTC offers.
            var btcTradeDirection = BUY.name();
            double priceMarginPctInput = 1.50;
            var alicesOffer = aliceClient.createMarketBasedPricedOffer(btcTradeDirection,
                    XMR,
                    20_000_000L,
                    10_500_000L,
                    priceMarginPctInput,
                    defaultBuyerSecurityDepositPct.get(),
                    alicesXmrAcct.getId(),
                    NO_TRIGGER_PRICE);
            log.debug("Alice's SELL XMR (BUY BTC) Offer:\n{}", new TableBuilder(OFFER_TBL, alicesOffer).build());
            genBtcBlocksThenWait(1, 4000);
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
            logTrade(log, testInfo, "Alice's Maker/Seller View", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(2)
    public void testAlicesConfirmPaymentStarted(final TestInfo testInfo) {
        try {
            var trade = aliceClient.getTrade(tradeId);
            waitForDepositUnlocked(log, testInfo, aliceClient, trade.getTradeId());
            log.debug("Alice sends XMR payment to Bob for trade {}", trade.getTradeId());
            aliceClient.confirmPaymentStarted(trade.getTradeId());
            sleep(3500);

            waitForBuyerSeesPaymentInitiatedMessage(log, testInfo, aliceClient, tradeId);
            logTrade(log, testInfo, "Alice's Maker/Seller View (Payment Sent)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View (Payment Sent)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(3)
    public void testBobsConfirmPaymentReceived(final TestInfo testInfo) {
        try {
            waitForSellerSeesPaymentInitiatedMessage(log, testInfo, bobClient, tradeId);

            var trade = bobClient.getTrade(tradeId);
            sleep(2_000);
            // If we were trading BTC, Bob would verify payment has been sent to his
            // Bisq wallet, but we can do no such checks for XMR payments.
            // All XMR transfers are done outside Bisq.
            log.debug("Bob verifies XMR payment was received from Alice, for trade {}", trade.getTradeId());
            bobClient.confirmPaymentReceived(trade.getTradeId());
            sleep(3_000);

            trade = bobClient.getTrade(tradeId);
            // Warning:  trade.getOffer().getState() might be AVAILABLE, not OFFER_FEE_RESERVED.
            EXPECTED_PROTOCOL_STATUS.setState(SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG)
                    .setPhase(PAYMENT_RECEIVED)
                    .setPayoutPublished(true)
                    .setPaymentReceivedMessageSent(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's Maker/Seller View (Payment Received)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Buyer View (Payment Received)", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }
}
