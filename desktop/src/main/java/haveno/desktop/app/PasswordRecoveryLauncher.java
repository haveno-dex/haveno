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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class PasswordRecoveryLauncher {
    static final String OPTION = "--recover-password";

    private PasswordRecoveryLauncher() {
    }

    static CompletableFuture<Void> start(Path networkDir) {
        CompletableFuture<Void> launched = new CompletableFuture<>();
        Thread launcher = new Thread(() -> {
            Process process = null;
            Path readinessDir = null;
            Exception failure = null;
            try {
                // keep child output detached; an empty marker signals that its screen has opened
                readinessDir = Files.createTempDirectory("haveno-recovery-");
                Path ready = readinessDir.resolve("ready");
                process = new ProcessBuilder(command(networkDir, ready))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process.getOutputStream().close();
                long started = System.nanoTime();
                while (!Files.exists(ready)) {
                    if (process.waitFor(100, TimeUnit.MILLISECONDS)) throw new IOException("Recovery exited during launch");
                    if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(60)) throw new IOException("Recovery did not open in time");
                }
                if (!process.isAlive()) throw new IOException("Recovery exited during launch");
            } catch (Exception e) {
                failure = e;
                if (process != null) process.destroy();
            } finally {
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

    private static List<String> command(Path networkDir, Path ready) {
        List<String> command = new ArrayList<>();
        String packagedLauncher = System.getProperty("jpackage.app-path");
        if (packagedLauncher != null && !packagedLauncher.isBlank()) {
            // packaged runtimes need not include a java executable
            // relaunch an AppImage through its runtime so it owns a new mount after the old app exits
            String appImage = System.getenv("APPIMAGE");
            String executable = appImage == null || appImage.isBlank() ? packagedLauncher : appImage;
            if (!Files.isExecutable(Path.of(executable))) throw new IllegalStateException("Application launcher is unavailable");
            command.add(executable);
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin", Utilities.isWindows() ? "java.exe" : "java").toString());
            // preserve module access without copying arbitrary system properties, agents or application credentials
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(arg -> arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=")
                            || arg.startsWith("--add-modules=") || arg.startsWith("--enable-native-access="))
                    .forEach(command::add);
            String modulePath = System.getProperty("jdk.module.path");
            if (modulePath != null) command.addAll(List.of("--module-path", modulePath));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), HavenoAppMain.class.getName()));
        }
        command.addAll(List.of(OPTION, networkDir.toAbsolutePath().toString(),
                Integer.toString(CssTheme.getCurrentTheme()), Long.toString(ProcessHandle.current().pid()),
                GlobalSettings.getLocale().toLanguageTag(), ready.toString()));
        return command;
    }
}
