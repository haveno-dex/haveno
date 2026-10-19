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

import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.wallet.MoneroWalletRpc;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Manages monero-wallet-rpc processes bound to ports.
 */
@Slf4j
public class MoneroWalletRpcManager {

    private static final String RPC_BIND_PORT_ARGUMENT = "--rpc-bind-port";
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
     * Start a new instance of monero-wallet-rpc.
     *
     * @param cmd command line parameters to start monero-wallet-rpc
     * @return a client connected to the monero-wallet-rpc instance
     */
    public MoneroWalletRpc startInstance(List<String> cmd) {
        if (isShutDownStarted) throw new MoneroError("Cannot start monero-wallet-rpc instance because shutdown is started");
        try {

            // register given port
            if (cmd.contains(RPC_BIND_PORT_ARGUMENT)) {
                int portArgumentPosition = cmd.indexOf(RPC_BIND_PORT_ARGUMENT) + 1;
                int port = Integer.parseInt(cmd.get(portArgumentPosition));
                synchronized (registeredPorts) {
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

        // unregister port
        int port = unregisterPort(walletRpc);

        // stop process
        String pid = walletRpc.getProcess() == null ? null : String.valueOf(walletRpc.getProcess().pid());
        if (force) log.info("Stopping MoneroWalletRpc path={}, port={}, pid={}, force={}", path, port, pid, force);
        else log.debug("Stopping MoneroWalletRpc path={}, port={}, pid={}, force={}", path, port, pid, force);
        walletRpc.stopProcess(force);
    }

    private int registerNextPort() throws IOException {
        synchronized (registeredPorts) {
            int port = startPort;
            while (registeredPorts.containsKey(port)) port++;
            registeredPorts.put(port, null);
            return port;
        }
    }

    private int unregisterPort(MoneroWalletRpc walletRpc) {
        synchronized (registeredPorts) {
            int port = -1;
            boolean found = false;
            for (Map.Entry<Integer, MoneroWalletRpc> entry : registeredPorts.entrySet()) {
                if (walletRpc == entry.getValue()) {
                    found = true;
                    try {
                        port = entry.getKey();
                        removePort(port);
                    } catch (Exception e) {
                        throw new MoneroError(e);
                    }
                    break;
                }
            }
            if (!found) throw new RuntimeException("MoneroWalletRpc instance not registered with a port");
            return port;
        }
    }

    private void removePort(int port) {
        synchronized (registeredPorts) {
            registeredPorts.remove(port);
        }
    }
}
