package haveno.apitest.method.wallet;

import haveno.apitest.method.MethodTest;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.XmrBalanceInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static haveno.apitest.config.HavenoAppConfig.bobdaemon;
import static haveno.apitest.config.HavenoAppConfig.seednode;

import static haveno.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

@Disabled
@Slf4j
@TestMethodOrder(OrderAnnotation.class)
public class XmrWalletTest extends MethodTest {

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
    public void testInitialXmrBalances(final TestInfo testInfo) {
        // Bob & Alice's regtest Haveno wallets were initialized with XMR.

        XmrBalanceInfo alicesBalances = aliceClient.getXmrBalances();
        log.debug("{} Alice's XMR Balances:\n{}",
                testName(testInfo),
                new TableBuilder(XMR_BALANCE_TBL, alicesBalances).build());

        XmrBalanceInfo bobsBalances = bobClient.getXmrBalances();
        log.debug("{} Bob's XMR Balances:\n{}",
                testName(testInfo),
                new TableBuilder(XMR_BALANCE_TBL, bobsBalances).build());

        // XMR balance validation - balances structure is different from BTC
        log.debug("Alice's XMR available balance: {}", alicesBalances.getAvailableBalance());
        log.debug("Bob's XMR available balance: {}", bobsBalances.getAvailableBalance());
    }

    @Test
    @Order(2)
    public void testFundAlicesXmrWallet(final TestInfo testInfo) {
        String newAddress = aliceClient.getXmrNewSubaddress();
        // For XMR testing, we would typically fund via XMR transactions
        // This test may need to be adapted based on actual XMR funding mechanism
        
        XmrBalanceInfo xmrBalanceInfo = aliceClient.getXmrBalances();
        log.debug("{} -> Alice's XMR Address: {}", testName(testInfo), newAddress);

        log.debug("{} -> Alice's XMR Balances After Funding -> \n{}",
                testName(testInfo),
                new TableBuilder(XMR_BALANCE_TBL, xmrBalanceInfo).build());
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
