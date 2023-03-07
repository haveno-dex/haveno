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

package haveno.apitest;

import java.io.File;

import lombok.extern.slf4j.Slf4j;

import static haveno.apitest.Scaffold.EXIT_FAILURE;
import static haveno.apitest.Scaffold.EXIT_SUCCESS;
import static haveno.apitest.config.ApiTestRateMeterInterceptorConfig.appendCallRateMeteringConfigPathOpt;
import static haveno.apitest.config.ApiTestRateMeterInterceptorConfig.getTestRateMeterInterceptorConfig;
import static haveno.apitest.config.ApiTestRateMeterInterceptorConfig.hasCallRateMeteringConfigPathOpt;
import static java.lang.System.err;
import static java.lang.System.exit;

import haveno.apitest.config.ApiTestConfig;

/**
 * ApiTestMain is a placeholder for the gradle build file, which requires a valid
 * 'mainClassName' property in the :apitest subproject configuration.
 *
 * It has some uses:
 *
 * It can be used to print test scaffolding options:  haveno-apitest --help.
 *
 * It can be used to smoke test your bitcoind environment:  haveno-apitest.
 *
 * It can be used to run the regtest environment for release testing:
 * haveno-test --shutdownAfterTests=false
 *
 * All method, scenario and end-to-end tests are found in the test sources folder.
 *
 * Requires bitcoind v0.19 - v22.
 */
@Slf4j
public class ApiTestMain {

    public static void main(String[] args) {
        if (!hasCallRateMeteringConfigPathOpt(args))
            new ApiTestMain().execute(getAppendedArgs(args));
        else
            new ApiTestMain().execute(args);
    }

    public void execute(String[] args) {
        try {
            log.info("Configuring test harness with options:\n\t{}", String.join("\n\t", args));
            Scaffold scaffold = new Scaffold(args).setUp();
            ApiTestConfig config = scaffold.config;

            if (config.skipTests) {
                log.info("Skipping tests ...");
            } else {
                new SmokeTestBitcoind(config).run();
            }

            if (config.shutdownAfterTests) {
                scaffold.tearDown();
                exit(EXIT_SUCCESS);
            } else {
                log.info("Not shutting down scaffolding background processes will run until ^C / kill -15 is rcvd ...");
            }

        } catch (Throwable ex) {
            err.println("Fault: An unexpected error occurred. " +
                    "Please file a report at https://haveno.network/issues");
            ex.printStackTrace(err);
            exit(EXIT_FAILURE);
        }
    }

    private static String[] getAppendedArgs(String[] args) {
        File rateMeterInterceptorConfig = getTestRateMeterInterceptorConfig();
        return appendCallRateMeteringConfigPathOpt(args, rateMeterInterceptorConfig);
    }
}
