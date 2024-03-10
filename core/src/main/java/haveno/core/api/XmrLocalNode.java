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
package haveno.core.api;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.util.Utilities;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.XmrNodeSettings;
import haveno.core.xmr.nodes.XmrNodes;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroUtils;
import monero.daemon.MoneroDaemonRpc;


/**
 * Start and stop or connect to a local Monero node.
 */
@Slf4j
@Singleton
public class XmrLocalNode {

    // constants
    public static final long REFRESH_PERIOD_LOCAL_MS = 5000; // refresh period for local node
    public static final String MONEROD_DIR = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? System.getProperty("user.dir") + File.separator + ".localnet" : Config.appDataDir().getAbsolutePath();
    public static final String MONEROD_NAME = Utilities.isWindows() ? "monerod.exe" : "monerod";
    public static final String MONEROD_PATH = MONEROD_DIR + File.separator + MONEROD_NAME;
    private static final String MONEROD_DATADIR =  Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? MONEROD_DIR + File.separator + Config.baseCurrencyNetwork().toString().toLowerCase() + File.separator + "node1" : null; // use default directory unless local

    // instance fields
    private MoneroDaemonRpc daemon;
    private MoneroConnectionManager connectionManager;
    private final Config config;
    private final Preferences preferences;
    private final List<XmrLocalNodeListener> listeners = new ArrayList<>();

    // required arguments
    private static final List<String> MONEROD_ARGS = new ArrayList<String>();
    static {
        MONEROD_ARGS.add(MONEROD_PATH);
        MONEROD_ARGS.add("--no-igd");
        MONEROD_ARGS.add("--hide-my-port");
        MONEROD_ARGS.add("--p2p-bind-ip");
        MONEROD_ARGS.add(HavenoUtils.LOOPBACK_HOST);
        if (!Config.baseCurrencyNetwork().isMainnet()) MONEROD_ARGS.add("--" + Config.baseCurrencyNetwork().getNetwork().toLowerCase());
    }

    // default rpc ports
    private static Integer rpcPort;
    static {
        if (Config.baseCurrencyNetwork().isMainnet()) rpcPort = 18081;
        else if (Config.baseCurrencyNetwork().isTestnet()) rpcPort = 28081;
        else if (Config.baseCurrencyNetwork().isStagenet()) rpcPort = 38081;
        else throw new RuntimeException("Base network is not local testnet, stagenet, or mainnet");
    }

    @Inject
    public XmrLocalNode(Config config, Preferences preferences) {
        this.config = config;
        this.preferences = preferences;
        this.daemon = new MoneroDaemonRpc("http://" + HavenoUtils.LOOPBACK_HOST + ":" + rpcPort);

        // initialize connection manager to listen to local connection
        this.connectionManager = new MoneroConnectionManager().setConnection(daemon.getRpcConnection());
        this.connectionManager.setTimeout(REFRESH_PERIOD_LOCAL_MS);
        this.connectionManager.addListener((connection) -> {
            for (var listener : listeners) listener.onConnectionChanged(connection); // notify of connection changes
        });
        this.connectionManager.startPolling(REFRESH_PERIOD_LOCAL_MS);
    }

    /**
     * Returns whether Haveno should use a local Monero node, meaning that a node was
     * detected and conditions under which it should be ignored have not been met. If
     * the local node should be ignored, a call to this method will not trigger an
     * unnecessary detection attempt.
     */
    public boolean shouldBeUsed() {
        return !shouldBeIgnored() && isDetected();
    }

    /**
     * Returns whether Haveno should ignore a local Monero node even if it is usable.
     */
    public boolean shouldBeIgnored() {
        return config.ignoreLocalXmrNode || preferences.getMoneroNodesOption() == XmrNodes.MoneroNodesOption.CUSTOM;
    }

    public void addListener(XmrLocalNodeListener listener) {
        listeners.add(listener);
    }

    public boolean removeListener(XmrLocalNodeListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Return the client of the local Monero node.
     */
    public MoneroDaemonRpc getDaemon() {
        return daemon;
    }

    public boolean equalsUri(String uri) {
        return HavenoUtils.isLocalHost(uri) && MoneroUtils.parseUri(uri).getPort() == rpcPort;
    }

    /**
     * Check if local Monero node is detected.
     */
    public boolean isDetected() {
        checkConnection();
        return Boolean.TRUE.equals(connectionManager.getConnection().isOnline());
    }

    /**
     * Check if connected to local Monero node.
     */
    public boolean isConnected() {
        checkConnection();
        return Boolean.TRUE.equals(connectionManager.isConnected());
    }

    private void checkConnection() {
        connectionManager.checkConnection();
    }

    public XmrNodeSettings getNodeSettings() {
        return preferences.getXmrNodeSettings();
    }

    /**
     * Start a local Monero node from settings.
     */
    public void startMoneroNode() throws IOException {
        var settings = preferences.getXmrNodeSettings();
        this.startNode(settings);
    }

    /**
     * Start local Monero node. Throws MoneroError if the node cannot be started.
     * Persist the settings to preferences if the node started successfully.
     */
    public void startNode(XmrNodeSettings settings) throws IOException {
        if (isDetected()) throw new IllegalStateException("Local Monero node already online");

        log.info("Starting local Monero node: " + settings);

        var args = new ArrayList<>(MONEROD_ARGS);

        var dataDir = settings.getBlockchainPath();
        if (dataDir == null || dataDir.isEmpty()) {
            dataDir = MONEROD_DATADIR;
        }
        if (dataDir != null) args.add("--data-dir=" + dataDir);

        var bootstrapUrl = settings.getBootstrapUrl();
        if (bootstrapUrl != null && !bootstrapUrl.isEmpty()) {
            args.add("--bootstrap-daemon-address=" + bootstrapUrl);
        }

        var flags = settings.getStartupFlags();
        if (flags != null) {
            args.addAll(flags);
        }

        daemon = new MoneroDaemonRpc(args); // start daemon as process and re-assign client
        preferences.setXmrNodeSettings(settings);
        for (var listener : listeners) listener.onNodeStarted(daemon);
    }

    /**
     * Stop the current local Monero node if we own its process.
     * Does not remove the last XmrNodeSettings.
     */
    public void stopNode() {
        if (!isDetected()) throw new IllegalStateException("Local Monero node is not running");
        if (daemon.getProcess() == null || !daemon.getProcess().isAlive()) throw new IllegalStateException("Cannot stop local Monero node because we don't own its process"); // TODO (woodser): remove isAlive() check after monero-java 0.5.4 which nullifies internal process
        daemon.stopProcess();
        for (var listener : listeners) listener.onNodeStopped();
    }
}
