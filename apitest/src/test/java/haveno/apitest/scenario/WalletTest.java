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

import haveno.apitest.method.MethodTest;
import haveno.apitest.method.wallet.BtcWalletTest;
import haveno.apitest.method.wallet.WalletProtectionTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static haveno.apitest.config.HavenoAppConfig.arbdaemon;
import static haveno.apitest.config.HavenoAppConfig.bobdaemon;
import static haveno.apitest.config.HavenoAppConfig.seednode;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WalletTest extends MethodTest {

    // Batching all wallet tests in this test case reduces scaffold setup
    // time.  Here, we create a method WalletProtectionTest instance and run each
    // test in declared order.

    @BeforeAll
    public static void setUp() {
        startSupportingApps(true,
                false,
                bitcoind,
                seednode,
                arbdaemon,
                alicedaemon,
                bobdaemon);
    }

    @Test
    @Order(1)
    public void testBtcWalletFunding(final TestInfo testInfo) {
        BtcWalletTest btcWalletTest = new BtcWalletTest();

        btcWalletTest.testInitialBtcBalances(testInfo);
        btcWalletTest.testFundAlicesBtcWallet(testInfo);
    }

    @Test
    @Order(3)
    public void testWalletProtection() {
        WalletProtectionTest walletProtectionTest = new WalletProtectionTest();

        walletProtectionTest.testSetWalletPassword();
        walletProtectionTest.testGetBalanceOnEncryptedWalletShouldThrowException();
        walletProtectionTest.testUnlockWalletFor4Seconds();
        walletProtectionTest.testGetBalanceAfterUnlockTimeExpiryShouldThrowException();
        walletProtectionTest.testLockWalletBeforeUnlockTimeoutExpiry();
        walletProtectionTest.testLockWalletWhenWalletAlreadyLockedShouldThrowException();
        walletProtectionTest.testUnlockWalletTimeoutOverride();
        walletProtectionTest.testSetNewWalletPassword();
        walletProtectionTest.testSetNewWalletPasswordWithIncorrectNewPasswordShouldThrowException();
        walletProtectionTest.testRemoveNewWalletPassword();
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
