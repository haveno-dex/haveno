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

import haveno.core.trade.TradeManager;

import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.setup.GracefulShutDownHandler;

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
    protected Injector injector;
    @Setter
    private GracefulShutDownHandler gracefulShutDownHandler;
    private boolean shutDownRequested;
    protected HavenoSetup havenoSetup;
    private CorruptedStorageFileHandler corruptedStorageFileHandler;
    private TradeManager tradeManager;

    public HavenoHeadlessApp() {
        shutDownHandler = this::stop;
    }

    public void startApplication() {
        try {
            havenoSetup = injector.getInstance(HavenoSetup.class);
            havenoSetup.addHavenoSetupListener(this);

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
        havenoSetup.setDisplayTacHandler(acceptedHandler -> {
            log.info("onDisplayTacHandler: We accept the tacs automatically in headless mode");
            acceptedHandler.run();
        });
        havenoSetup.setDisplayTorNetworkSettingsHandler(show -> log.info("onDisplayTorNetworkSettingsHandler: show={}", show));
        havenoSetup.setSpvFileCorruptedHandler(msg -> log.error("onSpvFileCorruptedHandler: msg={}", msg));
        havenoSetup.setChainFileLockedExceptionHandler(msg -> log.error("onChainFileLockedExceptionHandler: msg={}", msg));
        havenoSetup.setLockedUpFundsHandler(msg -> log.info("onLockedUpFundsHandler: msg={}", msg));
        havenoSetup.setShowFirstPopupIfResyncSPVRequestedHandler(() -> log.info("onShowFirstPopupIfResyncSPVRequestedHandler"));
        havenoSetup.setRequestWalletPasswordHandler(aesKeyHandler -> log.info("onRequestWalletPasswordHandler"));
        havenoSetup.setDisplayUpdateHandler((alert, key) -> log.info("onDisplayUpdateHandler"));
        havenoSetup.setDisplayAlertHandler(alert -> log.info("onDisplayAlertHandler. alert={}", alert));
        havenoSetup.setDisplayPrivateNotificationHandler(privateNotification -> log.info("onDisplayPrivateNotificationHandler. privateNotification={}", privateNotification));
        havenoSetup.setDisplaySecurityRecommendationHandler(key -> log.info("onDisplaySecurityRecommendationHandler"));
        havenoSetup.setWrongOSArchitectureHandler(msg -> log.error("onWrongOSArchitectureHandler. msg={}", msg));
        havenoSetup.setRejectedTxErrorMessageHandler(errorMessage -> log.warn("setRejectedTxErrorMessageHandler. errorMessage={}", errorMessage));
        havenoSetup.setShowPopupIfInvalidBtcConfigHandler(() -> log.error("onShowPopupIfInvalidBtcConfigHandler"));
        havenoSetup.setRevolutAccountsUpdateHandler(revolutAccountList -> log.info("setRevolutAccountsUpdateHandler: revolutAccountList={}", revolutAccountList));
        havenoSetup.setOsxKeyLoggerWarningHandler(() -> log.info("setOsxKeyLoggerWarningHandler"));
        havenoSetup.setQubesOSInfoHandler(() -> log.info("setQubesOSInfoHandler"));
        havenoSetup.setDownGradePreventionHandler(lastVersion -> log.info("Downgrade from version {} to version {} is not supported",
                lastVersion, Version.VERSION));

        corruptedStorageFileHandler.getFiles().ifPresent(files -> log.warn("getCorruptedDatabaseFiles. files={}", files));
        tradeManager.setTakeOfferRequestErrorMessageHandler(errorMessage -> log.error("onTakeOfferRequestErrorMessageHandler"));
    }

    public void stop() {
        if (!shutDownRequested) {
            UserThread.runAfter(() -> {
                gracefulShutDownHandler.gracefulShutDown(() -> {
                    log.debug("App shutdown complete");
                });
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
