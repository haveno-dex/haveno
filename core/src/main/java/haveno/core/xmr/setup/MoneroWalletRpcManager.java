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

package haveno.core.xmr.setup;

import com.google.common.util.concurrent.Uninterruptibles;
import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.common.MoneroRpcError;
import monero.wallet.MoneroWalletRpc;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Manages monero-wallet-rpc processes bound to ports.
 */
@Slf4j
public class MoneroWalletRpcManager {

    public static final long SHUTDOWN_TIMEOUT_MS = 120000;
    private static final long PROCESS_TIMEOUT_MS = 15000;
    private static final String RPC_BIND_PORT_ARGUMENT = "--rpc-bind-port";
    private static final String RPC_USERNAME = "haveno_user";
    private static int NUM_ALLOWED_ATTEMPTS = 3; // allow this many attempts to bind to an assigned port
    private Integer startPort;
    private volatile boolean isShutDownStarted;
    private final Map<Integer, MoneroWalletRpc> registeredPorts = new HashMap<>();

    /**
     * Manage monero-wallet-rpc instances by auto-assigning ports.
     */
    public MoneroWalletRpcManager() {
    }

    /**
     * Manage monero-wallet-rpc instances by assigning consecutive ports from a
     * starting port.
     *
     * @param startPort is the starting port to bind to
     */
    public MoneroWalletRpcManager(int startPort) {
        this.startPort = startPort;
    }

    /**
     * Start a new instance of monero-wallet-rpc with private ephemeral credentials.
     *
     * @param cmd command line parameters to start monero-wallet-rpc
     * @return a client connected to the monero-wallet-rpc instance
     */
    public MoneroWalletRpc startInstance(List<String> cmd) {
        if (isShutDownStarted) throw new MoneroError("Cannot start monero-wallet-rpc instance because shutdown is started");

        // pass credentials through an owner-only startup file, never through process arguments
        Path rpcConfig = null;
        try {
            rpcConfig = Files.createTempFile("haveno-wallet-rpc-", ".conf");
            if (Files.getFileStore(rpcConfig).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(rpcConfig, PosixFilePermissions.fromString("rw-------"));
            } else {
                AclFileAttributeView acl = Files.getFileAttributeView(rpcConfig, AclFileAttributeView.class);
                if (acl == null) throw new IOException("Cannot restrict wallet RPC credential file permissions");
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(rpcConfig)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String password = Utilities.encodeToHex(secret);
            cmd = new ArrayList<>(cmd);
            StringBuilder config = new StringBuilder("rpc-login=" + RPC_USERNAME + ":" + password + "\n");
            int daemonLoginIndex = cmd.indexOf("--daemon-login");
            String daemonLogin = daemonLoginIndex < 0 ? null : cmd.get(daemonLoginIndex + 1);
            // the config file trims whitespace and cuts at a comment or line break, so such a login stays in the arguments
            if (daemonLogin != null && daemonLogin.equals(daemonLogin.strip()) && !daemonLogin.matches("(?s).*[#\\r\\n].*")) {
                config.append("daemon-login=").append(daemonLogin).append("\n");
                cmd.remove(daemonLoginIndex + 1);
                cmd.remove(daemonLoginIndex);
            }
            Files.writeString(rpcConfig, config.toString(), StandardCharsets.UTF_8);
            cmd.add("--config-file");
            cmd.add(rpcConfig.toString());
            MoneroWalletRpc walletRpc = startInstanceInternal(cmd);
            try {
                walletRpc.getRpcConnection().setCredentials(RPC_USERNAME, password);
                return walletRpc;
            } catch (RuntimeException e) {
                stopInstance(walletRpc, null, true);
                throw e;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not configure wallet RPC authentication", e);
        } finally {
            if (rpcConfig != null) {
                try {
                    Files.deleteIfExists(rpcConfig);
                } catch (IOException e) {
                    log.warn("Could not delete wallet RPC credential file {}", rpcConfig, e);
                }
            }
        }
    }

    private MoneroWalletRpc startInstanceInternal(List<String> cmd) {
        try {

            // register given port
            if (cmd.contains(RPC_BIND_PORT_ARGUMENT)) {
                int portArgumentPosition = cmd.indexOf(RPC_BIND_PORT_ARGUMENT) + 1;
                int port = Integer.parseInt(cmd.get(portArgumentPosition));
                synchronized (registeredPorts) {
                    unregisterExited(); // an interrupted stop leaves its instance registered
                    if (registeredPorts.containsKey(port)) throw new RuntimeException("Port " + port + " is already registered");
                    registeredPorts.put(port, null);
                }
                MoneroWalletRpc walletRpc;
                try {
                    walletRpc = new MoneroWalletRpc(cmd); // starts monero-wallet-rpc process
                } catch (Exception e) {
                    removePort(port); // unregister port so retries are not blocked
                    throw e;
                }
                synchronized (registeredPorts) {
                    registeredPorts.put(port, walletRpc);
                }
                return walletRpc;
            }

            // register assigned ports up to maximum attempts
            else {
                int numAttempts = 0;
                while (numAttempts < NUM_ALLOWED_ATTEMPTS) {
                    int port = -1;
                    try {
                        numAttempts++;

                        // get port
                        ServerSocket socket = null;
                        if (startPort != null) port = registerNextPort();
                        else {
                            socket = new ServerSocket(0);
                            port = socket.getLocalPort();
                            synchronized (registeredPorts) {
                                registeredPorts.put(port, null);
                            }
                        }

                        // start monero-wallet-rpc
                        List<String> cmdCopy = new ArrayList<>(cmd); // preserve original cmd
                        cmdCopy.add(RPC_BIND_PORT_ARGUMENT);
                        cmdCopy.add("" + port);
                        if (socket != null) socket.close(); // TODO: port can be taken by another process before monero-wallet-rpc starts. we could synchronize on a lock until monero-wallet-rpc is started, but that would interfere with stress testing
                        MoneroWalletRpc walletRpc = new MoneroWalletRpc(cmdCopy); // start monero-wallet-rpc process
                        synchronized (registeredPorts) {
                            registeredPorts.put(port, walletRpc);
                        }
                        return walletRpc;
                    } catch (Exception e) {
                        if (port != -1) removePort(port); // unregister port so retries are not blocked
                        if (isShutDownStarted) throw e; // skip retries and warning on shutdown
                        log.warn("Unable to start monero-wallet-rpc instance on attempt {}/{} with port {}: {}", numAttempts, NUM_ALLOWED_ATTEMPTS, port, e.getMessage());
                        if (numAttempts >= NUM_ALLOWED_ATTEMPTS) throw e;
                    }
                }
                throw new MoneroError("Failed to start monero-wallet-rpc instance after " + NUM_ALLOWED_ATTEMPTS + " attempts. This should never happen"); // should never reach here
            }
        } catch (IOException e) {
            throw new MoneroError(e);
        }
    }

    /**
     * Signal that startup has begun, so instances can start after an in-process restart.
     */
    public void onStartUp() {
        isShutDownStarted = false;
    }

    /**
     * Prevent startup while an existing process may still be using the wallet paths.
     */
    public static void checkForRunningProcesses(File executable, File walletDir) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            executable = executable.getCanonicalFile();
            walletDir = walletDir.getCanonicalFile();
            for (ProcessHandle process : processes.toList()) {
                ProcessHandle.Info info = getInfo(process);
                String command = info == null ? null : getCommand(info);
                if (command == null || !new File(command).getName().equals(executable.getName())
                        || !new File(command).getCanonicalFile().equals(executable) || !process.isAlive()) continue;

                // skip processes known to use another wallet directory; arguments are unavailable on some platforms
                File processWalletDir = getWalletDir(info);
                if (processWalletDir != null && !processWalletDir.equals(walletDir)) continue;

                throw new IllegalStateException("Cannot start Haveno while monero-wallet-rpc process " + process.pid()
                        + " may still be using its wallet files. Close other Haveno instances and wait for them to exit, then restart Haveno. If none is running, stop the process in your task manager first");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not resolve monero-wallet-rpc paths before wallet startup", e);
        }
    }

    /** Get the process info, or null when the platform cannot describe the process, like a process mid exec on macos. */
    private static ProcessHandle.Info getInfo(ProcessHandle process) {
        try {
            return process.info();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Get the executable path of the process, or null when it is unavailable. */
    private static String getCommand(ProcessHandle.Info info) {
        String command = info.command().orElse(null);
        // linux appends this suffix when a running executable has been replaced
        if (command != null && Utilities.isLinux() && command.endsWith(" (deleted)")) {
            command = command.substring(0, command.length() - " (deleted)".length());
        }
        return command;
    }

    /** Get the canonical wallet directory from the process arguments, or null when it cannot be determined. */
    private static File getWalletDir(ProcessHandle.Info info) throws IOException {
        String[] args = info.arguments().orElse(new String[0]);
        for (int i = 0; i < args.length; i++) {
            String path;
            if (args[i].equals("--wallet-dir") && i + 1 < args.length) path = args[i + 1];
            else if (args[i].startsWith("--wallet-dir=")) path = args[i].substring("--wallet-dir=".length());
            else continue;
            File walletDir = new File(path);
            return walletDir.isAbsolute() ? walletDir.getCanonicalFile() : null;
        }
        return null;
    }

    /**
     * Signal that shutdown has started, so failed starts are not retried or logged.
     */
    public void onShutDownStarted() {
        isShutDownStarted = true;
    }

    /**
     * Stop an instance of monero-wallet-rpc.
     *
     * @param walletRpc the client connected to the monero-wallet-rpc instance to stop
     * @param path the wallet path, since the wallet might be closed
     * @param force specifies if the process should be forcibly destroyed
     */
    public void stopInstance(MoneroWalletRpc walletRpc, String path, boolean force) {

        // stop process
        int port = getPort(walletRpc);
        Process process = walletRpc.getProcess();
        if (port == -1 && (process == null || process.isAlive())) {
            throw new RuntimeException("MoneroWalletRpc instance not registered with a port");
        }
        String pid = process == null ? null : String.valueOf(process.pid());
        if (force) log.info("Stopping MoneroWalletRpc path={}, port={}, pid={}, force={}", path, port, pid, force);
        else log.debug("Stopping MoneroWalletRpc path={}, port={}, pid={}, force={}", path, port, pid, force);
        try {
            walletRpc.stopProcess(force);
        } finally {
            unregisterExited(); // an unfinished stop stays registered so shutdown can still find and force it
        }
    }

    /**
     * Stop instances which are still running after shutdown, like one still opening a wallet, so they do not outlive
     * the application or lock its wallet files. Each is asked to save and stop, then destroyed if it has not exited.
     *
     * @param timeoutMs the time allowed for saving and stopping before forced termination
     */
    public void stopRemainingInstances(long timeoutMs) {

        // wait for instances still starting, since their processes cannot be stopped before they are registered
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs, PROCESS_TIMEOUT_MS));
        while (isStarting() && System.nanoTime() - deadline < 0) Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
        List<MoneroWalletRpc> remaining;
        synchronized (registeredPorts) {
            unregisterExited();
            remaining = registeredPorts.values().stream().filter(Objects::nonNull).filter(walletRpc -> walletRpc.getProcess() != null).toList();
        }
        if (remaining.isEmpty()) return;
        log.warn("Stopping {} monero-wallet-rpc instance(s) still running after shutdown", remaining.size());

        // request each instance to save and stop in parallel, since a stop request waits for a busy wallet
        List<CompletableFuture<Process>> exits = new ArrayList<>();
        for (MoneroWalletRpc walletRpc : remaining) {
            Process process = walletRpc.getProcess();
            exits.add(process.onExit());
            Thread thread = new Thread(() -> {
                try {
                    walletRpc.stop();
                } catch (MoneroRpcError e) {
                    log.warn("monero-wallet-rpc process {} refused to save and stop: {}", process.pid(), e.getMessage());
                    if (Integer.valueOf(-13).equals(e.getCode())) process.destroy(); // no wallet is open; other errors can mean saving failed
                } catch (Exception e) {
                    log.warn("Error requesting monero-wallet-rpc process {} to stop: {}", process.pid(), e.getMessage());
                }
            }, "StopWalletRpc-" + process.pid());
            thread.setDaemon(true);
            thread.start();
        }

        // halfway through the timeout, signal instances still running to terminate where the platform supports it,
        // since a wallet too busy to serve the stop request still saves on the signal
        CompletableFuture<Void> exited = CompletableFuture.allOf(exits.toArray(new CompletableFuture[0]));
        if (!awaitExit(exited, timeoutMs / 2)) {
            for (MoneroWalletRpc walletRpc : remaining) {
                Process process = walletRpc.getProcess();
                if (!process.isAlive() || !process.supportsNormalTermination()) continue;
                log.info("Terminating monero-wallet-rpc process {} which has not served the stop request", process.pid());
                process.destroy();
            }
        }

        // wait for the instances to exit, destroying the rest
        if (!awaitExit(exited, timeoutMs - timeoutMs / 2)) {
            for (MoneroWalletRpc walletRpc : remaining) {
                Process process = walletRpc.getProcess();
                if (!process.isAlive()) continue;
                log.warn("Forcibly stopping monero-wallet-rpc process {} which did not stop within {} ms", process.pid(), timeoutMs);
                process.destroyForcibly();
            }
            awaitExit(exited, Math.min(timeoutMs, PROCESS_TIMEOUT_MS)); // a destroyed process exits asynchronously
        }

        // free the ports of the instances which exited, for a restart
        unregisterExited();
    }

    // an interrupt must not shorten the time allowed for saving
    private static boolean awaitExit(CompletableFuture<Void> exited, long timeoutMs) {
        try {
            Uninterruptibles.getUninterruptibly(exited, timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private boolean isStarting() {
        synchronized (registeredPorts) {
            return registeredPorts.containsValue(null);
        }
    }

    private int registerNextPort() throws IOException {
        synchronized (registeredPorts) {
            int port = startPort;
            while (registeredPorts.containsKey(port)) port++;
            registeredPorts.put(port, null);
            return port;
        }
    }

    private int getPort(MoneroWalletRpc walletRpc) {
        synchronized (registeredPorts) {
            for (Map.Entry<Integer, MoneroWalletRpc> entry : registeredPorts.entrySet()) {
                if (walletRpc == entry.getValue()) return entry.getKey();
            }
            return -1;
        }
    }

    private void removePort(int port) {
        synchronized (registeredPorts) {
            registeredPorts.remove(port);
        }
    }

    private void unregisterExited() {
        synchronized (registeredPorts) {
            registeredPorts.values().removeIf(walletRpc -> walletRpc != null && walletRpc.getProcess() != null && !walletRpc.getProcess().isAlive());
        }
    }
}
