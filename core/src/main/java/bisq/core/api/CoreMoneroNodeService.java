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
package bisq.core.api;

import bisq.core.user.Preferences;
import bisq.core.xmr.MoneroNodeSettings;

import bisq.common.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.net.URI;
import java.net.URISyntaxException;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import monero.daemon.MoneroDaemonRpc;

/**
 * Start and stop or connect to a local Monero node.
 */
@Slf4j
@Singleton
public class CoreMoneroNodeService {

    private static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address to host Monero node
    private static final String LOCALHOST = "localhost";
    private static final String MONERO_NETWORK_TYPE = Config.baseCurrencyNetwork().getNetwork().toLowerCase();
    private static final String MONEROD_PATH = System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + "monerod";
    private static final String MONEROD_DATADIR =  System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + MONERO_NETWORK_TYPE;

    private final Preferences preferences;
    private final List<MoneroNodeServiceListener> listeners = new ArrayList<>();

    // required arguments
    private static final List<String> MONEROD_ARGS = Arrays.asList(
            MONEROD_PATH,
            "--" + MONERO_NETWORK_TYPE,
            "--no-igd",
            "--hide-my-port",
            "--rpc-login", "superuser:abctesting123" // TODO: remove authentication
    );

    // client to the local Monero node
    private MoneroDaemonRpc daemon;

    @Inject
    public CoreMoneroNodeService(Preferences preferences) {
        this.preferences = preferences;
        int rpcPort = 18081; // mainnet
        if (Config.baseCurrencyNetwork().isTestnet()) {
            rpcPort = 28081;
        } else if (Config.baseCurrencyNetwork().isStagenet()) {
            rpcPort = 38081;
        }
        this.daemon = new MoneroDaemonRpc("http://" + LOOPBACK_HOST + ":" + rpcPort, "superuser", "abctesting123"); // TODO: remove authentication
    }

    /**
     * Returns whether the given URI is on local host. // TODO: move to utils
     */
    public static boolean isLocalHost(String uri) throws URISyntaxException {
        String host = new URI(uri).getHost();
        return host.equals(CoreMoneroNodeService.LOOPBACK_HOST) || host.equals(CoreMoneroNodeService.LOCALHOST);
    }

    public void addListener(MoneroNodeServiceListener listener) {
        listeners.add(listener);
    }

    public boolean removeListener(MoneroNodeServiceListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Returns the client of the local monero node.
     */
    public MoneroDaemonRpc getDaemon() {
        return daemon;
    }

    /**
     * Returns whether a local monero node is running.
     */
    public boolean isMoneroNodeRunning() {
        return daemon.isConnected();
    }

    public MoneroNodeSettings getMoneroNodeSettings() {
        return preferences.getMoneroNodeSettings();
    }

    /**
     * Starts a local monero node from settings.
     */
    public void startMoneroNode() throws IOException {
        var settings = preferences.getMoneroNodeSettings();
        this.startMoneroNode(settings);
    }

    /**
     * Starts a local monero node. Throws MoneroError if the node cannot be started.
     * Persists the settings to preferences if the node started successfully.
     */
    public void startMoneroNode(MoneroNodeSettings settings) throws IOException {
        if (isMoneroNodeRunning()) throw new IllegalStateException("Local Monero node already running");

        log.info("Starting local Monero node: " + settings);

        var args = new ArrayList<>(MONEROD_ARGS);

        var dataDir = settings.getBlockchainPath();
        if (dataDir == null || dataDir.isEmpty()) {
            dataDir = MONEROD_DATADIR;
        }
        args.add("--data-dir=" + dataDir);

        var bootstrapUrl = settings.getBootstrapUrl();
        if (bootstrapUrl != null && !bootstrapUrl.isEmpty()) {
            args.add("--bootstrap-daemon-address=" + bootstrapUrl);
        }

        var flags = settings.getStartupFlags();
        if (flags != null) {
            args.addAll(flags);
        }

        daemon = new MoneroDaemonRpc(args); // start daemon as process and re-assign client
        preferences.setMoneroNodeSettings(settings);
        for (var listener : listeners) listener.onNodeStarted(daemon);
    }

    /**
     * Stops the current local monero node if we own its process.
     * Does not remove the last MoneroNodeSettings.
     */
    public void stopMoneroNode() {
        if (!isMoneroNodeRunning()) throw new IllegalStateException("Local Monero node is not running");
        if (daemon.getProcess() == null || !daemon.getProcess().isAlive()) throw new IllegalStateException("Cannot stop local Monero node because we don't own its process"); // TODO (woodser): remove isAlive() check after monero-java 0.5.4 which nullifies internal process
        daemon.stopProcess();
        for (var listener : listeners) listener.onNodeStopped();
    }
}
