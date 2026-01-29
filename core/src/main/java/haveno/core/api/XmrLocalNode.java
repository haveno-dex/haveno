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
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.core.xmr.nodes.XmrNodesSetupPreferences;
import haveno.core.xmr.wallet.XmrWalletService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;


/**
 * Start and stop or connect to a local Monero node.
 */
@Slf4j
@Singleton
public class XmrLocalNode {

    // constants
    public static final long REFRESH_PERIOD_LOCAL_MS = 5000; // refresh period for local node
    public static final String MONEROD_NAME = Utilities.isWindows() ? "monerod.exe" : "monerod";
    public static final String MONEROD_PATH = XmrWalletService.MONERO_BINS_DIR + File.separator + MONEROD_NAME;
    private static final String MONEROD_DATADIR =  Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? XmrWalletService.MONERO_BINS_DIR + File.separator + Config.baseCurrencyNetwork().toString().toLowerCase() + File.separator + "node1" : null; // use default directory unless local

    // instance fields
    private MoneroDaemonRpc daemon;
    private final Config config;
    private final Preferences preferences;
    private final XmrNodes xmrNodes;
    private final List<XmrLocalNodeListener> listeners = new ArrayList<>();
    private TaskLooper monerodPoller;

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

    @Inject
    public XmrLocalNode(Config config,
                        Preferences preferences,
                        XmrNodes xmrNodes) {
        this.config = config;
        this.preferences = preferences;
        this.xmrNodes = xmrNodes;
        this.daemon = new MoneroDaemonRpc(getUri());
        startPolling();
    }

    private void startPolling() {
        monerodPoller = new TaskLooper(() -> pollMonerod());
        monerodPoller.start(REFRESH_PERIOD_LOCAL_MS);
    }

    private void pollMonerod() {
        
        // collect state before check
        Boolean onlineBefore = daemon.getRpcConnection().isOnline();
        Boolean authenticatedBefore = daemon.getRpcConnection().isAuthenticated();
        Boolean syncedWithinToleranceBefore = null;
        MoneroDaemonInfo lastInfo = XmrConnectionService.getCachedDaemonInfo(daemon.getRpcConnection());
        if (lastInfo != null) syncedWithinToleranceBefore = XmrConnectionService.isSyncedWithinTolerance(lastInfo);

        // check connection
        checkConnection();
        Boolean onlineAfter = daemon.getRpcConnection().isOnline();
        Boolean authenticatedAfter = daemon.getRpcConnection().isAuthenticated();
        Boolean syncedWithinToleranceAfter = null;
        lastInfo = XmrConnectionService.getCachedDaemonInfo(daemon.getRpcConnection());
        if (lastInfo != null) syncedWithinToleranceAfter = XmrConnectionService.isSyncedWithinTolerance(lastInfo);

        // announce if connection changed
        boolean change = onlineBefore != onlineAfter;
        change = change || authenticatedBefore != authenticatedAfter;
        change = change || syncedWithinToleranceBefore != syncedWithinToleranceAfter;
        if (change) {
            for (var listener : listeners) listener.onConnectionChanged(daemon.getRpcConnection());
        }
    }

    public String getUri() {
        return "http://" + HavenoUtils.LOOPBACK_HOST + ":" + HavenoUtils.getDefaultMoneroPort();
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
        if (config.ignoreLocalXmrNode) return true;

        // ignore if fixed connection is not local
        if (!"".equals(config.xmrNode)) return !HavenoUtils.isLocalHost(config.xmrNode);

        // check if local node is within configuration
        boolean hasConfiguredLocalNode = false;
        for (XmrNode node : xmrNodes.selectPreferredNodes(new XmrNodesSetupPreferences(preferences))) {
            if (node.hasClearNetAddress() && equalsUri(node.getClearNetUri())) {
                hasConfiguredLocalNode = true;
                break;
            }
        }
        return !hasConfiguredLocalNode;
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
        try {
            return HavenoUtils.isLocalHost(uri) && MoneroUtils.parseUri(uri).getPort() == HavenoUtils.getDefaultMoneroPort();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if local Monero node is detected.
     */
    public boolean isDetected() {
        checkConnection();
        return Boolean.TRUE.equals(daemon.getRpcConnection().isOnline());
    }

    /**
     * Check if connected to local Monero node.
     */
    public boolean isConnected() {
        return Boolean.TRUE.equals(daemon.getRpcConnection().isConnected());
    }

    private void checkConnection() {
        XmrConnectionService.checkConnection(daemon.getRpcConnection());
    }

    public XmrNodeSettings getNodeSettings() {
        return preferences.getXmrNodeSettings();
    }

    /**
     * Start a local Monero node from settings.
     */
    public void start() throws IOException {
        var settings = preferences.getXmrNodeSettings();
        this.start(settings);
    }

    /**
     * Start local Monero node. Throws MoneroError if the node cannot be started.
     * Persist the settings to preferences if the node started successfully.
     */
    public void start(XmrNodeSettings settings) throws IOException {
        if (isDetected()) throw new IllegalStateException("Local Monero node already online");

        log.info("Starting local Monero node: " + settings);

        var args = new ArrayList<>(MONEROD_ARGS);

        var dataDir = "";
        if (config.xmrBlockchainPath == null || config.xmrBlockchainPath.isEmpty()) {
            dataDir = settings.getBlockchainPath();
            if (dataDir == null || dataDir.isEmpty()) {
                dataDir = MONEROD_DATADIR;
            }
        } else {
            dataDir = config.xmrBlockchainPath; // startup config overrides settings
        }
        if (dataDir != null && !dataDir.isEmpty()) {
            args.add("--data-dir=" + dataDir);
        }

        var bootstrapUrl = settings.getBootstrapUrl();
        if (bootstrapUrl != null && !bootstrapUrl.isEmpty()) {
            args.add("--bootstrap-daemon-address=" + bootstrapUrl);
        }

        var syncBlockchain = settings.getSyncBlockchain();
        if (syncBlockchain != null && !syncBlockchain) {
            args.add("--no-sync");
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
    public void stop() {
        if (!isDetected()) throw new IllegalStateException("Local Monero node is not running");
        if (daemon.getProcess() == null || !daemon.getProcess().isAlive()) throw new IllegalStateException("Cannot stop local Monero node because we don't own its process"); // TODO (woodser): remove isAlive() check after monero-java 0.5.4 which nullifies internal process
        daemon.stopProcess();
        for (var listener : listeners) listener.onNodeStopped();
    }
}
