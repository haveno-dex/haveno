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

package haveno.common.setup;

import haveno.common.UserThread;
import haveno.common.app.AsciiLogo;
import haveno.common.app.DevEnv;
import haveno.common.app.Log;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.util.Profiler;
import haveno.common.util.Utilities;

import org.bitcoinj.store.BlockStoreException;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.URISyntaxException;

import java.nio.file.Paths;

import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;

import lombok.extern.slf4j.Slf4j;



import sun.misc.Signal;

@Slf4j
public class CommonSetup {

    public static void setup(Config config, GracefulShutDownHandler gracefulShutDownHandler) {
        setupLog(config);
        AsciiLogo.showAsciiLogo();
        Version.setBaseCryptoNetworkId(config.baseCurrencyNetwork.ordinal());
        Version.printVersion();
        maybePrintPathOfCodeSource();
        Profiler.printSystemLoad();

        setSystemProperties();
        setupSigIntHandlers(gracefulShutDownHandler);

        DevEnv.setup(config);
    }

    public static void printSystemLoadPeriodically(int delayMin) {
        UserThread.runPeriodically(Profiler::printSystemLoad, delayMin, TimeUnit.MINUTES);
    }

    public static void setupUncaughtExceptionHandler(UncaughtExceptionHandler uncaughtExceptionHandler) {
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            // Might come from another thread
            if (throwable.getCause() != null && throwable.getCause().getCause() != null &&
                    throwable.getCause().getCause() instanceof BlockStoreException) {
                log.error(throwable.getMessage());
            } else if (throwable instanceof ClassCastException &&
                    "sun.awt.image.BufImgSurfaceData cannot be cast to sun.java2d.xr.XRSurfaceData".equals(throwable.getMessage())) {
                log.warn(throwable.getMessage());
            } else if (throwable instanceof UnsupportedOperationException &&
                    "The system tray is not supported on the current platform.".equals(throwable.getMessage())) {
                log.warn(throwable.getMessage());
            } else {
                log.error("Uncaught Exception from thread " + Thread.currentThread().getName());
                log.error("throwableMessage= " + throwable.getMessage());
                log.error("throwableClass= " + throwable.getClass());
                log.error("Stack trace:\n" + ExceptionUtils.getStackTrace(throwable));
                throwable.printStackTrace();
                UserThread.execute(() -> uncaughtExceptionHandler.handleUncaughtException(throwable, false));
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);
    }

    private static void setupLog(Config config) {
        String logPath = Paths.get(config.appDataDir.getPath(), "haveno").toString();
        Log.setup(logPath);
        Utilities.printSysInfo();
        Log.setLevel(Level.toLevel(config.logLevel));
    }

    protected static void setSystemProperties() {
        if (Utilities.isLinux())
            System.setProperty("prism.lcdtext", "false");
    }

    protected static void setupSigIntHandlers(GracefulShutDownHandler gracefulShutDownHandler) {
        Signal.handle(new Signal("INT"), signal -> {
            log.info("Received {}", signal);
            UserThread.execute(() -> gracefulShutDownHandler.gracefulShutDown(() -> {
            }));
        });

        Signal.handle(new Signal("TERM"), signal -> {
            log.info("Received {}", signal);
            UserThread.execute(() -> gracefulShutDownHandler.gracefulShutDown(() -> {
            }));
        });
    }

    protected static void maybePrintPathOfCodeSource() {
        try {
            final String pathOfCodeSource = Utilities.getPathOfCodeSource();
            if (!pathOfCodeSource.endsWith("classes"))
                log.info("Path to Haveno jar file: " + pathOfCodeSource);
        } catch (URISyntaxException e) {
            log.error(e.toString());
            e.printStackTrace();
        }
    }
}
