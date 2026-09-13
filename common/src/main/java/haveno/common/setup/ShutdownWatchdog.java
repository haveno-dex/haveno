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

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sun.misc.Signal;
import sun.misc.SignalHandler;

public final class ShutdownWatchdog {
    private ShutdownWatchdog() {
    }

    // use a separate JVM because native exit handlers can stop every Java thread in Haveno
    static Process start(long timeoutMillis) throws IOException, URISyntaxException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Shutdown timeout must be positive");
        ProcessHandle parent = ProcessHandle.current();
        String java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classPath = Paths.get(ShutdownWatchdog.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Xms8m", "-Xmx32m", "-XX:+UseSerialGC",
                "-cp", classPath, ShutdownWatchdog.class.getName(), Long.toString(parent.pid()),
                parent.info().startInstant().orElseThrow().toString(), Long.toString(timeoutMillis));
        // inherited agents and debug ports must not interfere with the independent watchdog
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            CompletableFuture<Integer> readiness = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try (InputStream input = process.getInputStream()) {
                    readiness.complete(input.read());
                } catch (IOException e) {
                    readiness.completeExceptionally(e);
                }
            }, "ShutdownWatchdog-ready");
            reader.setDaemon(true);
            reader.start();
            int ready = readiness.get(5, TimeUnit.SECONDS);
            if (ready != 1) throw new IOException("Shutdown watchdog exited before becoming ready");
            return process;
        } catch (Exception e) {
            process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Could not arm shutdown watchdog", e);
        }
    }

    public static void main(String[] args) throws Exception {
        // repeated terminal or service-stop signals must not disarm the parent's exit guard
        for (String name : new String[]{"INT", "TERM", "HUP"}) {
            try {
                Signal.handle(new Signal(name), SignalHandler.SIG_IGN);
            } catch (IllegalArgumentException e) {
                // some signals are unavailable on Windows
            }
        }
        long parentPid = Long.parseLong(args[0]);
        Instant parentStart = Instant.parse(args[1]);
        long timeoutMillis = Long.parseLong(args[2]);
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Shutdown timeout must be positive");

        // Unix reparents orphaned children; Windows retains the original pid and needs a start-time check
        // avoid comparing start times across Unix JVMs, where clock corrections can change their values
        ProcessHandle parent = ProcessHandle.current().parent()
                .filter(process -> process.pid() == parentPid)
                .filter(process -> !System.getProperty("os.name").startsWith("Windows")
                        || process.info().startInstant().filter(parentStart::equals).isPresent())
                .orElse(null);
        if (parent == null) {
            System.err.println("Shutdown watchdog parent has exited or its identity does not match");
            return;
        }

        System.out.write(1);
        System.out.flush();

        try {
            parent.onExit().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // terminate before reporting so a blocked output stream cannot prevent shutdown
            boolean terminated = parent.destroyForcibly();
            System.err.println("Haveno shutdown timed out; forced termination of process " + parentPid + ": " + terminated);
        }
    }
}
