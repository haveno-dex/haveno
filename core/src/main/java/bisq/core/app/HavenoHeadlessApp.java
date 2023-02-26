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

import bisq.core.trade.TradeManager;

import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.file.CorruptedStorageFileHandler;
import bisq.common.setup.GracefulShutDownHandler;

import com.google.inject.Injector;

import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HavenoHeadlessApp implements HeadlessApp {
    @Getter
    private static Runnable shutDownHandler;
    @Setter
    public static Runnable onGracefulShutDownHandler;

    @Setter
    protected Injector injector;
    @Setter
    private GracefulShutDownHandler gracefulShutDownHandler;
    private boolean shutDownRequested;
    protected HavenoSetup bisqSetup;
    private CorruptedStorageFileHandler corruptedStorageFileHandler;
    private TradeManager tradeManager;

    public HavenoHeadlessApp() {
        shutDownHandler = this::stop;
    }

    @Override
    public void startApplication() {
        try {
            bisqSetup = injector.getInstance(HavenoSetup.class);
            bisqSetup.addHavenoSetupListener(this);

            corruptedStorageFileHandler = injector.getInstance(CorruptedStorageFileHandler.class);
            tradeManager = injector.getInstance(TradeManager.class);

            setupHandlers();
        } catch (Throwable throwable) {
            log.error("Error during app init", throwable);
            handleUncaughtException(throwable, false);
        }
    }

    @Override
    public void onSetupComplete() {
        log.info("onSetupComplete");
    }

    protected void setupHandlers() {
        bisqSetup.setDisplayTacHandler(acceptedHandler -> {
            log.info("onDisplayTacHandler: We accept the tacs automatically in headless mode");
            acceptedHandler.run();
        });
        bisqSetup.setDisplayTorNetworkSettingsHandler(show -> log.info("onDisplayTorNetworkSettingsHandler: show={}", show));
        bisqSetup.setSpvFileCorruptedHandler(msg -> log.error("onSpvFileCorruptedHandler: msg={}", msg));
        bisqSetup.setChainFileLockedExceptionHandler(msg -> log.error("onChainFileLockedExceptionHandler: msg={}", msg));
        bisqSetup.setLockedUpFundsHandler(msg -> log.info("onLockedUpFundsHandler: msg={}", msg));
        bisqSetup.setShowFirstPopupIfResyncSPVRequestedHandler(() -> log.info("onShowFirstPopupIfResyncSPVRequestedHandler"));
        bisqSetup.setDisplayUpdateHandler((alert, key) -> log.info("onDisplayUpdateHandler"));
        bisqSetup.setDisplayAlertHandler(alert -> log.info("onDisplayAlertHandler. alert={}", alert));
        bisqSetup.setDisplayPrivateNotificationHandler(privateNotification -> log.info("onDisplayPrivateNotificationHandler. privateNotification={}", privateNotification));
        bisqSetup.setDisplaySecurityRecommendationHandler(key -> log.info("onDisplaySecurityRecommendationHandler"));
        bisqSetup.setWrongOSArchitectureHandler(msg -> log.error("onWrongOSArchitectureHandler. msg={}", msg));
        bisqSetup.setRejectedTxErrorMessageHandler(errorMessage -> log.warn("setRejectedTxErrorMessageHandler. errorMessage={}", errorMessage));
        bisqSetup.setShowPopupIfInvalidBtcConfigHandler(() -> log.error("onShowPopupIfInvalidBtcConfigHandler"));
        bisqSetup.setRevolutAccountsUpdateHandler(revolutAccountList -> log.info("setRevolutAccountsUpdateHandler: revolutAccountList={}", revolutAccountList));
        bisqSetup.setOsxKeyLoggerWarningHandler(() -> log.info("setOsxKeyLoggerWarningHandler"));
        bisqSetup.setQubesOSInfoHandler(() -> log.info("setQubesOSInfoHandler"));
        bisqSetup.setDownGradePreventionHandler(lastVersion -> log.info("Downgrade from version {} to version {} is not supported",
                lastVersion, Version.VERSION));
        bisqSetup.setTorAddressUpgradeHandler(() -> log.info("setTorAddressUpgradeHandler"));
        corruptedStorageFileHandler.getFiles().ifPresent(files -> log.warn("getCorruptedDatabaseFiles. files={}", files));
        tradeManager.setTakeOfferRequestErrorMessageHandler(errorMessage -> log.error("Error taking offer: " + errorMessage));
    }

    public void stop() {
        if (!shutDownRequested) {
            UserThread.runAfter(() -> {
                if (gracefulShutDownHandler != null) {
                    gracefulShutDownHandler.gracefulShutDown(() -> {
                        log.debug("App shutdown complete");
                        if (onGracefulShutDownHandler != null) onGracefulShutDownHandler.run();
                    });
                } else if (onGracefulShutDownHandler != null) {
                    onGracefulShutDownHandler.run();
                }
            }, 200, TimeUnit.MILLISECONDS);
            shutDownRequested = true;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UncaughtExceptionHandler implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        if (!shutDownRequested) {
            try {
                try {
                    log.error(throwable.getMessage());
                } catch (Throwable throwable3) {
                    log.error("Error at displaying Throwable.");
                    throwable3.printStackTrace();
                }
                if (doShutDown)
                    stop();
            } catch (Throwable throwable2) {
                // If printStackTrace cause a further exception we don't pass the throwable to the Popup.
                log.error(throwable2.toString());
                if (doShutDown)
                    stop();
            }
        }
    }
}
