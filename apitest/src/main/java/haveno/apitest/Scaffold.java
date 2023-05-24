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

import haveno.apitest.config.ApiTestConfig;
import haveno.apitest.config.HavenoAppConfig;
import haveno.apitest.linux.BashCommand;
import haveno.apitest.linux.BitcoinDaemon;
import haveno.apitest.linux.HavenoProcess;
import haveno.apitest.linux.LinuxProcess;
import haveno.cli.GrpcClient;
import haveno.common.config.HavenoHelpFormatter;
import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.ApiTestConfig.MEDIATOR;
import static haveno.apitest.config.ApiTestConfig.REFUND_AGENT;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static haveno.apitest.config.HavenoAppConfig.alicedesktop;
import static haveno.apitest.config.HavenoAppConfig.arbdaemon;
import static haveno.apitest.config.HavenoAppConfig.arbdesktop;
import static haveno.apitest.config.HavenoAppConfig.bobdaemon;
import static haveno.apitest.config.HavenoAppConfig.bobdesktop;
import static haveno.apitest.config.HavenoAppConfig.seednode;
import static haveno.common.app.DevEnv.DEV_PRIVILEGE_PRIV_KEY;
import static java.lang.String.format;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.net.InetAddress.getLoopbackAddress;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public class Scaffold {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_FAILURE = 1;

    public enum BitcoinCoreApp {
        bitcoind
    }

    public final ApiTestConfig config;

    @Nullable
    private SetupTask bitcoindTask;
    @Nullable
    private Future<SetupTask.Status> bitcoindTaskFuture;
    @Nullable
    private SetupTask seedNodeTask;
    @Nullable
    private Future<SetupTask.Status> seedNodeTaskFuture;
    @Nullable
    private SetupTask arbNodeTask;
    @Nullable
    private Future<SetupTask.Status> arbNodeTaskFuture;
    @Nullable
    private SetupTask aliceNodeTask;
    @Nullable
    private Future<SetupTask.Status> aliceNodeTaskFuture;
    @Nullable
    private SetupTask bobNodeTask;
    @Nullable
    private Future<SetupTask.Status> bobNodeTaskFuture;

    private final ExecutorService executor;

    /**
     * Constructor for passing comma delimited list of supporting apps to
     * ApiTestConfig, e.g., "bitcoind,seednode,arbdaemon,alicedaemon,bobdaemon".
     *
     * @param supportingApps String
     */
    public Scaffold(String supportingApps) {
        this(new ApiTestConfig("--supportingApps", supportingApps));
    }

    /**
     * Constructor for passing options accepted by ApiTestConfig.
     *
     * @param args String[]
     */
    public Scaffold(String[] args) {
        this(new ApiTestConfig(args));
    }

    /**
     * Constructor for passing ApiTestConfig instance.
     *
     * @param config ApiTestConfig
     */
    public Scaffold(ApiTestConfig config) {
        verifyNotWindows();
        this.config = config;
        this.executor = Executors.newFixedThreadPool(config.supportingApps.size());
        if (config.helpRequested) {
            config.printHelp(out,
                    new HavenoHelpFormatter(
                            "Haveno ApiTest",
                            "haveno-apitest",
                            "0.1.0"));
            exit(EXIT_SUCCESS);
        }
    }


    public Scaffold setUp() throws IOException, InterruptedException, ExecutionException {

        // Start each background process from an executor, then add a shutdown hook.
        CountDownLatch countdownLatch = new CountDownLatch(config.supportingApps.size());
        startBackgroundProcesses(executor, countdownLatch);
        installShutdownHook();

        // Wait for all submitted startup tasks to decrement the count of the latch.
        Objects.requireNonNull(countdownLatch).await();

        // Verify each startup task's future is done.
        verifyStartupCompleted();

        maybeRegisterDisputeAgents();
        return this;
    }

    public void tearDown() {
        if (!executor.isTerminated()) {
            try {
                log.info("Shutting down executor service ...");
                executor.shutdownNow();
                //noinspection ResultOfMethodCallIgnored
                executor.awaitTermination(config.supportingApps.size() * 2000L, MILLISECONDS);

                SetupTask[] orderedTasks = new SetupTask[]{
                        bobNodeTask, aliceNodeTask, arbNodeTask, seedNodeTask, bitcoindTask};
                Optional<Throwable> firstException = shutDownAll(orderedTasks);

                if (firstException.isPresent())
                    throw new IllegalStateException(
                            "There were errors shutting down one or more background instances.",
                            firstException.get());
                else
                    log.info("Teardown complete");

            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private Optional<Throwable> shutDownAll(SetupTask[] orderedTasks) {
        Optional<Throwable> firstException = Optional.empty();
        for (SetupTask t : orderedTasks) {
            if (t != null && t.getLinuxProcess() != null) {
                try {
                    LinuxProcess p = t.getLinuxProcess();
                    p.shutdown();
                    MILLISECONDS.sleep(1000);
                    if (p.hasShutdownExceptions()) {
                        // We log shutdown exceptions, but do not throw any from here
                        // because all the background instances must be shut down.
                        p.logExceptions(p.getShutdownExceptions(), log);

                        // We cache only the 1st shutdown exception and move on to the
                        // next process to be shutdown.  This cached exception will be the
                        // one thrown to the calling test case (the @AfterAll method).
                        if (!firstException.isPresent())
                            firstException = Optional.of(p.getShutdownExceptions().get(0));
                    }
                } catch (InterruptedException ignored) {
                    // empty
                }
            }
        }
        return firstException;
    }

    private void installBitcoinBlocknotify() {
        // gradle is not working for this
        try {
            Path srcPath = Paths.get(config.baseSrcResourcesDir, "blocknotify");
            Path destPath = Paths.get(config.bitcoinDatadir, "blocknotify");
            Files.copy(srcPath, destPath, REPLACE_EXISTING);
            String chmod700Perms = "rwx------";
            Files.setPosixFilePermissions(destPath, PosixFilePermissions.fromString(chmod700Perms));
            log.info("Installed {} with perms {}.", destPath, chmod700Perms);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void installCallRateMeteringConfiguration(String dataDir) throws IOException, InterruptedException {
        if (config.callRateMeteringConfigPath.isEmpty())
            return;

        File testRateMeteringFile = new File(config.callRateMeteringConfigPath);
        if (!testRateMeteringFile.exists())
            throw new FileNotFoundException(
                    format("Call rate metering config file '%s' not found", config.callRateMeteringConfigPath));

        BashCommand copyRateMeteringConfigFile = new BashCommand(
                "cp -rf " + config.callRateMeteringConfigPath + " " + dataDir);
        if (copyRateMeteringConfigFile.run().getExitStatus() != 0)
            throw new IllegalStateException(
                    format("Could not install %s file in %s",
                            testRateMeteringFile.getAbsolutePath(), dataDir));

        Path destPath = Paths.get(dataDir, testRateMeteringFile.getName());
        String chmod700Perms = "rwx------";
        Files.setPosixFilePermissions(destPath, PosixFilePermissions.fromString(chmod700Perms));
        log.info("Installed {} with perms {}.", destPath, chmod700Perms);
    }

    private void installShutdownHook() {
        // Background apps can be left running until the jvm is manually shutdown,
        // so we add a shutdown hook for that use case.
        Runtime.getRuntime().addShutdownHook(new Thread(this::tearDown));
    }

    // Starts bitcoind and haveno apps (seednode, arbnode, etc...)
    private void startBackgroundProcesses(ExecutorService executor,
                                          CountDownLatch countdownLatch)
            throws InterruptedException, IOException {

        log.info("Starting supporting apps {}", config.supportingApps.toString());

        if (config.hasSupportingApp(bitcoind.name())) {
            BitcoinDaemon bitcoinDaemon = new BitcoinDaemon(config);
            bitcoinDaemon.verifyBitcoinPathsExist(true);
            bitcoindTask = new SetupTask(bitcoinDaemon, countdownLatch);
            bitcoindTaskFuture = executor.submit(bitcoindTask);
            MILLISECONDS.sleep(config.havenoAppInitTime);

            LinuxProcess bitcoindProcess = bitcoindTask.getLinuxProcess();
            if (bitcoindProcess.hasStartupExceptions()) {
                bitcoindProcess.logExceptions(bitcoindProcess.getStartupExceptions(), log);
                throw new IllegalStateException(bitcoindProcess.getStartupExceptions().get(0));
            }

            bitcoinDaemon.verifyBitcoindRunning();
        }

        // Start Haveno apps defined by the supportingApps option, in the in proper order.

        if (config.hasSupportingApp(seednode.name()))
            startHavenoApp(seednode, executor, countdownLatch);

        if (config.hasSupportingApp(arbdaemon.name()))
            startHavenoApp(arbdaemon, executor, countdownLatch);
        else if (config.hasSupportingApp(arbdesktop.name()))
            startHavenoApp(arbdesktop, executor, countdownLatch);

        if (config.hasSupportingApp(alicedaemon.name()))
            startHavenoApp(alicedaemon, executor, countdownLatch);
        else if (config.hasSupportingApp(alicedesktop.name()))
            startHavenoApp(alicedesktop, executor, countdownLatch);

        if (config.hasSupportingApp(bobdaemon.name()))
            startHavenoApp(bobdaemon, executor, countdownLatch);
        else if (config.hasSupportingApp(bobdesktop.name()))
            startHavenoApp(bobdesktop, executor, countdownLatch);
    }

    private void startHavenoApp(HavenoAppConfig HavenoAppConfig,
                              ExecutorService executor,
                              CountDownLatch countdownLatch)
            throws IOException, InterruptedException {

        HavenoProcess HavenoProcess = createHavenoProcess(HavenoAppConfig);
        switch (HavenoAppConfig) {
            case seednode:
                seedNodeTask = new SetupTask(HavenoProcess, countdownLatch);
                seedNodeTaskFuture = executor.submit(seedNodeTask);
                break;
            case arbdaemon:
            case arbdesktop:
                arbNodeTask = new SetupTask(HavenoProcess, countdownLatch);
                arbNodeTaskFuture = executor.submit(arbNodeTask);
                break;
            case alicedaemon:
            case alicedesktop:
                aliceNodeTask = new SetupTask(HavenoProcess, countdownLatch);
                aliceNodeTaskFuture = executor.submit(aliceNodeTask);
                break;
            case bobdaemon:
            case bobdesktop:
                bobNodeTask = new SetupTask(HavenoProcess, countdownLatch);
                bobNodeTaskFuture = executor.submit(bobNodeTask);
                break;
            default:
                throw new IllegalStateException("Unknown HavenoAppConfig " + HavenoAppConfig.name());
        }
        log.info("Giving {} ms for {} to initialize ...", config.havenoAppInitTime, HavenoAppConfig.appName);
        MILLISECONDS.sleep(config.havenoAppInitTime);
        if (HavenoProcess.hasStartupExceptions()) {
            HavenoProcess.logExceptions(HavenoProcess.getStartupExceptions(), log);
            throw new IllegalStateException(HavenoProcess.getStartupExceptions().get(0));
        }
    }

    private HavenoProcess createHavenoProcess(HavenoAppConfig HavenoAppConfig)
            throws IOException, InterruptedException {
        HavenoProcess HavenoProcess = new HavenoProcess(HavenoAppConfig, config);
        HavenoProcess.verifyAppNotRunning();
        HavenoProcess.verifyAppDataDirInstalled();
        return HavenoProcess;
    }

    private void verifyStartupCompleted()
            throws ExecutionException, InterruptedException {
        if (bitcoindTaskFuture != null)
            verifyStartupCompleted(bitcoindTaskFuture);

        if (seedNodeTaskFuture != null)
            verifyStartupCompleted(seedNodeTaskFuture);

        if (arbNodeTaskFuture != null)
            verifyStartupCompleted(arbNodeTaskFuture);

        if (aliceNodeTaskFuture != null)
            verifyStartupCompleted(aliceNodeTaskFuture);

        if (bobNodeTaskFuture != null)
            verifyStartupCompleted(bobNodeTaskFuture);
    }

    private void verifyStartupCompleted(Future<SetupTask.Status> futureStatus)
            throws ExecutionException, InterruptedException {
        for (int i = 0; i < 10; i++) {
            if (futureStatus.isDone()) {
                log.info("{} completed startup at {} {}",
                        futureStatus.get().getName(),
                        futureStatus.get().getStartTime().toLocalDate(),
                        futureStatus.get().getStartTime().toLocalTime());
                return;
            } else {
                // We are giving the thread more time to terminate after the countdown
                // latch reached 0.  If we are running only bitcoind, we need to be even
                // more lenient.
                SECONDS.sleep(config.supportingApps.size() == 1 ? 2 : 1);
            }
        }
        throw new IllegalStateException(format("%s did not complete startup", futureStatus.get().getName()));
    }

    private void verifyNotWindows() {
        if (Utilities.isWindows())
            throw new IllegalStateException("ApiTest not supported on Windows");
    }

    private void maybeRegisterDisputeAgents() {
        if (config.hasSupportingApp(arbdaemon.name()) && config.registerDisputeAgents) {
            log.info("Option --registerDisputeAgents=true, registering dispute agents in arbdaemon ...");
            GrpcClient arbClient = new GrpcClient(getLoopbackAddress().getHostAddress(),
                    arbdaemon.apiPort,
                    config.apiPassword);
            arbClient.registerDisputeAgent(MEDIATOR, DEV_PRIVILEGE_PRIV_KEY);
            arbClient.registerDisputeAgent(REFUND_AGENT, DEV_PRIVILEGE_PRIV_KEY);
        }
    }
}
