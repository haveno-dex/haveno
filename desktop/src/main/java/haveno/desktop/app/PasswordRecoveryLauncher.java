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

package haveno.desktop.app;

import haveno.common.util.Utilities;
import haveno.core.locale.GlobalSettings;
import haveno.desktop.util.CssTheme;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PasswordRecoveryLauncher {
    static final String OPTION = "--recover-password";
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;
    private static final int OUTPUT_LIMIT = 16384;

    private PasswordRecoveryLauncher() {
    }

    static CompletableFuture<Void> start(Path networkDir) {
        CompletableFuture<Void> launched = new CompletableFuture<>();
        Thread launcher = new Thread(() -> {
            Process process = null;
            Path readinessDir = null;
            Exception failure = null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                // an empty marker signals that the child's screen has opened; its output is kept until then
                readinessDir = Files.createTempDirectory("haveno-recovery-");
                Path ready = readinessDir.resolve("ready");
                ProcessBuilder builder = new ProcessBuilder(command(networkDir, ready)).redirectErrorStream(true);
                // jpackage's linux launcher reads this inherited variable as a JVM restart and would pass the arguments to the JVM
                builder.environment().remove("_JPACKAGE_LAUNCHER");
                process = builder.start();
                process.getOutputStream().close();
                long started = System.nanoTime();
                while (!Files.exists(ready)) {
                    boolean exited = process.waitFor(100, TimeUnit.MILLISECONDS);
                    drain(process.getInputStream(), output, exited);
                    if (exited) throw new IOException("Recovery exited during launch with code " + process.exitValue() + lastMessage(output));
                    if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(LAUNCH_TIMEOUT_SECONDS)) {
                        throw new IOException("Recovery did not open within " + LAUNCH_TIMEOUT_SECONDS + " seconds" + lastMessage(output));
                    }
                }
                if (!process.isAlive()) {
                    drain(process.getInputStream(), output, true);
                    throw new IOException("Recovery exited during launch with code " + process.exitValue() + lastMessage(output));
                }
            } catch (Exception e) {
                failure = e;
                if (process != null) process.destroy();
                String text = output.toString(StandardCharsets.UTF_8).strip();
                log.warn("Could not open password recovery{}\n", text.isEmpty() ? "" : ", output:\n" + text, e);
            } finally {
                // the opened screen is detached; closing the pipe makes any later output fail harmlessly
                if (process != null) closeQuietly(process.getInputStream());
                if (readinessDir != null) {
                    try {
                        Files.deleteIfExists(readinessDir.resolve("ready"));
                        Files.deleteIfExists(readinessDir);
                    } catch (IOException ignored) {
                    }
                }
            }
            if (failure == null) launched.complete(null);
            else launched.completeExceptionally(failure);
        }, "LaunchPasswordRecovery");
        launcher.setDaemon(true);
        launcher.start();
        return launched;
    }

    /** The terminal command that opens the recovery screen for this installation once Haveno has exited. */
    static String manualCommand(Path networkDir) {
        String flatpakId = System.getenv("FLATPAK_ID");
        List<String> command = flatpakId == null || flatpakId.isBlank() ? launcher()
                : new ArrayList<>(List.of("flatpak", "run", flatpakId));
        command.addAll(List.of(OPTION, networkDir.toAbsolutePath().toString()));
        return (Utilities.isWindows() ? "& " : "")
                + command.stream().map(PasswordRecoveryLauncher::quote).collect(Collectors.joining(" "));
    }

    // quote literal arguments for PowerShell (including typographic apostrophes) or POSIX shells
    private static String quote(String argument) {
        if (Utilities.isWindows()) return "'" + argument.replaceAll("(['\u2018-\u201b])", "$1$1") + "'";
        if (argument.matches("[\\w./:=@+-]+")) return argument;
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    // keep the tail of the child's output without blocking: a running child is read one chunk per poll so
    // flooding cannot stall the wait, while everything an exited child left buffered is read before it is reported
    private static void drain(InputStream stream, ByteArrayOutputStream output, boolean exited) {
        try {
            int remaining = exited ? stream.available() : Math.min(stream.available(), OUTPUT_LIMIT);
            while (remaining > 0) {
                byte[] buffer = new byte[Math.min(remaining, OUTPUT_LIMIT)];
                int read = stream.read(buffer);
                if (read <= 0) break;
                remaining -= read;
                output.write(buffer, 0, read);
                if (output.size() > OUTPUT_LIMIT) {
                    // keep whole lines so a cut line cannot read as the cause
                    byte[] bytes = output.toByteArray();
                    int start = bytes.length - OUTPUT_LIMIT;
                    while (start < bytes.length && bytes[start - 1] != '\n') start++;
                    output.reset();
                    output.write(bytes, start, bytes.length - start);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // the last unindented line names the failure, such as a Java cause or a launcher error
    private static String lastMessage(ByteArrayOutputStream output) {
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty() && !Character.isWhitespace(lines[i].charAt(0)) && !line.startsWith("...")) return ": " + line;
        }
        return "";
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static List<String> command(Path networkDir, Path ready) {
        List<String> command = launcher();
        if (!Files.isExecutable(Path.of(command.get(0)))) throw new IllegalStateException("Application launcher " + command.get(0) + " is not executable");
        command.addAll(List.of(OPTION, networkDir.toAbsolutePath().toString(),
                Integer.toString(CssTheme.getCurrentTheme()), Long.toString(ProcessHandle.current().pid()),
                GlobalSettings.getLocale().toLanguageTag(), ready.toString()));
        return command;
    }

    private static List<String> launcher() {
        List<String> command = new ArrayList<>();
        String packagedLauncher = System.getProperty("jpackage.app-path");
        if (packagedLauncher != null && !packagedLauncher.isBlank()) {
            // packaged runtimes need not include a java executable
            // relaunch an AppImage through its runtime so it owns a new mount after the old app exits
            String appImage = System.getenv("APPIMAGE");
            command.add(appImage == null || appImage.isBlank() ? packagedLauncher : appImage);
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin", Utilities.isWindows() ? "java.exe" : "java").toString());
            // preserve module access without copying arbitrary system properties, agents or application credentials
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(arg -> arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=")
                            || arg.startsWith("--add-modules=") || arg.startsWith("--enable-native-access="))
                    .forEach(command::add);
            String modulePath = System.getProperty("jdk.module.path");
            if (modulePath != null) command.addAll(List.of("--module-path", absolutePaths(modulePath)));
            command.addAll(List.of("-cp", absolutePaths(System.getProperty("java.class.path")), HavenoAppMain.class.getName()));
        }
        return command;
    }

    // a source build started with relative paths must not depend on the directory the manual command runs from
    private static String absolutePaths(String paths) {
        return Arrays.stream(paths.split(File.pathSeparator, -1)).map(path -> new File(path).getAbsolutePath()).collect(Collectors.joining(File.pathSeparator));
    }
}
