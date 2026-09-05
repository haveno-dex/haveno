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

package haveno.core.app;

import com.google.inject.Guice;
import com.google.inject.Injector;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.common.config.ConfigException;
import haveno.common.config.HavenoHelpFormatter;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.common.setup.CommonSetup;
import haveno.common.setup.GracefulShutDownHandler;
import haveno.common.setup.UncaughtExceptionHandler;
import haveno.common.util.Utilities;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.XmrConnectionService;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.setup.CorePersistedDataHost;
import haveno.core.setup.CoreSetup;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.TradeManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.P2PService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.Console;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class HavenoExecutable implements GracefulShutDownHandler, HavenoSetup.HavenoSetupListener, UncaughtExceptionHandler {

    // TODO: regular expression is used to parse application name for the flatpak manifest, a more stable approach would be nice
    // Don't edit the next line unless you're only editing in between the quotes.
    public static final String DEFAULT_APP_NAME = "Haveno";

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
    @Getter
    protected volatile boolean isShutDownStarted;
    private final Object shutdownLock = new Object();
    private final List<ResultHandler> shutdownResultHandlers = new ArrayList<>();
    private boolean isShutdownComplete;
    private boolean systemExitRequested;
    private boolean isReadOnly;
    private Thread keepRunningThread;
    private final Object keepRunningLock = new Object();
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
                    "Please file a report at https://github.com/haveno-dex/haveno/issues");
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
        CommonSetup.warnOnHighMemoryUsagePeriodically(10);
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
            if (isShutDownStarted || keepRunningResult.get() == EXIT_RESTART) return;
            if (throwable != null) {
                log.error("Error logging in to account", throwable);
                shutDownNoPersist(null, false);
                return;
            }
            try {
                if (!isReadOnly && loginFuture.get()) {
                    readAllPersisted(this::startApplicationUnlessRestarting);
                } else {
                    log.warn("Running application in readonly mode");
                    startApplicationUnlessRestarting();
                }
            } catch (InterruptedException | ExecutionException e) {
                log.error("An error occurred: {}\n", e.getMessage(), e);
            }
        });
    }

    // a restore or delete during login requests a restart, so the application must not start against the changed data dir
    private void startApplicationUnlessRestarting() {
        if (isShutDownStarted) log.info("Shutdown requested during startup, not starting the application");
        else if (keepRunningResult.get() == EXIT_RESTART) log.info("Restart requested during login, not starting the application");
        else startApplication();
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
            synchronized (keepRunningLock) {
                keepRunningResult.set(EXIT_RESTART);
                if (keepRunningThread != null) keepRunningThread.interrupt(); // else keepRunning() returns the pending restart
            }
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
        log.info("Starting graceful shut down of {}", getClass().getSimpleName());

        // consume shutdownCompletedHandler so it runs once even when repeated requests join
        ResultHandler resultHandler;
        boolean exitCompletedShutdown;
        synchronized (shutdownLock) {
            systemExitRequested |= systemExit;
            exitCompletedShutdown = isShutdownComplete && systemExit; // a completed shutdown cannot exit anymore
            Runnable completedHandler = shutdownCompletedHandler;
            shutdownCompletedHandler = null;
            resultHandler = completedHandler == null ? onShutdown : () -> {
                completedHandler.run();
                onShutdown.handleResult();
            };
        }

        // repeated requests join the shutdown in progress and get notified on completion
        if (!beginGracefulShutDown(resultHandler)) {
            if (exitCompletedShutdown) CommonSetup.exitAfter(EXIT_SUCCESS, 100, TimeUnit.MILLISECONDS);
            return;
        }

        if (injector == null) {
            log.info("Shut down called before injector was created");
            completeShutdown(EXIT_SUCCESS);
            return;
        }

        try {

            // notify trade protocols and wallets to prepare for shut down before shutting down
            Set<Runnable> tasks = new HashSet<Runnable>();
            tasks.add(() -> injector.getInstance(TradeManager.class).onShutDownStarted());
            tasks.add(() -> injector.getInstance(XmrWalletService.class).onShutDownStarted());
            tasks.add(() -> injector.getInstance(XmrConnectionService.class).onShutDownStarted());
            try {
                ThreadUtils.awaitTasks(tasks, tasks.size(), 90000l); // run in parallel with timeout
            } catch (Exception e) {
                log.error("Failed to notify all services to prepare for shutdown: {}\n", e.getMessage(), e);
            }

            injector.getInstance(PriceFeedService.class).shutDown();
            injector.getInstance(ArbitratorManager.class).shutDown();
            injector.getInstance(TradeStatisticsManager.class).shutDown();
            injector.getInstance(AvoidStandbyModeService.class).shutDown();

            // shut down open offer manager
            log.info("Shutting down OpenOfferManager");
            injector.getInstance(OpenOfferManager.class).shutDown(() -> {

                // listen for shut down of wallets setup
                injector.getInstance(WalletsSetup.class).shutDownComplete.addListener((ov, o, n) -> {

                    // shut down p2p service
                    log.info("Shutting down P2P service");
                    injector.getInstance(P2PService.class).shutDown(() -> {

                        // done shutting down
                        log.info("Graceful shutdown completed. Exiting now.");
                        module.close(injector);
                        completeShutdown(EXIT_SUCCESS);
                    });
                });

                // shut down trade and wallet services
                log.info("Shutting down trade and wallet services");
                injector.getInstance(OfferBookService.class).shutDown();
                injector.getInstance(TradeManager.class).shutDown();
                injector.getInstance(BtcWalletService.class).shutDown();
                injector.getInstance(XmrWalletService.class).shutDown();
                injector.getInstance(XmrConnectionService.class).shutDown();
                injector.getInstance(WalletsSetup.class).shutDown();
            });
        } catch (Throwable t) {
            log.error("App shutdown failed with exception: {}\n", t.getMessage(), t);
            completeShutdown(EXIT_FAILURE);
        }
    }

    // Registers the caller's handler and returns whether the caller must run the shutdown routine.
    protected boolean beginGracefulShutDown(ResultHandler resultHandler) {
        synchronized (shutdownLock) {
            if (!isShutdownComplete) {
                shutdownResultHandlers.add(resultHandler);
                if (isShutDownStarted) {
                    log.info("Joining graceful shutdown already in progress");
                    return false;
                }

                isShutDownStarted = true;
                return true;
            }
        }

        notifyShutdownResultHandler(resultHandler);
        return false;
    }

    protected void notifyGracefulShutDownComplete() {
        List<ResultHandler> resultHandlers;
        synchronized (shutdownLock) {
            if (isShutdownComplete) {
                return;
            }

            isShutdownComplete = true;
            resultHandlers = List.copyOf(shutdownResultHandlers);
            shutdownResultHandlers.clear();
        }

        resultHandlers.forEach(this::notifyShutdownResultHandler);
    }

    private void notifyShutdownResultHandler(ResultHandler resultHandler) {
        try {
            resultHandler.handleResult();
        } catch (Throwable t) {
            log.error("Shutdown completion handler failed", t);
        }
    }

    // Used by subclasses with their own shutdown routine to notify handlers and schedule the exit.
    protected void completeShutDown(int status, long delay, TimeUnit timeUnit) {
        try {
            notifyGracefulShutDownComplete();
        } finally {
            CommonSetup.exitAfter(status, delay, timeUnit);
        }
    }

    private void completeShutdown(int exitCode) {
        if (!isReadOnly) {
            // If user tried to downgrade we do not write the persistable data to avoid data corruption
            PersistenceManager.flushAllDataToDiskAtShutdown(() -> {
                log.info("Graceful shutdown flushed persistence. Exiting now.");
                notifyGracefulShutDownComplete();
                if (isSystemExitRequested())
                    CommonSetup.exitAfter(exitCode, 100, TimeUnit.MILLISECONDS);
            });
        } else {
            notifyGracefulShutDownComplete();
            if (isSystemExitRequested())
                CommonSetup.exitAfter(exitCode, 100, TimeUnit.MILLISECONDS);
        }
    }

    // read after notifying completion so exit requests which joined the shutdown are honored
    private boolean isSystemExitRequested() {
        synchronized (shutdownLock) {
            return systemExitRequested;
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
        Thread thread = new Thread(() -> {
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

        synchronized (keepRunningLock) {
            if (keepRunningResult.get() == EXIT_RESTART) return EXIT_RESTART; // requested before the thread existed, e.g. by a restore before login
            keepRunningThread = thread;
            keepRunningThread.start();
        }
        try {
            keepRunningThread.join();
        } catch (InterruptedException ie) {
            System.out.println(ie);
        }

        return keepRunningResult.get();
    }
}
