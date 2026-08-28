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

package haveno.common.setup;

import ch.qos.logback.classic.Level;
import haveno.common.UserThread;
import haveno.common.app.AsciiLogo;
import haveno.common.app.DevEnv;
import haveno.common.app.Log;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.util.Profiler;
import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bitcoinj.store.BlockStoreException;
import sun.misc.Signal;

import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class CommonSetup {
    private static final int SHUTDOWN_WATCHDOG_MINUTES = 4;
    private static final AtomicBoolean exitScheduled = new AtomicBoolean();
    private static final AtomicBoolean shutdownSignalReceived = new AtomicBoolean();
    private static final AtomicBoolean shutdownHookRunning = new AtomicBoolean();
    private static final AtomicBoolean pipelineDisposedNpeLogged = new AtomicBoolean();
    private static volatile Thread shutdownHook;

    // throttle repeated exceptions per throw site so a hot loop cannot fill the logs:
    // full handling for the first occurrences, then one sampled occurrence per interval
    private static final int DETAILED_LOGS_PER_THROW_SITE = 3;
    private static final long THROTTLED_SAMPLE_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_TRACKED_THROW_SITES = 1000;
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_SIGNATURE_FRAMES = 8;
    private static final ConcurrentHashMap<String, RepeatedThrow> repeatedThrows = new ConcurrentHashMap<>();

    private static class RepeatedThrow {
        final AtomicLong count = new AtomicLong();
        final AtomicLong lastSampleNs = new AtomicLong(System.nanoTime() - THROTTLED_SAMPLE_INTERVAL_NS); // first sample due immediately
    }

    public static void setup(Config config, GracefulShutDownHandler gracefulShutDownHandler) {
        setupLog(config);
        AsciiLogo.showAsciiLogo();
        Version.setBaseCryptoNetworkId(config.baseCurrencyNetwork.ordinal());
        Version.printVersion();
        maybePrintPathOfCodeSource();
        Profiler.printSystemLoad();

        setSystemProperties();
        setupShutdownHandler(gracefulShutDownHandler);

        DevEnv.setup(config);
    }

    public static void printSystemLoadPeriodically(int delayMin) {
        UserThread.runPeriodically(Profiler::printSystemLoad, delayMin, TimeUnit.MINUTES);
    }

    public static void warnOnHighMemoryUsagePeriodically(int delaySec) {
        Profiler.warnOnHighMemoryUsagePeriodically(delaySec, TimeUnit.SECONDS);
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
            } else if (throwable instanceof IndexOutOfBoundsException && Arrays.stream(throwable.getStackTrace())
                    .anyMatch(element -> element.getClassName().startsWith("com.sun.glass.ui.mac.MacAccessible"))) {
                log.warn("Ignoring JavaFX macOS accessibility bug (JDK-8235989): {}", throwable.getMessage());
            } else if (throwable instanceof NullPointerException && isShutdownInProgress() && throwable.getMessage() != null &&
                    throwable.getMessage().contains("\"com.sun.prism.GraphicsPipeline.getPipeline()\" is null") &&
                    Arrays.stream(throwable.getStackTrace())
                            .anyMatch(element -> element.getClassName().equals("com.sun.javafx.tk.quantum.QuantumToolkit") && element.getMethodName().equals("isSupported"))) {
                if (pipelineDisposedNpeLogged.compareAndSet(false, true))
                    log.warn("Ignoring JavaFX input events delivered after graphics pipeline disposal on shutdown: {}", throwable.getMessage());
            } else {
                RepeatedThrow repeat = trackThrowSite(throwable);
                long count = repeat.count.incrementAndGet();
                if (count > DETAILED_LOGS_PER_THROW_SITE && !sampleDue(repeat)) return;
                if (count <= DETAILED_LOGS_PER_THROW_SITE) {
                    log.error("Uncaught Exception from thread {}, throwableClass={}, throwableMessage={}", Thread.currentThread().getName(), throwable.getClass(), throwable.getMessage());
                } else {
                    log.error("Uncaught Exception from thread {} repeated {} times, throwableClass={}, throwableMessage={}", Thread.currentThread().getName(), count, throwable.getClass(), throwable.getMessage());
                }
                log.error("Stack trace:\n" + ExceptionUtils.getStackTrace(throwable));
                throwable.printStackTrace();
                UserThread.execute(() -> uncaughtExceptionHandler.handleUncaughtException(throwable, false));
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);
    }

    // true once an application exit is scheduled, a termination signal was received, or the JVM shutdown hook has started
    private static boolean isShutdownInProgress() {
        return exitScheduled.get() || shutdownSignalReceived.get() || shutdownHookRunning.get();
    }

    private static RepeatedThrow trackThrowSite(Throwable throwable) {
        String site = throwSite(throwable);
        RepeatedThrow repeat = repeatedThrows.get(site);
        if (repeat != null) return repeat;
        if (repeatedThrows.size() >= MAX_TRACKED_THROW_SITES) site = "overflow"; // hard cap on memory
        return repeatedThrows.computeIfAbsent(site, key -> new RepeatedThrow());
    }

    private static boolean sampleDue(RepeatedThrow repeat) {
        long now = System.nanoTime(); // monotonic, immune to wall clock changes
        long last = repeat.lastSampleNs.get();
        return now - last >= THROTTLED_SAMPLE_INTERVAL_NS && repeat.lastSampleNs.compareAndSet(last, now);
    }

    // identifies a throw site by exception class, top frame and a bounded stack signature
    // through the cause chain, excluding messages so variable text cannot defeat the throttle
    static String throwSite(Throwable throwable) {
        StringBuilder site = new StringBuilder();
        for (int depth = 0; throwable != null && depth < MAX_CAUSE_DEPTH; throwable = throwable.getCause(), depth++) {
            if (depth > 0) site.append(" < ");
            site.append(throwable.getClass().getName());
            StackTraceElement[] trace = throwable.getStackTrace();
            if (trace.length > 0) site.append(" at ").append(trace[0]).append('#').append(stackSignature(trace));
        }
        return site.toString();
    }

    // hash of the first frames so different callers of a shared throw helper get distinct buckets
    private static String stackSignature(StackTraceElement[] trace) {
        int hash = 1;
        for (int i = 0; i < trace.length && i < MAX_SIGNATURE_FRAMES; i++) hash = 31 * hash + trace[i].hashCode();
        return Integer.toHexString(hash);
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

    protected static void setupShutdownHandler(GracefulShutDownHandler gracefulShutDownHandler) {
        // handle INT and TERM before the JVM shutdown sequence starts, as concurrent shutdown hooks
        // tear down JavaFX and the UserThread then silently drops the graceful shutdown task
        for (String signalName : new String[]{"INT", "TERM"}) {
            Signal.handle(new Signal(signalName), signal -> {
                log.info("Received {}", signal);
                startShutdownWatchdog();
                UserThread.execute(() -> gracefulShutDownHandler.gracefulShutDown(() -> {
                }));
            });
        }

        // fallback for termination paths which bypass the signal handlers, e.g. System.exit
        // setup re-runs on in-process restart, so replace any hook of the previous executable
        removeShutdownHook();
        Thread hook = new Thread(() -> {
            try {
                shutdownHookRunning.set(true);
                var countDownLatch = new CountDownLatch(1);
                UserThread.execute(() ->
                        gracefulShutDownHandler.gracefulShutDown(countDownLatch::countDown));
                //noinspection ResultOfMethodCallIgnored
                countDownLatch.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, "HavenoShutdownHook");
        Runtime.getRuntime().addShutdownHook(hook);
        shutdownHook = hook;
    }

    // Halts the process if a signal-initiated graceful shutdown does not complete in time,
    // since the handled signals no longer trigger the JVM's own bounded shutdown sequence.
    private static void startShutdownWatchdog() {
        if (!shutdownSignalReceived.compareAndSet(false, true)) {
            return;
        }

        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.MINUTES.sleep(SHUTDOWN_WATCHDOG_MINUTES);
            } catch (InterruptedException e) {
                return;
            }
            log.warn("Graceful shutdown did not complete within {} minutes, halting", SHUTDOWN_WATCHDOG_MINUTES);
            Runtime.getRuntime().halt(1);
        }, "HavenoShutdownWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // Terminates the process after an application-initiated graceful shutdown. Removes the shutdown hook first,
    // as re-running it waits on the UserThread while System.exit waits on the hook, which can deadlock.
    public static void exitAfter(int status, long delay, TimeUnit timeUnit) {
        if (!exitScheduled.compareAndSet(false, true)) {
            return;
        }

        removeShutdownHook();
        Thread exitThread = new Thread(() -> {
            try {
                timeUnit.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(status);
        }, "HavenoExit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    static boolean removeShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook == null) {
            return false;
        }

        try {
            return Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignored) {
            // The JVM is already shutting down, or its security policy does not permit removal.
            return false;
        }
    }

    protected static void maybePrintPathOfCodeSource() {
        try {
            final String pathOfCodeSource = Utilities.getPathOfCodeSource();
            if (!pathOfCodeSource.endsWith("classes"))
                log.info("Path to Haveno jar file: " + pathOfCodeSource);
        } catch (URISyntaxException e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }
}
