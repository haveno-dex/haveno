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
package haveno.core.app;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A cancellable console input reader.
 * Derived from https://www.javaspecialists.eu/archive/Issue153-Timeout-on-Console-Input.html
 */
@Slf4j
public class ConsoleInput {
    private static final long STTY_TIMEOUT_SECONDS = 5;
    private final int tries;
    private final int timeout;
    private final TimeUnit unit;
    private volatile Future<String> future;
    private volatile boolean cancelled;

    public ConsoleInput(int tries, int timeout, TimeUnit unit) {
        this.tries = tries;
        this.timeout = timeout;
        this.unit = unit;
    }

    public void cancel() {
        cancelled = true;
        Future<String> future = this.future;
        if (future != null)
            future.cancel(true);
    }

    // prompts for a line with terminal echo disabled, falling back to echoed input if unsupported (e.g. Windows, no tty)
    public String readPassword(String prompt) throws InterruptedException {
        String savedConfig = getTerminalConfig();
        Thread restoreHook = savedConfig == null ? null : addRestoreHook(savedConfig); // register before disabling echo so ctrl+c always restores
        boolean echoDisabled = restoreHook != null && runStty("-echo");
        if (restoreHook != null && !echoDisabled) removeRestoreHook(restoreHook);
        System.out.println(prompt); // prompt after disabling echo so input sent in response is never echoed
        try {
            return readLine();
        } finally {
            if (echoDisabled) {
                boolean restored = runStty(savedConfig);
                if (!restored) log.warn("Failed to restore terminal settings, run `stty echo` to fix the terminal");
                System.out.println(); // enter key is not echoed while echo is off
                if (restored) removeRestoreHook(restoreHook); // on failed restore, keep the hook to retry on shutdown
            }
        }
    }

    private static Thread addRestoreHook(String savedConfig) {
        Thread hook = new Thread(() -> runStty(savedConfig));
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return hook;
        } catch (IllegalStateException e) {
            return null; // shutdown in progress
        }
    }

    private static void removeRestoreHook(Thread restoreHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(restoreHook);
        } catch (IllegalStateException e) {
            // shutdown in progress, the hook itself will restore the config
        }
    }

    // returns the terminal config from `stty -g` for later restore, or null if unavailable
    private static String getTerminalConfig() {
        if (System.console() == null) return null;
        try {
            Process process = new ProcessBuilder("stty", "-g")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(STTY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String config = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !config.isEmpty() ? config : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean runStty(String arg) {
        Process process = null;
        try {
            process = new ProcessBuilder("stty", arg)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(STTY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly(); // don't leave an orphan changing the terminal after we report failure
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String readLine() throws InterruptedException {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        String input = null;
        try {
            for (int i = 0; i < tries; i++) {
                if (cancelled) throw new CancellationException("Reader cancelled");
                future = ex.submit(new ConsoleInputReadTask());
                if (cancelled) future.cancel(true); // close race with cancel() before future was set
                try {
                    input = future.get(timeout, unit);
                    break;
                } catch (ExecutionException e) {
                    e.getCause().printStackTrace();
                } catch (TimeoutException e) {
                    future.cancel(true);
                } finally {
                    future = null;
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return input;
    }
}
