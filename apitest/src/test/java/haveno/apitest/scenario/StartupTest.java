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

import haveno.apitest.method.CallRateMeteringInterceptorTest;
import haveno.apitest.method.GetMethodHelpTest;
import haveno.apitest.method.GetVersionTest;
import haveno.apitest.method.MethodTest;
import haveno.apitest.method.RegisterDisputeAgentsTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.io.IOException;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.ApiTestRateMeterInterceptorConfig.getTestRateMeterInterceptorConfig;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static haveno.apitest.config.HavenoAppConfig.arbdaemon;
import static haveno.apitest.config.HavenoAppConfig.seednode;
import static haveno.common.file.FileUtil.deleteFileIfExists;
import static org.junit.jupiter.api.Assertions.fail;


@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StartupTest extends MethodTest {

    private static File callRateMeteringConfigFile;

    @BeforeAll
    public static void setUp() {
        try {
            callRateMeteringConfigFile = getTestRateMeterInterceptorConfig();
            startSupportingApps(callRateMeteringConfigFile,
                    false,
                    false,
                    bitcoind, seednode, arbdaemon, alicedaemon);
        } catch (Exception ex) {
            fail(ex);
        }
    }

    @Test
    @Order(1)
    public void testCallRateMeteringInterceptor() {
        CallRateMeteringInterceptorTest test = new CallRateMeteringInterceptorTest();
        test.testGetVersionCall1IsAllowed();
        test.sleep200Milliseconds();
        test.testGetVersionCall2ShouldThrowException();
        test.sleep200Milliseconds();
        test.testGetVersionCall3ShouldThrowException();
        test.sleep200Milliseconds();
        test.testGetVersionCall4IsAllowed();
        sleep(1000); // Wait 1 second before calling getversion in next test.
    }

    @Test
    @Order(2)
    public void testGetVersion() {
        GetVersionTest test = new GetVersionTest();
        test.testGetVersion();
    }

    @Test
    @Order(3)
    public void testRegisterDisputeAgents() {
        RegisterDisputeAgentsTest test = new RegisterDisputeAgentsTest();
        test.testRegisterArbitratorShouldThrowException();
        test.testInvalidDisputeAgentTypeArgShouldThrowException();
        test.testInvalidRegistrationKeyArgShouldThrowException();
        test.testRegisterMediator();
        test.testRegisterRefundAgent();
    }

    @Test
    @Order(4)
    public void testGetCreateOfferHelp() {
        GetMethodHelpTest test = new GetMethodHelpTest();
        test.testGetCreateOfferHelp();
    }

    @AfterAll
    public static void tearDown() {
        try {
            deleteFileIfExists(callRateMeteringConfigFile);
        } catch (IOException ex) {
            log.error(ex.getMessage());
        }
        tearDownScaffold();
    }
}
