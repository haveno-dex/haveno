package bisq.apitest.method.trade;

import bisq.proto.grpc.TradeInfo;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;

import lombok.Getter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

import static bisq.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static bisq.core.trade.Trade.Phase.DEPOSITS_UNLOCKED;
import static bisq.core.trade.Trade.Phase.PAYMENT_SENT;
import static bisq.core.trade.Trade.Phase.PAYMENT_RECEIVED;
import static bisq.core.trade.Trade.State.BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG;
import static bisq.core.trade.Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN;
import static bisq.core.trade.Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG;
import static java.lang.String.format;
import static java.lang.System.out;
import static org.junit.jupiter.api.Assertions.*;



import bisq.apitest.method.offer.AbstractOfferTest;
import bisq.cli.CliMain;
import bisq.cli.GrpcClient;
import bisq.cli.table.builder.TableBuilder;

public class AbstractTradeTest extends AbstractOfferTest {

    public static final ExpectedProtocolStatus EXPECTED_PROTOCOL_STATUS = new ExpectedProtocolStatus();

    // A Trade ID cache for use in @Test sequences.
    @Getter
    protected static String tradeId;

    protected final Supplier<Integer> maxTradeStateAndPhaseChecks = () -> isLongRunningTest ? 10 : 2;
    protected final Function<TradeInfo, String> toTradeDetailTable = (trade) ->
            new TableBuilder(TRADE_DETAIL_TBL, trade).build().toString();
    protected final Function<GrpcClient, String> toUserName = (client) -> client.equals(aliceClient) ? "Alice" : "Bob";

    @BeforeAll
    public static void initStaticFixtures() {
        EXPECTED_PROTOCOL_STATUS.init();
    }

    protected final TradeInfo takeAlicesOffer(String offerId,
                                              String paymentAccountId) {
        return takeAlicesOffer(offerId,
                paymentAccountId,
                true);
    }

    protected final TradeInfo takeAlicesOffer(String offerId,
                                              String paymentAccountId,
                                              boolean generateBtcBlock) {
        @SuppressWarnings("ConstantConditions")
        var trade = bobClient.takeOffer(offerId,
                paymentAccountId);
        assertNotNull(trade);
        assertEquals(offerId, trade.getTradeId());

        // Cache the trade id for the other tests.
        tradeId = trade.getTradeId();

        if (generateBtcBlock)
            genBtcBlocksThenWait(1, 6_000);

        return trade;
    }

    protected final void waitForDepositUnlocked(Logger log,
                                                    TestInfo testInfo,
                                                    GrpcClient grpcClient,
                                                    String tradeId) {
        Predicate<TradeInfo> isTradeInDepositUnlockedStateAndPhase = (t) ->
                t.getState().equals(DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN.name())
                        && t.getPhase().equals(DEPOSITS_UNLOCKED.name());

        String userName = toUserName.apply(grpcClient);
        for (int i = 1; i <= maxTradeStateAndPhaseChecks.get(); i++) {
            TradeInfo trade = grpcClient.getTrade(tradeId);
            if (!isTradeInDepositUnlockedStateAndPhase.test(trade)) {
                log.warn("{} still waiting on trade {} tx {}: DEPOSIT_UNLOCKED_IN_BLOCK_CHAIN, attempt # {}",
                        userName,
                        trade.getShortId(),
                        trade.getMakerDepositTxId(),
                        trade.getTakerDepositTxId(),
                        i);
                genBtcBlocksThenWait(1, 4_000);
            } else {
                EXPECTED_PROTOCOL_STATUS.setState(DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN)
                        .setPhase(DEPOSITS_UNLOCKED)
                        .setDepositPublished(true)
                        .setDepositConfirmed(true);
                verifyExpectedProtocolStatus(trade);
                logTrade(log,
                        testInfo,
                        userName + "'s view after deposit is confirmed",
                        trade);
                break;
            }
        }
    }

    protected final void verifyTakerDepositConfirmed(TradeInfo trade) {
        if (!trade.getIsDepositUnlocked()) {
            fail(format("INVALID_PHASE for trade %s in STATE=%s PHASE=%s, deposit tx never unlocked.",
                    trade.getShortId(),
                    trade.getState(),
                    trade.getPhase()));
        }
    }

    protected final void waitForBuyerSeesPaymentInitiatedMessage(Logger log,
                                                                 TestInfo testInfo,
                                                                 GrpcClient grpcClient,
                                                                 String tradeId) {
        String userName = toUserName.apply(grpcClient);
        for (int i = 1; i <= maxTradeStateAndPhaseChecks.get(); i++) {
            TradeInfo trade = grpcClient.getTrade(tradeId);
            if (!trade.getIsPaymentSent()) {
                log.warn("{} still waiting for trade {} {}, attempt # {}",
                        userName,
                        trade.getShortId(),
                        BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG,
                        i);
                sleep(5_000);
            } else {
                // Do not check trade.getOffer().getState() here because
                // it might be AVAILABLE, not OFFER_FEE_RESERVED.
                EXPECTED_PROTOCOL_STATUS.setState(BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG)
                        .setPhase(PAYMENT_SENT)
                        .setPaymentStartedMessageSent(true);
                verifyExpectedProtocolStatus(trade);
                logTrade(log, testInfo, userName + "'s view after confirming trade payment sent", trade);
                break;
            }
        }
    }

    protected final void waitForSellerSeesPaymentInitiatedMessage(Logger log,
                                                                  TestInfo testInfo,
                                                                  GrpcClient grpcClient,
                                                                  String tradeId) {
        Predicate<TradeInfo> isTradeInPaymentReceiptConfirmedStateAndPhase = (t) ->
                t.getState().equals(SELLER_RECEIVED_PAYMENT_SENT_MSG.name()) &&
                        t.getPhase().equals(PAYMENT_SENT.name());
        String userName = toUserName.apply(grpcClient);
        for (int i = 1; i <= maxTradeStateAndPhaseChecks.get(); i++) {
            TradeInfo trade = grpcClient.getTrade(tradeId);
            if (!isTradeInPaymentReceiptConfirmedStateAndPhase.test(trade)) {
                log.warn("INVALID_PHASE for {}'s trade {} in STATE={} PHASE={}, cannot confirm payment received yet.",
                        userName,
                        trade.getShortId(),
                        trade.getState(),
                        trade.getPhase());
                sleep(10_000);
            } else {
                break;
            }
        }

        TradeInfo trade = grpcClient.getTrade(tradeId);
        if (!isTradeInPaymentReceiptConfirmedStateAndPhase.test(trade)) {
            fail(format("INVALID_PHASE for %s's trade %s in STATE=%s PHASE=%s, cannot confirm payment received.",
                    userName,
                    trade.getShortId(),
                    trade.getState(),
                    trade.getPhase()));
        }
    }

    protected final void verifyExpectedProtocolStatus(TradeInfo trade) {
        assertNotNull(trade);
        assertEquals(EXPECTED_PROTOCOL_STATUS.state.name(), trade.getState());
        assertEquals(EXPECTED_PROTOCOL_STATUS.phase.name(), trade.getPhase());

        if (!isLongRunningTest)
            assertEquals(EXPECTED_PROTOCOL_STATUS.isDepositPublished, trade.getIsDepositPublished());

        assertEquals(EXPECTED_PROTOCOL_STATUS.isDepositConfirmed, trade.getIsDepositUnlocked());
        assertEquals(EXPECTED_PROTOCOL_STATUS.isPaymentStartedMessageSent, trade.getIsPaymentSent());
        assertEquals(EXPECTED_PROTOCOL_STATUS.isPaymentReceivedMessageSent, trade.getIsPaymentReceived());
        assertEquals(EXPECTED_PROTOCOL_STATUS.isPayoutPublished, trade.getIsPayoutPublished());
        assertEquals(EXPECTED_PROTOCOL_STATUS.isCompleted, trade.getIsCompleted());
    }

    protected final void logBalances(Logger log, TestInfo testInfo) {
        var alicesBalances = aliceClient.getBalances();
        log.debug("{} Alice's Current Balances:\n{}",
                testName(testInfo),
                formatBalancesTbls(alicesBalances));
        var bobsBalances = bobClient.getBalances();
        log.debug("{} Bob's Current Balances:\n{}",
                testName(testInfo),
                formatBalancesTbls(bobsBalances));
    }

    protected final void logTrade(Logger log,
                                  TestInfo testInfo,
                                  String description,
                                  TradeInfo trade) {
        if (log.isDebugEnabled()) {
            log.debug(format("%s %s%n%s",
                    testName(testInfo),
                    description,
                    new TableBuilder(TRADE_DETAIL_TBL, trade).build()));
        }
    }

    protected static void runCliGetTrade(String tradeId) {
        out.println("Alice's CLI 'gettrade' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9998", "gettrade", "--trade-id=" + tradeId});
        out.println("Bob's CLI 'gettrade' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9999", "gettrade", "--trade-id=" + tradeId});
    }

    protected static void runCliGetOpenTrades() {
        out.println("Alice's CLI 'gettrades --category=open' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9998", "gettrades", "--category=open"});
        out.println("Bob's CLI 'gettrades --category=open' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9999", "gettrades", "--category=open"});
    }

    protected static void runCliGetClosedTrades() {
        out.println("Alice's CLI 'gettrades --category=closed' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9998", "gettrades", "--category=closed"});
        out.println("Bob's CLI 'gettrades --category=closed' response:");
        CliMain.main(new String[]{"--password=xyz", "--port=9999", "gettrades", "--category=closed"});
    }
}
