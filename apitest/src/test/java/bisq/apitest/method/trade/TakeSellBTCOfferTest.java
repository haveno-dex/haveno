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

import bisq.core.payment.PaymentAccount;

import io.grpc.StatusRuntimeException;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.config.ApiTestConfig.BTC;
import static bisq.apitest.config.ApiTestConfig.USD;
import static bisq.core.trade.Trade.Phase.PAYMENT_RECEIVED;
import static bisq.core.trade.Trade.Phase.COMPLETED;
import static bisq.core.trade.Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG;
import static bisq.core.trade.Trade.State.TRADE_COMPLETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static protobuf.Offer.State.OFFER_FEE_RESERVED;
import static protobuf.OfferDirection.SELL;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TakeSellBTCOfferTest extends AbstractTradeTest {

    // Alice is maker/seller, Bob is taker/buyer.

    // Maker and Taker fees are in BTC.
    private static final String TRADE_FEE_CURRENCY_CODE = BTC;

    private static final String WITHDRAWAL_TX_MEMO = "Bob's trade withdrawal";

    @Test
    @Order(1)
    public void testTakeAlicesSellOffer(final TestInfo testInfo) {
        try {
            PaymentAccount alicesUsdAccount = createDummyF2FAccount(aliceClient, "US");
            var alicesOffer = aliceClient.createMarketBasedPricedOffer(SELL.name(),
                    USD,
                    12_500_000L,
                    12_500_000L, // min-amount = amount
                    0.00,
                    defaultBuyerSecurityDepositPct.get(),
                    alicesUsdAccount.getId(),
                    NO_TRIGGER_PRICE);
            var offerId = alicesOffer.getId();

            // Wait for Alice's AddToOfferBook task.
            // Wait times vary;  my logs show >= 2-second delay, but taking sell offers
            // seems to require more time to prepare.
            sleep(3_000); // TODO loop instead of hard code a wait time
            var alicesUsdOffers = aliceClient.getMyOffersSortedByDate(SELL.name(), USD);
            assertEquals(1, alicesUsdOffers.size());

            PaymentAccount bobsUsdAccount = createDummyF2FAccount(bobClient, "US");
            var trade = takeAlicesOffer(offerId,
                    bobsUsdAccount.getId(),
                    false);
            sleep(2_500);  // Allow available offer to be removed from offer book.
            var takeableUsdOffers = bobClient.getOffersSortedByDate(SELL.name(), USD);
            assertEquals(0, takeableUsdOffers.size());
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
            bobClient.confirmPaymentStarted(tradeId);
            sleep(6_000);
            waitForBuyerSeesPaymentInitiatedMessage(log, testInfo, bobClient, tradeId);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(3)
    public void testAlicesConfirmPaymentReceived(final TestInfo testInfo) {
        try {
            waitForSellerSeesPaymentInitiatedMessage(log, testInfo, aliceClient, tradeId);

            var trade = aliceClient.getTrade(tradeId);
            aliceClient.confirmPaymentReceived(trade.getTradeId());
            sleep(3_000);
            trade = aliceClient.getTrade(tradeId);
            assertEquals(OFFER_FEE_RESERVED.name(), trade.getOffer().getState());
            EXPECTED_PROTOCOL_STATUS.setState(SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG)
                    .setPhase(PAYMENT_RECEIVED)
                    .setPayoutPublished(true)
                    .setPaymentReceivedMessageSent(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's view after confirming fiat payment received", trade);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }
}
