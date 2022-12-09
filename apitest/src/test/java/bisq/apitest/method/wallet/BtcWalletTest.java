package bisq.apitest.method.wallet;

import bisq.proto.grpc.BtcBalanceInfo;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static bisq.apitest.config.HavenoAppConfig.alicedaemon;
import static bisq.apitest.config.HavenoAppConfig.bobdaemon;
import static bisq.apitest.config.HavenoAppConfig.seednode;
import static bisq.apitest.method.wallet.WalletTestUtil.INITIAL_BTC_BALANCES;
import static bisq.apitest.method.wallet.WalletTestUtil.verifyBtcBalances;
import static bisq.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static bisq.cli.table.builder.TableType.BTC_BALANCE_TBL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.MethodOrderer.OrderAnnotation;



import bisq.apitest.method.MethodTest;
import bisq.cli.table.builder.TableBuilder;

@Disabled
@Slf4j
@TestMethodOrder(OrderAnnotation.class)
public class BtcWalletTest extends MethodTest {

    private static final String TX_MEMO = "tx memo";

    @BeforeAll
    public static void setUp() {
        startSupportingApps(false,
                true,
                bitcoind,
                seednode,
                alicedaemon,
                bobdaemon);
    }

    @Test
    @Order(1)
    public void testInitialBtcBalances(final TestInfo testInfo) {
        // Bob & Alice's regtest Bisq wallets were initialized with 10 BTC.

        BtcBalanceInfo alicesBalances = aliceClient.getBtcBalances();
        log.debug("{} Alice's BTC Balances:\n{}",
                testName(testInfo),
                new TableBuilder(BTC_BALANCE_TBL, alicesBalances).build());

        BtcBalanceInfo bobsBalances = bobClient.getBtcBalances();
        log.debug("{} Bob's BTC Balances:\n{}",
                testName(testInfo),
                new TableBuilder(BTC_BALANCE_TBL, bobsBalances).build());

        assertEquals(INITIAL_BTC_BALANCES.getAvailableBalance(), alicesBalances.getAvailableBalance());
        assertEquals(INITIAL_BTC_BALANCES.getAvailableBalance(), bobsBalances.getAvailableBalance());
    }

    @Test
    @Order(2)
    public void testFundAlicesBtcWallet(final TestInfo testInfo) {
        String newAddress = aliceClient.getUnusedBtcAddress();
        bitcoinCli.sendToAddress(newAddress, "2.5");
        genBtcBlocksThenWait(1, 1000);

        BtcBalanceInfo btcBalanceInfo = aliceClient.getBtcBalances();
        // New balance is 12.5 BTC
        assertEquals(1250000000, btcBalanceInfo.getAvailableBalance());

        log.debug("{} -> Alice's Funded Address Balance -> \n{}",
                testName(testInfo),
                new TableBuilder(ADDRESS_BALANCE_TBL,
                        aliceClient.getAddressBalance(newAddress)));

        // New balance is 12.5 BTC
        btcBalanceInfo = aliceClient.getBtcBalances();
        bisq.core.api.model.BtcBalanceInfo alicesExpectedBalances =
                bisq.core.api.model.BtcBalanceInfo.valueOf(1250000000,
                        0,
                        1250000000,
                        0);
        verifyBtcBalances(alicesExpectedBalances, btcBalanceInfo);
        log.debug("{} -> Alice's BTC Balances After Sending 2.5 BTC -> \n{}",
                testName(testInfo),
                new TableBuilder(BTC_BALANCE_TBL, btcBalanceInfo).build());
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
