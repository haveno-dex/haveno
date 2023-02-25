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

package bisq.daemon.app;

import bisq.core.api.AccountServiceListener;
import bisq.core.app.ConsoleInput;
import bisq.core.app.CoreModule;
import bisq.core.app.HavenoHeadlessAppMain;

import bisq.common.UserThread;
import bisq.common.app.AppModule;
import bisq.common.crypto.IncorrectPasswordException;
import bisq.common.handlers.ResultHandler;
import bisq.common.persistence.PersistenceManager;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.Console;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

import bisq.daemon.grpc.GrpcServer;

@Slf4j
public class HavenoDaemonMain extends HavenoHeadlessAppMain {

    private GrpcServer grpcServer;

    public static void main(String[] args) {
        var keepRunning = true;
        while (keepRunning) {
            keepRunning = false;
            var daemon = new HavenoDaemonMain();
            var ret = daemon.execute(args);
            if (ret == EXIT_SUCCESS) {
                UserThread.execute(() -> daemon.gracefulShutDown(() -> {}));
            } else if (ret == EXIT_RESTART) {
                AtomicBoolean shuttingDown = new AtomicBoolean(true);
                UserThread.execute(() -> daemon.gracefulShutDown(() -> shuttingDown.set(false), false));
                keepRunning = true;
                // wait for graceful shutdown
                try {
                    while (shuttingDown.get()) {
                        Thread.sleep(1000);
                    }
                    PersistenceManager.reset();
                } catch (InterruptedException e) {
                    System.out.println("interrupted!");
                }
            }
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // First synchronous execution tasks
    /////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void configUserThread() {
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat(this.getClass().getSimpleName())
                .setDaemon(true)
                .build();
        UserThread.setExecutor(Executors.newSingleThreadExecutor(threadFactory));
    }

    @Override
    protected void launchApplication() {
        headlessApp = new HavenoDaemon();
        UserThread.execute(this::onApplicationLaunched);
    }

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
        headlessApp.setGracefulShutDownHandler(this);
    }


    /////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    /////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected AppModule getModule() {
        return new CoreModule(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();

        headlessApp.setInjector(injector);
    }

    @Override
    protected void startApplication() {
        // We need to be in user thread! We mapped at launchApplication already...
        headlessApp.startApplication();

        // In headless mode we don't have an async behaviour so we trigger the setup by
        // calling onApplicationStarted.
        onApplicationStarted();
    }

    @Override
    protected void onApplicationStarted() {
        super.onApplicationStarted();
    }

    @Override
    public void gracefulShutDown(ResultHandler resultHandler, boolean exit) {
        super.gracefulShutDown(resultHandler, exit);
        if (grpcServer != null) grpcServer.shutdown(); // could be null if application attempted to shutdown early
    }

    /**
     * Start the grpcServer to allow logging in remotely.
     */
    @Override
    protected CompletableFuture<Boolean> loginAccount() {
        CompletableFuture<Boolean> opened = super.loginAccount();

        // Start rpc server in case login is coming in from rpc
        grpcServer = injector.getInstance(GrpcServer.class);

        CompletableFuture<Boolean> inputResult = new CompletableFuture<Boolean>();
        try {
            if (opened.get()) {
                grpcServer.start();
                return opened;
            } else {

                // Nonblocking, we need to stop if the login occurred through rpc.
                // TODO: add a mode to mask password
                ConsoleInput reader = new ConsoleInput(Integer.MAX_VALUE, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
                Thread t = new Thread(() -> {
                    interactiveLogin(reader);
                });

                // Handle asynchronous account opens.
                // Will need to also close and reopen account.
                AccountServiceListener accountListener = new AccountServiceListener() {
                    @Override public void onAccountCreated() { onLogin(); }
                    @Override public void onAccountOpened() { onLogin(); }
                    private void onLogin() {
                        log.info("Logged in successfully");
                        reader.cancel(); // closing the reader will stop all read attempts and end the interactive login thread
                    }
                };
                accountService.addListener(accountListener);

                // start server after the listener is registered
                grpcServer.start();

                try {
                    // Wait until interactive login or rpc. Check one more time if account is open to close race condition.
                    if (!accountService.isAccountOpen()) {
                        log.info("Interactive login required");
                        t.start();
                        t.join();
                    }
                } catch (InterruptedException e) {
                    // expected
                }

                accountService.removeListener(accountListener);
                inputResult.complete(accountService.isAccountOpen());
            }
        } catch (InterruptedException | ExecutionException e) {
            inputResult.completeExceptionally(e);
        }

        return inputResult;
    }

    /**
     * Asks user for login. TODO: Implement in the desktop app.
     * @return True if user logged in interactively.
     */
    protected boolean interactiveLogin(ConsoleInput reader) {
        Console console = System.console();
        if (console == null) {
            // The ConsoleInput class reads from system.in, can wait for input without a console.
            log.info("No console available, account must be opened through rpc");
            try {
                // If user logs in through rpc, the reader will be interrupted through the event.
                reader.readLine();
            } catch (InterruptedException | CancellationException ex) {
                log.info("Reader interrupted, continuing startup");
            }
            return false;
        }

        String openedOrCreated = "Account unlocked\n";
        boolean accountExists = accountService.accountExists();
        while (!accountService.isAccountOpen()) {
            try {
                if (accountExists) {
                    try {
                        // readPassword will not return until the user inputs something
                        // which is not suitable if we are waiting for rpc call which
                        // could login the account. Must be able to interrupt the read.
                        //new String(console.readPassword("Password:"));
                        System.out.printf("Password:\n");
                        String password = reader.readLine();
                        accountService.openAccount(password);
                    } catch (IncorrectPasswordException ipe) {
                        System.out.printf("Incorrect password\n");
                    }
                } else {
                    System.out.printf("Creating a new account\n");
                    System.out.printf("Password:\n");
                    String password = reader.readLine();
                    System.out.printf("Confirm:\n");
                    String passwordConfirm = reader.readLine();
                    if (password.equals(passwordConfirm)) {
                        accountService.createAccount(password);
                        openedOrCreated = "Account created\n";
                    } else {
                        System.out.printf("Passwords did not match\n");
                    }
                }
            } catch (Exception ex) {
                log.debug(ex.getMessage());
                return false;
            }
        }
        System.out.printf(openedOrCreated);
        return true;
    }
}
