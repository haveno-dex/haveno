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
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.core.xmr.nodes.XmrNodesSetupPreferences;
import haveno.core.xmr.setup.DownloadListener;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroConnectionManagerListener;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import monero.daemon.model.MoneroPeer;

@Slf4j
@Singleton
public final class XmrConnectionService {

    private static final int MIN_BROADCAST_CONNECTIONS = 0; // TODO: 0 for stagenet, 5+ for mainnet
    private static final long REFRESH_PERIOD_HTTP_MS = 20000; // refresh period when connected to remote node over http
    private static final long REFRESH_PERIOD_ONION_MS = 30000; // refresh period when connected to remote node over tor

    private final Object lock = new Object();
    private final Object pollLock = new Object();
    private final Object listenerLock = new Object();
    private final Config config;
    private final CoreContext coreContext;
    private final Preferences preferences;
    private final CoreAccountService accountService;
    private final XmrNodes xmrNodes;
    private final XmrLocalNode xmrLocalNode;
    private final MoneroConnectionManager connectionManager;
    private final EncryptedConnectionList connectionList;
    private final ObjectProperty<List<MoneroPeer>> peers = new SimpleObjectProperty<>();
    private final ObjectProperty<MoneroRpcConnection> connectionProperty = new SimpleObjectProperty<>();
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();
    @Getter
    private final StringProperty connectionServiceErrorMsg = new SimpleStringProperty();
    private final LongProperty numUpdates = new SimpleLongProperty(0);
    private Socks5ProxyProvider socks5ProxyProvider;

    private boolean isInitialized;
    private boolean pollInProgress;
    private MoneroDaemonRpc daemon;
    private Boolean isConnected = false;
    @Getter
    private MoneroDaemonInfo lastInfo;
    private Long lastLogPollErrorTimestamp;
    private long lastLogDaemonNotSyncedTimestamp;
    private Long syncStartHeight;
    private TaskLooper daemonPollLooper;
    private long lastRefreshPeriodMs;
    @Getter
    private boolean isShutDownStarted;
    private List<MoneroConnectionManagerListener> listeners = new ArrayList<>();

    // connection switching
    private static final int EXCLUDE_CONNECTION_SECONDS = 180;
    private static final int MAX_SWITCH_REQUESTS_PER_MINUTE = 2;
    private static final int SKIP_SWITCH_WITHIN_MS = 10000;
    private int numRequestsLastMinute;
    private long lastSwitchTimestamp;
    private Set<MoneroRpcConnection> excludedConnections = new HashSet<>();

    @Inject
    public XmrConnectionService(P2PService p2PService,
                                        Config config,
                                        CoreContext coreContext,
                                        Preferences preferences,
                                        WalletsSetup walletsSetup,
                                        CoreAccountService accountService,
                                        XmrNodes xmrNodes,
                                        XmrLocalNode xmrLocalNode,
                                        MoneroConnectionManager connectionManager,
                                        EncryptedConnectionList connectionList,
                                        Socks5ProxyProvider socks5ProxyProvider) {
        this.config = config;
        this.coreContext = coreContext;
        this.preferences = preferences;
        this.accountService = accountService;
        this.xmrNodes = xmrNodes;
        this.xmrLocalNode = xmrLocalNode;
        this.connectionManager = connectionManager;
        this.connectionList = connectionList;
        this.socks5ProxyProvider = socks5ProxyProvider;

        // initialize when connected to p2p network
        p2PService.addP2PServiceListener(new P2PServiceListener() {
            @Override
            public void onTorNodeReady() {
                initialize();
            }
            @Override
            public void onHiddenServicePublished() {}
            @Override
            public void onDataReceived() {}
            @Override
            public void onNoSeedNodeAvailable() {}
            @Override
            public void onNoPeersAvailable() {}
            @Override
            public void onUpdatedDataReceived() {}
        });
    }

    public void onShutDownStarted() {
        log.info("{}.onShutDownStarted()", getClass().getSimpleName());
        isShutDownStarted = true;
    }

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());
        isInitialized = false;
        synchronized (lock) {
            if (daemonPollLooper != null) daemonPollLooper.stop();
            daemon = null;
        }
    }

    // ------------------------ CONNECTION MANAGEMENT -------------------------

    public MoneroDaemonRpc getDaemon() {
        accountService.checkAccountOpen();
        return this.daemon;
    }

    public String getProxyUri() {
        return socks5ProxyProvider.getSocks5Proxy() == null ? null : socks5ProxyProvider.getSocks5Proxy().getInetAddress().getHostAddress() + ":" + socks5ProxyProvider.getSocks5Proxy().getPort();
    }

    public void addConnectionListener(MoneroConnectionManagerListener listener) {
        synchronized (listenerLock) {
            listeners.add(listener);
        }
    }

    public Boolean isConnected() {
        return isConnected;
    }

    public void addConnection(MoneroRpcConnection connection) {
        accountService.checkAccountOpen();
        if (coreContext.isApiUser()) connectionList.addConnection(connection);
        connectionManager.addConnection(connection);
    }

    public void removeConnection(String uri) {
        accountService.checkAccountOpen();
        connectionList.removeConnection(uri);
        connectionManager.removeConnection(uri);
    }

    public MoneroRpcConnection getConnection() {
        accountService.checkAccountOpen();
        return connectionManager.getConnection();
    }

    public List<MoneroRpcConnection> getConnections() {
        accountService.checkAccountOpen();
        return connectionManager.getConnections();
    }

    public void setConnection(String connectionUri) {
        accountService.checkAccountOpen();
        connectionManager.setConnection(connectionUri); // listener will update connection list
    }

    public void setConnection(MoneroRpcConnection connection) {
        accountService.checkAccountOpen();
        connectionManager.setConnection(connection); // listener will update connection list
    }

    public MoneroRpcConnection checkConnection() {
        accountService.checkAccountOpen();
        connectionManager.checkConnection();
        return getConnection();
    }

    public List<MoneroRpcConnection> checkConnections() {
        accountService.checkAccountOpen();
        connectionManager.checkConnections();
        return getConnections();
    }

    public void startCheckingConnection(Long refreshPeriod) {
        accountService.checkAccountOpen();
        connectionList.setRefreshPeriod(refreshPeriod);
        updatePolling();
    }

    public void stopCheckingConnection() {
        accountService.checkAccountOpen();
        connectionList.setRefreshPeriod(-1L);
        updatePolling();
    }

    public MoneroRpcConnection getBestAvailableConnection() {
        accountService.checkAccountOpen();
        List<MoneroRpcConnection> ignoredConnections = new ArrayList<MoneroRpcConnection>();
        addLocalNodeIfIgnored(ignoredConnections);
        return connectionManager.getBestAvailableConnection(ignoredConnections.toArray(new MoneroRpcConnection[0]));
    }

    private MoneroRpcConnection getBestAvailableConnection(Collection<MoneroRpcConnection> ignoredConnections) {
        accountService.checkAccountOpen();
        Set<MoneroRpcConnection> ignoredConnectionsSet = new HashSet<>(ignoredConnections);
        addLocalNodeIfIgnored(ignoredConnectionsSet);
        return connectionManager.getBestAvailableConnection(ignoredConnectionsSet.toArray(new MoneroRpcConnection[0]));
    }

    private void addLocalNodeIfIgnored(Collection<MoneroRpcConnection> ignoredConnections) {
        if (xmrLocalNode.shouldBeIgnored() && connectionManager.hasConnection(xmrLocalNode.getUri())) ignoredConnections.add(connectionManager.getConnectionByUri(xmrLocalNode.getUri()));
    }

    private void switchToBestConnection() {
        if (isFixedConnection() || !connectionManager.getAutoSwitch()) {
            log.info("Skipping switch to best Monero connection because connection is fixed or auto switch is disabled");
            return;
        }
        MoneroRpcConnection bestConnection = getBestAvailableConnection();
        if (bestConnection != null) setConnection(bestConnection);
    }

    public synchronized boolean requestSwitchToNextBestConnection() {
        return requestSwitchToNextBestConnection(null);
    }

    public synchronized boolean requestSwitchToNextBestConnection(MoneroRpcConnection sourceConnection) {
        log.warn("Requesting switch to next best monerod, source monerod={}", sourceConnection == null ? getConnection() == null ? null : getConnection().getUri() : sourceConnection.getUri());

        // skip if shut down started
        if (isShutDownStarted) {
            log.warn("Skipping switch to next best Monero connection because shut down has started");
            return false;
        }

        // skip if connection is already switched
        if (sourceConnection != null && sourceConnection != getConnection()) {
            log.warn("Skipping switch to next best Monero connection because source connection is not current connection");
            return false;
        }

        // skip if connection is fixed
        if (isFixedConnection() || !connectionManager.getAutoSwitch()) {
            log.warn("Skipping switch to next best Monero connection because connection is fixed or auto switch is disabled");
            return false;
        }

        // skip if last switch was too recent
        boolean skipSwitch = System.currentTimeMillis() - lastSwitchTimestamp < SKIP_SWITCH_WITHIN_MS;
        if (skipSwitch) {
            log.warn("Skipping switch to next best Monero connection because last switch was less than {} seconds ago", SKIP_SWITCH_WITHIN_MS / 1000);
            return false;
        }

        // skip if too many requests in the last minute
        if (numRequestsLastMinute > MAX_SWITCH_REQUESTS_PER_MINUTE) {
            log.warn("Skipping switch to next best Monero connection because more than {} requests were made in the last minute", MAX_SWITCH_REQUESTS_PER_MINUTE);
            return false;
        }

        // increment request count
        numRequestsLastMinute++;
        UserThread.runAfter(() -> numRequestsLastMinute--, 60); // decrement after one minute

        // exclude current connection
        MoneroRpcConnection currentConnection = getConnection();
        if (currentConnection != null) excludedConnections.add(currentConnection);

        // get connection to switch to
        MoneroRpcConnection bestConnection = getBestAvailableConnection(excludedConnections);

        // remove from excluded connections after period
        UserThread.runAfter(() -> {
            if (currentConnection != null) excludedConnections.remove(currentConnection);
        }, EXCLUDE_CONNECTION_SECONDS);

        // return if no connection to switch to
        if (bestConnection == null) {
            log.warn("No connection to switch to");
            return false;
        }

        // switch to best connection
        lastSwitchTimestamp = System.currentTimeMillis();
        setConnection(bestConnection);
        return true;
    }

    public void setAutoSwitch(boolean autoSwitch) {
        accountService.checkAccountOpen();
        connectionManager.setAutoSwitch(autoSwitch);
        connectionList.setAutoSwitch(autoSwitch);
    }

    public boolean getAutoSwitch() {
        accountService.checkAccountOpen();
        return connectionList.getAutoSwitch();
    }

    public boolean isConnectionLocalHost() {
        return isConnectionLocalHost(getConnection());
    }

    public boolean isProxyApplied() {
        return isProxyApplied(getConnection());
    }

    public long getRefreshPeriodMs() {
        return connectionList.getRefreshPeriod() > 0 ? connectionList.getRefreshPeriod() : getDefaultRefreshPeriodMs(false);
    }

    private long getInternalRefreshPeriodMs() {
        return connectionList.getRefreshPeriod() > 0 ? connectionList.getRefreshPeriod() : getDefaultRefreshPeriodMs(true);
    }

    public void verifyConnection() {
        if (daemon == null) throw new RuntimeException("No connection to Monero node");
        if (!Boolean.TRUE.equals(isConnected())) throw new RuntimeException("No connection to Monero node");
        if (!isSyncedWithinTolerance()) throw new RuntimeException("Monero node is not synced");
    }

    public boolean isSyncedWithinTolerance() {
        Long targetHeight = getTargetHeight();
        if (targetHeight == null) return false;
        if (targetHeight - chainHeight.get() <= 3) return true; // synced if within 3 blocks of target height
        return false;
    }

    public Long getTargetHeight() {
        if (lastInfo == null) return null;
        return lastInfo.getTargetHeight() == 0 ? chainHeight.get() : lastInfo.getTargetHeight(); // monerod sync_info's target_height returns 0 when node is fully synced
    }

    // ----------------------------- APP METHODS ------------------------------

    public ReadOnlyIntegerProperty numPeersProperty() {
        return numPeers;
    }

    public ReadOnlyObjectProperty<List<MoneroPeer>> peerConnectionsProperty() {
        return peers;
    }

    public ReadOnlyObjectProperty<MoneroRpcConnection> connectionProperty() {
        return connectionProperty;
    }

    public boolean hasSufficientPeersForBroadcast() {
        return numPeers.get() >= getMinBroadcastConnections();
    }

    public LongProperty chainHeightProperty() {
        return chainHeight;
    }

    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return downloadListener.percentageProperty();
    }

    public int getMinBroadcastConnections() {
        return MIN_BROADCAST_CONNECTIONS;
    }

    public boolean isDownloadComplete() {
        return downloadPercentageProperty().get() == 1d;
    }

    public ReadOnlyLongProperty numUpdatesProperty() {
        return numUpdates;
    }

    // ------------------------------- HELPERS --------------------------------

    private void doneDownload() {
        downloadListener.doneDownload();
    }

    private boolean isConnectionLocalHost(MoneroRpcConnection connection) {
        return connection != null && HavenoUtils.isLocalHost(connection.getUri());
    }

    private long getDefaultRefreshPeriodMs(boolean internal) {
        MoneroRpcConnection connection = getConnection();
        if (connection == null) return XmrLocalNode.REFRESH_PERIOD_LOCAL_MS;
        if (isConnectionLocalHost(connection)) {
            if (internal) return XmrLocalNode.REFRESH_PERIOD_LOCAL_MS;
            if (lastInfo != null && (lastInfo.getHeightWithoutBootstrap() != null && lastInfo.getHeightWithoutBootstrap() > 0 && lastInfo.getHeightWithoutBootstrap() < lastInfo.getHeight())) {
                return REFRESH_PERIOD_HTTP_MS; // refresh slower if syncing or bootstrapped
            } else {
                return XmrLocalNode.REFRESH_PERIOD_LOCAL_MS; // TODO: announce faster refresh after done syncing
            }
        } else if (isProxyApplied(connection)) {
            return REFRESH_PERIOD_ONION_MS;
        } else {
            return REFRESH_PERIOD_HTTP_MS;
        }
    }

    private boolean isProxyApplied(MoneroRpcConnection connection) {
        if (connection == null) return false;
        return connection.isOnion() || (preferences.getUseTorForXmr().isUseTorForXmr() && !HavenoUtils.isPrivateIp(connection.getUri()));
    }

    private void initialize() {

        // initialize connections
        initializeConnections();

        // listen for account to be opened or password changed
        accountService.addListener(new AccountServiceListener() {

            @Override
            public void onAccountOpened() {
                try {
                    log.info(getClass() + ".onAccountOpened() called");
                    initialize();
                } catch (Exception e) {
                    log.error("Error initializing connection service after account opened, error={}\n", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                log.info(getClass() + ".onPasswordChanged({}, {}) called", oldPassword == null ? null : "***", newPassword == null ? null : "***");
                connectionList.changePassword(oldPassword, newPassword);
            }
        });
    }

    private void initializeConnections() {
        synchronized (lock) {

            // reset connection manager
            connectionManager.reset();
            connectionManager.setTimeout(REFRESH_PERIOD_HTTP_MS);

            // run once
            if (!isInitialized) {

                // register local node listener
                xmrLocalNode.addListener(new XmrLocalNodeListener() {
                    @Override
                    public void onNodeStarted(MoneroDaemonRpc daemon) {
                        log.info("Local monero node started");
                    }

                    @Override
                    public void onNodeStopped() {
                        log.info("Local monero node stopped");
                    }

                    @Override
                    public void onConnectionChanged(MoneroRpcConnection connection) {
                        log.info("Local monerod connection changed: " + connection);

                        // skip if ignored
                        if (isShutDownStarted || !connectionManager.getAutoSwitch() || !accountService.isAccountOpen() ||
                            !connectionManager.hasConnection(connection.getUri()) || xmrLocalNode.shouldBeIgnored()) return;

                        // check connection
                        boolean isConnected = false;
                        if (xmrLocalNode.isConnected()) {
                            MoneroRpcConnection conn = connectionManager.getConnectionByUri(connection.getUri());
                            conn.checkConnection(connectionManager.getTimeout());
                            isConnected = Boolean.TRUE.equals(conn.isConnected());
                        }

                        // update connection
                        if (isConnected) {
                            setConnection(connection.getUri());
                        } else if (getConnection() != null && getConnection().getUri().equals(connection.getUri())) {
                            MoneroRpcConnection bestConnection = getBestAvailableConnection();
                            if (bestConnection != null) setConnection(bestConnection); // switch to best connection
                        }
                    }
                });
            }

            // restore connections
            if ("".equals(config.xmrNode)) {

                // load previous or default connections
                if (coreContext.isApiUser()) {

                    // load previous connections
                    for (MoneroRpcConnection connection : connectionList.getConnections()) connectionManager.addConnection(connection);
                    log.info("Read " + connectionList.getConnections().size() + " previous connections from disk");

                    // add default connections
                    for (XmrNode node : xmrNodes.getAllXmrNodes()) {
                        if (node.hasClearNetAddress()) {
                            MoneroRpcConnection connection = new MoneroRpcConnection(node.getAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                            if (!connectionList.hasConnection(connection.getUri())) addConnection(connection);
                        }
                        if (node.hasOnionAddress()) {
                            MoneroRpcConnection connection = new MoneroRpcConnection(node.getOnionAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                            if (!connectionList.hasConnection(connection.getUri())) addConnection(connection);
                        }
                    }
                } else {

                    // add default connections
                    for (XmrNode node : xmrNodes.selectPreferredNodes(new XmrNodesSetupPreferences(preferences))) {
                        if (node.hasClearNetAddress()) {
                            MoneroRpcConnection connection = new MoneroRpcConnection(node.getAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                            addConnection(connection);
                        }
                        if (node.hasOnionAddress()) {
                            MoneroRpcConnection connection = new MoneroRpcConnection(node.getOnionAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                            addConnection(connection);
                        }
                    }
                }

                // restore last connection
                if (isFixedConnection()) {
                    if (getConnections().size() != 1) throw new IllegalStateException("Expected connection list to have 1 fixed connection but was: " + getConnections().size());
                    connectionManager.setConnection(getConnections().get(0));
                } else if (connectionList.getCurrentConnectionUri().isPresent() && connectionManager.hasConnection(connectionList.getCurrentConnectionUri().get())) {
                    if (!xmrLocalNode.shouldBeIgnored() || !xmrLocalNode.equalsUri(connectionList.getCurrentConnectionUri().get())) {
                        connectionManager.setConnection(connectionList.getCurrentConnectionUri().get());
                    }
                }

                // set connection proxies
                log.info("TOR proxy URI: " + getProxyUri());
                for (MoneroRpcConnection connection : connectionManager.getConnections()) {
                    if (isProxyApplied(connection)) connection.setProxyUri(getProxyUri());
                }

                // restore auto switch
                if (coreContext.isApiUser()) connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
                else connectionManager.setAutoSwitch(true);

                // start local node if applicable
                maybeStartLocalNode();

                // update connection
                if (!isFixedConnection() && (connectionManager.getConnection() == null || connectionManager.getAutoSwitch())) {
                    MoneroRpcConnection bestConnection = getBestAvailableConnection();
                    if (bestConnection != null) setConnection(bestConnection);
                }
            } else if (!isInitialized) {

                // set connection from startup argument if given
                connectionManager.setAutoSwitch(false);
                MoneroRpcConnection connection = new MoneroRpcConnection(config.xmrNode, config.xmrNodeUsername, config.xmrNodePassword).setPriority(1);
                if (isProxyApplied(connection)) connection.setProxyUri(getProxyUri());
                connectionManager.setConnection(connection);

                // start local node if applicable
                maybeStartLocalNode();
            }

            // register connection listener
            connectionManager.addListener(this::onConnectionChanged);
            isInitialized = true;
        }

        // notify initial connection
        onConnectionChanged(connectionManager.getConnection());
    }

    private void maybeStartLocalNode() {

        // skip if seed node
        if (HavenoUtils.isSeedNode()) return;

        // start local node if offline and used as last connection
        if (connectionManager.getConnection() != null && xmrLocalNode.equalsUri(connectionManager.getConnection().getUri()) && !xmrLocalNode.isDetected() && !xmrLocalNode.shouldBeIgnored()) {
            try {
                log.info("Starting local node");
                xmrLocalNode.start();
            } catch (Exception e) {
                log.error("Unable to start local monero node, error={}\n", e.getMessage(), e);
            }
        }
    }

    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        if (isShutDownStarted || !accountService.isAccountOpen()) return;
        if (currentConnection == null) {
            log.warn("Setting daemon connection to null");
            Thread.dumpStack();
        }
        synchronized (lock) {
            if (currentConnection == null) {
                daemon = null;
                isConnected = false;
                connectionList.setCurrentConnectionUri(null);
            } else {
                daemon = new MoneroDaemonRpc(currentConnection);
                isConnected = currentConnection.isConnected();
                connectionList.removeConnection(currentConnection.getUri());
                connectionList.addConnection(currentConnection);
                connectionList.setCurrentConnectionUri(currentConnection.getUri());
            }

            // set connection property on user thread
            UserThread.execute(() -> {
                connectionProperty.set(currentConnection);
                numUpdates.set(numUpdates.get() + 1);
            });
        }
        
        // update polling
        doPollDaemon();
        if (currentConnection != getConnection()) return; // polling can change connection
        UserThread.runAfter(() -> updatePolling(), getInternalRefreshPeriodMs() / 1000);

        // notify listeners in parallel
        log.info("XmrConnectionService.onConnectionChanged() uri={}, connected={}", currentConnection == null ? null : currentConnection.getUri(), currentConnection == null ? "false" : isConnected);
        synchronized (listenerLock) {
            for (MoneroConnectionManagerListener listener : listeners) {
                ThreadUtils.submitToPool(() -> listener.onConnectionChanged(currentConnection));
            }
        }
    }

    private void updatePolling() {
        stopPolling();
        if (connectionList.getRefreshPeriod() >= 0) startPolling(); // 0 means default refresh poll
    }

    private void startPolling() {
        synchronized (lock) {
            if (daemonPollLooper != null) daemonPollLooper.stop();
            daemonPollLooper = new TaskLooper(() -> pollDaemon());
            daemonPollLooper.start(getInternalRefreshPeriodMs());
        }
    }

    private void stopPolling() {
        synchronized (lock) {
            if (daemonPollLooper != null) {
                daemonPollLooper.stop();
                daemonPollLooper = null;
            }
        }
    }

    private void pollDaemon() {
        if (pollInProgress) return;
        doPollDaemon();
    }

    private void doPollDaemon() {
        synchronized (pollLock) {
            pollInProgress = true;
            if (isShutDownStarted) return;
            try {

                // poll daemon
                if (daemon == null) switchToBestConnection();
                if (daemon == null) throw new RuntimeException("No connection to Monero daemon");
                try {
                    lastInfo = daemon.getInfo();
                } catch (Exception e) {

                    // skip handling if shutting down
                    if (isShutDownStarted) return;

                    // fallback to provided nodes if custom connection fails on startup
                    if (lastInfo == null && "".equals(config.xmrNode) && preferences.getMoneroNodesOption() == XmrNodes.MoneroNodesOption.CUSTOM) {
                        log.warn("Failed to fetch daemon info from custom node on startup, falling back to provided nodes: " + e.getMessage());
                        preferences.setMoneroNodesOptionOrdinal(XmrNodes.MoneroNodesOption.PROVIDED.ordinal());
                        initializeConnections();
                        return;
                    }

                    // log error message periodically
                    if (lastLogPollErrorTimestamp == null || System.currentTimeMillis() - lastLogPollErrorTimestamp > HavenoUtils.LOG_POLL_ERROR_PERIOD_MS) {
                        log.warn("Failed to fetch daemon info, trying to switch to best connection, error={}", e.getMessage());
                        if (DevEnv.isDevMode()) log.error(ExceptionUtils.getStackTrace(e));
                        lastLogPollErrorTimestamp = System.currentTimeMillis();
                    }

                    // switch to best connection
                    switchToBestConnection();
                    lastInfo = daemon.getInfo(); // caught internally if still fails
                }

                // connected to daemon
                isConnected = true;

                // determine if blockchain is syncing locally
                boolean blockchainSyncing = lastInfo.getHeight().equals(lastInfo.getHeightWithoutBootstrap()) || (lastInfo.getTargetHeight().equals(0l) && lastInfo.getHeightWithoutBootstrap().equals(0l)); // blockchain is syncing if height equals height without bootstrap, or target height and height without bootstrap both equal 0

                // write sync status to preferences
                preferences.getXmrNodeSettings().setSyncBlockchain(blockchainSyncing);

                // throttle warnings if daemon not synced
                if (!isSyncedWithinTolerance() && System.currentTimeMillis() - lastLogDaemonNotSyncedTimestamp > HavenoUtils.LOG_DAEMON_NOT_SYNCED_WARN_PERIOD_MS) {
                    log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), getTargetHeight());
                    lastLogDaemonNotSyncedTimestamp = System.currentTimeMillis();
                }

                // announce connection change if refresh period changes
                if (getRefreshPeriodMs() != lastRefreshPeriodMs) {
                    lastRefreshPeriodMs = getRefreshPeriodMs();
                    onConnectionChanged(getConnection()); // causes new poll
                    return;
                }

                // update properties on user thread
                UserThread.execute(() -> {

                    // set chain height
                    chainHeight.set(lastInfo.getHeight());

                    // update sync progress
                    boolean isTestnet = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL;
                    if (lastInfo.isSynchronized() || isTestnet) doneDownload(); // TODO: skipping synchronized check for testnet because CI tests do not sync 3rd local node, see "Can manage Monero daemon connections"
                    else if (lastInfo.isBusySyncing()) {
                        long targetHeight = lastInfo.getTargetHeight();
                        long blocksLeft = targetHeight - lastInfo.getHeight();
                        if (syncStartHeight == null) syncStartHeight = lastInfo.getHeight();
                        double percent = Math.min(1.0, targetHeight == syncStartHeight ? 1.0 : ((double) Math.max(1, lastInfo.getHeight() - syncStartHeight) / (double) (targetHeight - syncStartHeight))); // grant at least 1 block to show progress
                        downloadListener.progress(percent, blocksLeft, null);
                    }

                    // set peer connections
                    // TODO: peers often uknown due to restricted RPC call, skipping call to get peer connections
                    // try {
                    //     peers.set(getOnlinePeers());
                    // } catch (Exception err) {
                    //     // TODO: peers unknown due to restricted RPC call
                    // }
                    // numPeers.set(peers.get().size());
                    numPeers.set(lastInfo.getNumOutgoingConnections() + lastInfo.getNumIncomingConnections());
                    peers.set(new ArrayList<MoneroPeer>());

                    // notify update
                    numUpdates.set(numUpdates.get() + 1);
                });

                // handle error recovery
                if (lastLogPollErrorTimestamp != null) {
                    log.info("Successfully fetched daemon info after previous error");
                    lastLogPollErrorTimestamp = null;
                }

                // clear error message
                getConnectionServiceErrorMsg().set(null);
            } catch (Exception e) {

                // not connected to daemon
                isConnected = false;

                // skip if shut down
                if (isShutDownStarted) return;

                // set error message
                getConnectionServiceErrorMsg().set(e.getMessage());
            } finally {
                pollInProgress = false;
            }
        }
    }

    private List<MoneroPeer> getOnlinePeers() {
        return daemon.getPeers().stream()
                .filter(peer -> peer.isOnline())
                .collect(Collectors.toList());
    }

    private boolean isFixedConnection() {
        return !"".equals(config.xmrNode) || preferences.getMoneroNodesOption() == XmrNodes.MoneroNodesOption.CUSTOM;
    }
}
