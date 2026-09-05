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

package haveno.desktop.app;

import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.common.app.DevEnv;
import haveno.common.app.Version;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.app.AvoidStandbyModeService;
import haveno.core.app.HavenoExecutable;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.user.Preferences;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.desktop.common.UITimer;
import haveno.desktop.common.view.guice.InjectorViewFactory;
import haveno.desktop.setup.DesktopPersistedDataHost;
import javafx.application.Application;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public class HavenoAppMain extends HavenoExecutable {

    private HavenoApp application;
    private StartupWizard.Result startupWizardResult;

    public HavenoAppMain() {
        super("Haveno Desktop", "haveno-desktop", HavenoExecutable.DEFAULT_APP_NAME, Version.VERSION);
    }

    public static void main(String[] args) {
        // For some reason the JavaFX launch process results in us losing the thread
        // context class loader: reset it. In order to work around a bug in JavaFX 8u25
        // and below, you must include the following code as the first line of your
        // realMain method:
        Thread.currentThread().setContextClassLoader(HavenoAppMain.class.getClassLoader());

        new HavenoAppMain().execute(args);
    }

    @Override
    public void onSetupComplete() {
        log.debug("onSetupComplete");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // First synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void configUserThread() {
        UserThread.setExecutor(Platform::runLater);
        UserThread.setTimerClass(UITimer.class);
    }

    @Override
    protected void launchApplication() {
        HavenoApp.setAppLaunchedHandler(application -> {
            HavenoAppMain.this.application = (HavenoApp) application;
            // Map to user thread!
            UserThread.execute(this::onApplicationLaunched);
        });

        Application.launch(HavenoApp.class);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // As application is a JavaFX application we need to wait for onApplicationLaunched
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
        application.setGracefulShutDownHandler(this);
    }

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        application.handleUncaughtException(throwable, doShutDown);
    }

    @Override
    protected void onPersistenceReadFailure(Throwable failure) {
        application.showPersistenceReadFailure(failure, () -> new Thread(
                () -> super.onPersistenceReadFailure(failure), "Persistence-failure-shutdown").start());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected AppModule getModule() {
        return new HavenoAppModule(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();

        application.setInjector(injector);
        injector.getInstance(InjectorViewFactory.class).setInjector(injector);
    }

    @Override
    protected void readAllPersisted(Runnable completeHandler) {
        super.readAllPersisted(DesktopPersistedDataHost.getPersistedDataHosts(injector), () -> {
            applyStartupWizardResult();
            completeHandler.run();
        });
    }

    // record the choices from the startup wizard once preferences are loaded
    private void applyStartupWizardResult() {
        if (startupWizardResult == null) return;
        Preferences preferences = injector.getInstance(Preferences.class);
        TradeCurrency currency = startupWizardResult.getPreferredTradeCurrency();
        if (currency != null) {
            preferences.setPreferredTradeCurrency(currency);
            preferences.setOfferBookChartScreenCurrencyCode(currency.getCode());
            preferences.setTradeChartsScreenCurrencyCode(currency.getCode());
        }
        // applied before the connection service initializes, so default nodes are never contacted
        String customNodes = startupWizardResult.getCustomMoneroNodes();
        if (customNodes != null) {
            preferences.setMoneroNodes(customNodes);
            preferences.setMoneroNodesOptionOrdinal(XmrNodes.MoneroNodesOption.CUSTOM.ordinal());
        }
        if (startupWizardResult.getUseTorForXmr() != null) preferences.setUseTorForXmrOrdinal(startupWizardResult.getUseTorForXmr().ordinal());
        // last: force-persists the preferences, making all wizard choices durable before startup continues
        preferences.setTacAcceptedV190(true);
    }

    @Override
    protected void setupAvoidStandbyMode() {
        injector.getInstance(AvoidStandbyModeService.class).init();
    }

    @Override
    protected void startApplication() {
        // We need to be in user thread! We mapped at launchApplication already.  Once
        // the UI is ready we get onApplicationStarted called and start the setup there.
        if (!application.isShutDownRequested()) application.startApplication(this::onApplicationStarted);
    }

    @Override
    protected void onApplicationStarted() {
        super.onApplicationStarted();

        // Relevant to have this in the logs, for support cases
        // This can only be called after JavaFX is initialized, otherwise the version logged will be null
        // Therefore, calling this as part of onApplicationStarted()
        log.info("Using JavaFX {}", System.getProperty("javafx.version"));
    }

    @Override
    protected CompletableFuture<Boolean> loginAccount() {

        // first run: gather the user's choices with the startup wizard before creating the account
        if (!accountService.accountExists() && !DevEnv.isDevMode()) return runStartupWizard();

        CompletableFuture<Boolean> loginResult = new CompletableFuture<>();
        Platform.setImplicitExit(false);
        application.showLoginProgress();
        // Key derivation and migration writes must finish before persistence is read.
        CompletableFuture.supplyAsync(() -> super.loginAccount().join(),
                task -> new Thread(task, "AccountLogin").start()).whenComplete((opened, failure) -> UserThread.execute(() -> {
                    if (application.isShutDownRequested() || isShutDownStarted) return;
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException
                                ? failure.getCause() : failure;
                        log.error("Error opening account", cause);
                        // Completing exceptionally starts shutdown, which can wait for services.
                        application.showLoginFailure(cause, () -> new Thread(
                                () -> loginResult.completeExceptionally(cause), "Account-login-failure-shutdown").start());
                    } else if (opened) {
                        loginResult.complete(true);
                    } else {
                        showPasswordScreen(loginResult);
                    }
                }));
        return loginResult;
    }

    private void showPasswordScreen(CompletableFuture<Boolean> loginResult) {
        application.showPasswordScreen(

                // verify off the JavaFX thread (openAccount decrypts the keys) and report the outcome
                (password, resultHandler) -> new Thread(() -> {
                    try {
                        accountService.openAccount(password);
                        if (accountService.isAccountOpen()) {
                            UserThread.execute(() -> {
                                if (!application.isShutDownRequested()) loginResult.complete(true);
                            });
                            resultHandler.accept(null);
                        } else {
                            resultHandler.accept(Res.get("password.startup.wrongPw"));
                        }
                    } catch (IncorrectPasswordException e) {
                        resultHandler.accept(Res.get("password.startup.wrongPw"));
                    } catch (Throwable t) {
                        log.error("Error opening account", t);
                        resultHandler.accept(t.getMessage() != null ? t.getMessage() : t.toString());
                    }
                }, "PasswordLogin").start(),

                // called if the user chooses to quit instead of logging in
                () -> {
                    log.warn("Password entry cancelled, shutting down");
                    new Thread(() -> HavenoApp.getShutDownHandler().run()).start();
                });
    }

    private CompletableFuture<Boolean> runStartupWizard() {
        CompletableFuture<Boolean> loginResult = new CompletableFuture<>();
        Platform.setImplicitExit(false);
        UserThread.execute(() -> application.showStartupWizard(

                // create the account off the JavaFX thread (generating the keys is slow)
                result -> new Thread(() -> {
                    try {
                        if (result.getWalletSeed() != null) accountService.setWalletImportDetails(result.getWalletSeed(), result.getWalletRestoreHeight(), result.getWalletRestoreDate());
                        accountService.createAccount(result.getPassword());
                        startupWizardResult = result;
                        UserThread.execute(() -> loginResult.complete(accountService.isAccountOpen()));
                    } catch (Throwable t) {
                        log.error("Error creating account", t);
                        UserThread.execute(() -> loginResult.completeExceptionally(t));
                    }
                }, "CreateAccount").start(),

                // called if the user chooses to quit instead of completing the setup
                () -> {
                    log.warn("Startup wizard cancelled, shutting down");
                    new Thread(() -> HavenoApp.getShutDownHandler().run()).start();
                }));
        return loginResult;
    }
}
