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

package bisq.core.app;

import bisq.core.api.AccountServiceListener;
import bisq.core.api.CoreAccountService;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.setup.CorePersistedDataHost;
import bisq.core.setup.CoreSetup;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.TradeManager;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.trade.txproof.xmr.XmrTxProofService;

import bisq.network.p2p.P2PService;

import bisq.common.UserThread;
import bisq.common.app.AppModule;
import bisq.common.config.Config;
import bisq.common.config.ConfigException;
import bisq.common.config.HavenoHelpFormatter;
import bisq.common.crypto.IncorrectPasswordException;
import bisq.common.handlers.ResultHandler;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.setup.CommonSetup;
import bisq.common.setup.GracefulShutDownHandler;
import bisq.common.setup.UncaughtExceptionHandler;
import bisq.common.util.Utilities;

import com.google.inject.Guice;
import com.google.inject.Injector;

import java.io.Console;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public abstract class HavenoExecutable implements GracefulShutDownHandler, HavenoSetup.HavenoSetupListener, UncaughtExceptionHandler {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_FAILURE = 1;
    public static final int EXIT_RESTART = 2;

    private final String fullName;
    private final String scriptName;
    private final String appName;
    private final String version;

    protected CoreAccountService accountService;
    protected Injector injector;
    protected AppModule module;
    protected Config config;
    private boolean isShutdownInProgress;
    private boolean isReadOnly;
    private Thread keepRunningThread;
    private AtomicInteger keepRunningResult = new AtomicInteger(EXIT_SUCCESS);
    private Runnable shutdownCompletedHandler;

    public HavenoExecutable(String fullName, String scriptName, String appName, String version) {
        this.fullName = fullName;
        this.scriptName = scriptName;
        this.appName = appName;
        this.version = version;
    }

    public int execute(String[] args) {
        try {
            config = new Config(appName, Utilities.getUserDataDir(), args);
            if (config.helpRequested) {
                config.printHelp(System.out, new HavenoHelpFormatter(fullName, scriptName, version));
                System.exit(EXIT_SUCCESS);
            }
        } catch (ConfigException ex) {
            ex.printStackTrace();
            System.err.println("error: " + ex.getMessage());
            System.exit(EXIT_FAILURE);
        } catch (Throwable ex) {
            System.err.println("fault: An unexpected error occurred. " +
                    "Please file a report at https://bisq.network/issues");
            ex.printStackTrace(System.err);
            System.exit(EXIT_FAILURE);
        }

        return doExecute();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // First synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected int doExecute() {
        CommonSetup.setup(config, this);
        CoreSetup.setup(config);

        addCapabilities();

        // If application is JavaFX application we need to wait until it is initialized
        launchApplication();

        return EXIT_SUCCESS;
    }

    protected abstract void configUserThread();

    protected void addCapabilities() {
    }

    // The onApplicationLaunched call must map to UserThread, so that all following methods are running in the
    // thread the application is running and we don't run into thread interference.
    protected abstract void launchApplication();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // If application is a JavaFX application we need wait for onApplicationLaunched
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Headless versions can call inside launchApplication the onApplicationLaunched() manually
    protected void onApplicationLaunched() {
        configUserThread();
        CommonSetup.printSystemLoadPeriodically(10);
        // As the handler method might be overwritten by subclasses and they use the application as handler
        // we need to setup the handler after the application is created.
        CommonSetup.setupUncaughtExceptionHandler(this);
        setupGuice();
        setupAvoidStandbyMode();

        // If user tried to downgrade we do not read the persisted data to avoid data corruption
        // We call startApplication to enable UI to show popup. We prevent in HavenoSetup to go further
        // in the process and require a shut down.
        isReadOnly = HavenoSetup.hasDowngraded();

        // Account service should be available before attempting to login.
        accountService = injector.getInstance(CoreAccountService.class);

        // Application needs to restart on delete and restore of account.
        accountService.addListener(new AccountServiceListener() {
            @Override public void onAccountDeleted(Runnable onShutdown) { shutDownNoPersist(onShutdown, true); }
            @Override public void onAccountRestored(Runnable onShutdown) { shutDownNoPersist(onShutdown, true); }
        });

        // Attempt to login, subclasses should implement interactive login and or rpc login.
        CompletableFuture<Boolean> loginFuture = loginAccount();
        loginFuture.whenComplete((result, throwable) -> {
            if (throwable != null) {
                log.error("Error logging in to account", throwable);
                shutDownNoPersist(null, false);
                return;
            }
            try {
                if (!isReadOnly && loginFuture.get()) {
                    readAllPersisted(this::startApplication);
                } else {
                    log.warn("Running application in readonly mode");
                    startApplication();
                }
            } catch (InterruptedException | ExecutionException e) {
                log.error("An error occurred: {}", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Do not persist when shutting down after account restore and restarts since
     * that causes the current persistables to overwrite the restored or deleted state.
     *
     * If restart is specified, initiates an in-process asynchronous restart of the
     * application by interrupting the keepRunningThread.
     */
    protected void shutDownNoPersist(Runnable onShutdown, boolean restart) {
        this.isReadOnly = true;
        if (restart) {
            shutdownCompletedHandler = onShutdown;
            keepRunningResult.set(EXIT_RESTART);
            keepRunningThread.interrupt();
        } else {
            gracefulShutDown(() -> {
                log.info("Shutdown without persisting");
                if (onShutdown != null) onShutdown.run();
            });
        }
    }

    /**
     * Attempt to login. TODO: supply a password in config or args
     *
     * @return true if account is opened successfully.
     */
    protected CompletableFuture<Boolean> loginAccount() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (accountService.accountExists()) {
            log.info("Account already exists, attempting to open");
            try {
                accountService.openAccount(null);
                result.complete(accountService.isAccountOpen());
            } catch (IncorrectPasswordException ipe) {
                log.info("Account password protected, password required");
                result.complete(false);
            }
        } else if (!config.passwordRequired) {
            log.info("Creating Haveno account with null password");
            accountService.createAccount(null);
            result.complete(accountService.isAccountOpen());
        } else {
            log.info("Account does not exist and password is required");
            result.complete(false);
        }
        return result;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void setupGuice() {
        module = getModule();
        injector = getInjector();
        applyInjector();
    }

    protected abstract AppModule getModule();

    protected Injector getInjector() {
        return Guice.createInjector(module);
    }

    protected void applyInjector() {
        // Subclasses might configure classes with the injector here
    }

    protected void readAllPersisted(Runnable completeHandler) {
        readAllPersisted(null, completeHandler);
    }

    protected void readAllPersisted(@Nullable List<PersistedDataHost> additionalHosts, Runnable completeHandler) {
        List<PersistedDataHost> hosts = CorePersistedDataHost.getPersistedDataHosts(injector);
        if (additionalHosts != null) {
            hosts.addAll(additionalHosts);
        }

        AtomicInteger remaining = new AtomicInteger(hosts.size());
        hosts.forEach(host -> {
            host.readPersisted(() -> {
                if (remaining.decrementAndGet() == 0) {
                    UserThread.execute(completeHandler);
                }
            });
        });
    }

    protected void setupAvoidStandbyMode() {
    }

    protected abstract void startApplication();

    // Once the application is ready we get that callback and we start the setup
    protected void onApplicationStarted() {
        runHavenoSetup();
    }

    protected void runHavenoSetup() {
        HavenoSetup havenoSetup = injector.getInstance(HavenoSetup.class);
        havenoSetup.addHavenoSetupListener(this);
        havenoSetup.start();
    }

    @Override
    public abstract void onSetupComplete();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // GracefulShutDownHandler implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void gracefulShutDown(ResultHandler resultHandler) {
        gracefulShutDown(resultHandler, true);
    }

    // This might need to be overwritten in case the application is not using all modules
    @Override
    public void gracefulShutDown(ResultHandler onShutdown, boolean systemExit) {
        log.info("Start graceful shutDown");
        if (isShutdownInProgress) {
            log.info("Ignoring call to gracefulShutDown, already in progress");
            return;
        }

        isShutdownInProgress = true;

        ResultHandler resultHandler;
        if (shutdownCompletedHandler != null) {
            resultHandler = () -> {
                shutdownCompletedHandler.run();
                onShutdown.handleResult();
            };
        } else {
            resultHandler = onShutdown;
        }

        if (injector == null) {
            log.info("Shut down called before injector was created");
            resultHandler.handleResult();
            System.exit(EXIT_SUCCESS);
        }

        try {
            injector.getInstance(PriceFeedService.class).shutDown();
            injector.getInstance(ArbitratorManager.class).shutDown();
            injector.getInstance(TradeStatisticsManager.class).shutDown();
            injector.getInstance(XmrTxProofService.class).shutDown();
            injector.getInstance(AvoidStandbyModeService.class).shutDown();
            log.info("TradeManager and XmrWalletService shutdown started");
            HavenoUtils.executeTasks(Arrays.asList( // shut down trade and main wallets at same time
                    () -> injector.getInstance(TradeManager.class).shutDown(),
                    () -> injector.getInstance(XmrWalletService.class).shutDown(!isReadOnly)));
            log.info("OpenOfferManager shutdown started");
            injector.getInstance(OpenOfferManager.class).shutDown(() -> {
                log.info("OpenOfferManager shutdown completed");

                injector.getInstance(BtcWalletService.class).shutDown();

                // We need to shutdown BitcoinJ before the P2PService as it uses Tor.
                WalletsSetup walletsSetup = injector.getInstance(WalletsSetup.class);
                walletsSetup.shutDownComplete.addListener((ov, o, n) -> {
                    log.info("WalletsSetup shutdown completed");

                    injector.getInstance(P2PService.class).shutDown(() -> {
                        log.info("P2PService shutdown completed");
                        module.close(injector);
                        completeShutdown(resultHandler, EXIT_SUCCESS, systemExit);
                    });
                });
                walletsSetup.shutDown();
            });
        } catch (Throwable t) {
            log.error("App shutdown failed with exception {}", t.toString());
            t.printStackTrace();
            completeShutdown(resultHandler, EXIT_FAILURE, systemExit);
        }
    }

    private void completeShutdown(ResultHandler resultHandler, int exitCode, boolean systemExit) {
        if (!isReadOnly) {
            // If user tried to downgrade we do not write the persistable data to avoid data corruption
            PersistenceManager.flushAllDataToDiskAtShutdown(() -> {
                log.info("Graceful shutdown flushed persistence. Exiting now.");
                resultHandler.handleResult();
                if (systemExit)
                    UserThread.runAfter(() -> System.exit(exitCode), 1);
            });
        } else {
            resultHandler.handleResult();
            if (systemExit)
                UserThread.runAfter(() -> System.exit(exitCode), 1);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UncaughtExceptionHandler implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        log.error(throwable.toString());

        if (doShutDown)
            gracefulShutDown(() -> log.info("gracefulShutDown complete"));
    }

    /**
     * Runs until a command interrupts the application and returns the desired command behavior.
     * @return EXIT_SUCCESS to initiate a shutdown, EXIT_RESTART to initiate an in process restart.
     */
    protected int keepRunning() {
        keepRunningThread = new Thread(() -> {
            ConsoleInput reader = new ConsoleInput(Integer.MAX_VALUE, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            while (true) {
                Console console = System.console();
                try {
                    if (console == null) {
                        Thread.sleep(Long.MAX_VALUE);
                    } else {
                        var cmd = reader.readLine();
                        if ("exit".equals(cmd)) {
                            keepRunningResult.set(EXIT_SUCCESS);
                            break;
                        } else if ("restart".equals(cmd)) {
                            keepRunningResult.set(EXIT_RESTART);
                            break;
                        } else if ("help".equals(cmd)) {
                            System.out.println("Commands: restart, exit, help");
                        } else {
                            System.out.println("Unknown command, use: restart, exit, help");
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        keepRunningThread.start();
        try {
            keepRunningThread.join();
        } catch (InterruptedException ie) {
            System.out.println(ie);
        }

        return keepRunningResult.get();
    }
}
