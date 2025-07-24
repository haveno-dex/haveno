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
import haveno.core.xmr.wallet.XmrKeyImagePoller;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

@Slf4j
@Singleton
public final class XmrConnectionService {

    private static final int MIN_BROADCAST_CONNECTIONS = 0; // TODO: 0 for stagenet, 5+ for mainnet
    private static final long REFRESH_PERIOD_HTTP_MS = 20000; // refresh period when connected to remote node over http
    private static final long REFRESH_PERIOD_ONION_MS = 30000; // refresh period when connected to remote node over tor
    private static final long KEY_IMAGE_REFRESH_PERIOD_MS_LOCAL = 20000; // 20 seconds
    private static final long KEY_IMAGE_REFRESH_PERIOD_MS_REMOTE = 300000; // 5 minutes

    public enum XmrConnectionFallbackType {
        LOCAL,
        CUSTOM,
        PROVIDED
    }

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
    private final ObjectProperty<List<MoneroRpcConnection>> connections = new SimpleObjectProperty<>();
    private final IntegerProperty numConnections = new SimpleIntegerProperty(-1);
    private final ObjectProperty<MoneroRpcConnection> connectionProperty = new SimpleObjectProperty<>();
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();
    @Getter
    private final ObjectProperty<XmrConnectionFallbackType> connectionServiceFallbackType = new SimpleObjectProperty<>();
    @Getter
    private final StringProperty connectionServiceErrorMsg = new SimpleStringProperty();
    private final LongProperty numUpdates = new SimpleLongProperty(0);
    private Socks5ProxyProvider socks5ProxyProvider;

    private boolean isInitialized;
    private boolean pollInProgress;
    private MoneroDaemonRpc monerod;
    private Boolean isConnected = false;
    @Getter
    private MoneroDaemonInfo lastInfo;
    private Long lastFallbackInvocation;
    private Long lastLogPollErrorTimestamp;
    private long lastLogMonerodNotSyncedTimestamp;
    private Long syncStartHeight;
    private TaskLooper monerodPollLooper;
    private long lastRefreshPeriodMs;
    @Getter
    private boolean isShutDownStarted;
    private List<MoneroConnectionManagerListener> listeners = new ArrayList<>();
    private XmrKeyImagePoller keyImagePoller;

    // connection switching
    private static final int EXCLUDE_CONNECTION_SECONDS = 180;
    private static final int MAX_SWITCH_REQUESTS_PER_MINUTE = 2;
    private static final int SKIP_SWITCH_WITHIN_MS = 10000;
    private int numRequestsLastMinute;
    private long lastSwitchTimestamp;
    private Set<MoneroRpcConnection> excludedConnections = new HashSet<>();
    private static final long FALLBACK_INVOCATION_PERIOD_MS = 1000 * 30 * 1; // offer to fallback up to once every 30s
    private boolean fallbackApplied;
    private boolean usedSyncingLocalNodeBeforeStartup;

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
                ThreadUtils.submitToPool(() -> {
                    try {
                        initialize();
                    } catch (Exception e) {
                        log.warn("Error initializing connection service, error={}\n", e.getMessage(), e);
                    }
                });
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
            if (monerodPollLooper != null) monerodPollLooper.stop();
            monerod = null;
        }
    }

    // ------------------------ CONNECTION MANAGEMENT -------------------------

    public MoneroDaemonRpc getMonerod() {
        accountService.checkAccountOpen();
        return this.monerod;
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

    public MoneroRpcConnection getBestConnection() {
        return getBestConnection(new ArrayList<MoneroRpcConnection>());
    }

    private MoneroRpcConnection getBestConnection(Collection<MoneroRpcConnection> ignoredConnections) {
        accountService.checkAccountOpen();

        // user needs to authorize fallback on startup after using locally synced node
        if (fallbackRequiredBeforeConnectionSwitch()) {
            log.warn("Cannot get best connection on startup because we last synced local node and user has not opted to fallback");
            return null;
        }

        // get best connection
        Set<MoneroRpcConnection> ignoredConnectionsSet = new HashSet<>(ignoredConnections);
        addLocalNodeIfIgnored(ignoredConnectionsSet);
        MoneroRpcConnection bestConnection = connectionManager.getBestAvailableConnection(ignoredConnectionsSet.toArray(new MoneroRpcConnection[0])); // checks connections
        if (bestConnection == null && connectionManager.getConnections().size() == 1 && !ignoredConnectionsSet.contains(connectionManager.getConnections().get(0))) bestConnection = connectionManager.getConnections().get(0);
        return bestConnection;
    }

    private boolean fallbackRequiredBeforeConnectionSwitch() {
        return lastInfo == null && !fallbackApplied && usedSyncingLocalNodeBeforeStartup && (!xmrLocalNode.isDetected() || xmrLocalNode.shouldBeIgnored());
    }

    private void addLocalNodeIfIgnored(Collection<MoneroRpcConnection> ignoredConnections) {
        if (xmrLocalNode.shouldBeIgnored() && connectionManager.hasConnection(xmrLocalNode.getUri())) ignoredConnections.add(connectionManager.getConnectionByUri(xmrLocalNode.getUri()));
    }

    private void switchToBestConnection() {
        if (isFixedConnection() || !connectionManager.getAutoSwitch()) {
            log.info("Skipping switch to best Monero connection because connection is fixed or auto switch is disabled");
            return;
        }
        MoneroRpcConnection bestConnection = getBestConnection();
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
        MoneroRpcConnection bestConnection = getBestConnection(excludedConnections);

        // remove from excluded connections after period
        UserThread.runAfter(() -> {
            if (currentConnection != null) excludedConnections.remove(currentConnection);
        }, EXCLUDE_CONNECTION_SECONDS);

        // return if no connection to switch to
        if (bestConnection == null || !Boolean.TRUE.equals(bestConnection.isConnected())) {
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
        if (monerod == null) throw new RuntimeException("No connection to Monero node");
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

    public XmrKeyImagePoller getKeyImagePoller() {
        synchronized (lock) {
            if (keyImagePoller == null) keyImagePoller = new XmrKeyImagePoller();
            return keyImagePoller;
        }
    }

    private long getKeyImageRefreshPeriodMs() {
        return isConnectionLocalHost() ? KEY_IMAGE_REFRESH_PERIOD_MS_LOCAL : KEY_IMAGE_REFRESH_PERIOD_MS_REMOTE;
    }

    // ----------------------------- APP METHODS ------------------------------

    public ReadOnlyIntegerProperty numConnectionsProperty() {
        return numConnections;
    }

    public ReadOnlyObjectProperty<List<MoneroRpcConnection>> connectionsProperty() {
        return connections;
    }

    public ReadOnlyObjectProperty<MoneroRpcConnection> connectionProperty() {
        return connectionProperty;
    }

    public boolean hasSufficientPeersForBroadcast() {
        if (numConnections.get() < 0) return true; // we don't know how many connections we have, but that's expected with restricted node
        return numConnections.get() >= getMinBroadcastConnections();
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

    public void fallbackToBestConnection() {
        if (isShutDownStarted) return;
        fallbackApplied = true;
        if (isProvidedConnections() || xmrNodes.getProvidedXmrNodes().isEmpty()) {
            log.warn("Falling back to public nodes");
            preferences.setMoneroNodesOptionOrdinal(XmrNodes.MoneroNodesOption.PUBLIC.ordinal());
            initializeConnections();
        } else {
            log.warn("Falling back to provided nodes");
            preferences.setMoneroNodesOptionOrdinal(XmrNodes.MoneroNodesOption.PROVIDED.ordinal());
            initializeConnections();
            if (getConnection() == null) {
                log.warn("No provided nodes available, falling back to public nodes");
                fallbackToBestConnection();
            }
        }
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

        // initialize key image poller
        getKeyImagePoller();
        new Thread(() -> {
            HavenoUtils.waitFor(20000);
            keyImagePoller.poll(); // TODO: keep or remove first poll?s
        }).start();

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
                    public void onNodeStarted(MoneroDaemonRpc monerod) {
                        log.info("Local monero node started, height={}", monerod.getHeight());
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

                            // reset error connecting to local node
                            if (connectionServiceFallbackType.get() == XmrConnectionFallbackType.LOCAL && isConnectionLocalHost()) {
                                connectionServiceFallbackType.set(null);
                            }
                        } else if (getConnection() != null && getConnection().getUri().equals(connection.getUri())) {
                            MoneroRpcConnection bestConnection = getBestConnection();
                            if (bestConnection != null) setConnection(bestConnection); // switch to best connection
                        }
                    }
                });
            }

            // restore connections
            if (!isFixedConnection()) {

                // load previous or default connections
                if (coreContext.isApiUser()) {

                    // load previous connections
                    for (MoneroRpcConnection connection : connectionList.getConnections()) connectionManager.addConnection(connection);
                    log.info("Read " + connectionList.getConnections().size() + " previous connections from disk");

                    // add default connections
                    for (XmrNode node : xmrNodes.getAllXmrNodes()) {
                        if (node.hasClearNetAddress()) {
                            if (!xmrLocalNode.shouldBeIgnored() || !xmrLocalNode.equalsUri(node.getClearNetUri())) {
                                MoneroRpcConnection connection = new MoneroRpcConnection(node.getHostNameOrAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                                if (!connectionList.hasConnection(connection.getUri())) addConnection(connection);
                            }
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
                            if (!xmrLocalNode.shouldBeIgnored() || !xmrLocalNode.equalsUri(node.getClearNetUri())) {
                                MoneroRpcConnection connection = new MoneroRpcConnection(node.getHostNameOrAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                                addConnection(connection);
                            }
                        }
                        if (node.hasOnionAddress()) {
                            MoneroRpcConnection connection = new MoneroRpcConnection(node.getOnionAddress() + ":" + node.getPort()).setPriority(node.getPriority());
                            addConnection(connection);
                        }
                    }
                }

                // restore last connection
                if (connectionList.getCurrentConnectionUri().isPresent() && connectionManager.hasConnection(connectionList.getCurrentConnectionUri().get())) {
                    if (!xmrLocalNode.shouldBeIgnored() || !xmrLocalNode.equalsUri(connectionList.getCurrentConnectionUri().get())) {
                        connectionManager.setConnection(connectionList.getCurrentConnectionUri().get());
                    }
                }

                // set if last node was locally syncing
                if (!isInitialized) {
                    usedSyncingLocalNodeBeforeStartup = connectionList.getCurrentConnectionUri().isPresent() && xmrLocalNode.equalsUri(connectionList.getCurrentConnectionUri().get()) && preferences.getXmrNodeSettings().getSyncBlockchain();
                }

                // set connection proxies
                log.info("TOR proxy URI: " + getProxyUri());
                for (MoneroRpcConnection connection : connectionManager.getConnections()) {
                    if (isProxyApplied(connection)) connection.setProxyUri(getProxyUri());
                }

                // restore auto switch
                if (coreContext.isApiUser()) connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
                else connectionManager.setAutoSwitch(true); // auto switch is always enabled on desktop ui

                // update connection
                if (connectionManager.getConnection() == null || connectionManager.getAutoSwitch()) {
                    MoneroRpcConnection bestConnection = getBestConnection();
                    if (bestConnection != null) setConnection(bestConnection);
                }
            } else if (!isInitialized) {

                // set connection from startup argument if given
                connectionManager.setAutoSwitch(false);
                MoneroRpcConnection connection = new MoneroRpcConnection(config.xmrNode, config.xmrNodeUsername, config.xmrNodePassword).setPriority(1);
                if (isProxyApplied(connection)) connection.setProxyUri(getProxyUri());
                connectionManager.setConnection(connection);
            }

            // register connection listener
            connectionManager.addListener(this::onConnectionChanged);
            isInitialized = true;
        }

        // notify initial connection
        lastRefreshPeriodMs = getRefreshPeriodMs();
        onConnectionChanged(connectionManager.getConnection());
    }

    public void startLocalNode() throws Exception {
        
        // cannot start local node as seed node
        if (HavenoUtils.isSeedNode()) {
            throw new RuntimeException("Cannot start local node on seed node");
        }

        // start local node
        log.info("Starting local node");
        xmrLocalNode.start();
    }

    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        if (isShutDownStarted || !accountService.isAccountOpen()) return;
        if (currentConnection == null) {
            log.warn("Setting monerod connection to null", new Throwable("Stack trace"));
        }
        synchronized (lock) {
            if (currentConnection == null) {
                monerod = null;
                isConnected = false;
                connectionList.setCurrentConnectionUri(null);
            } else {
                monerod = new MoneroDaemonRpc(currentConnection);
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

        // update key image poller
        keyImagePoller.setMonerod(getMonerod());
        keyImagePoller.setRefreshPeriodMs(getKeyImageRefreshPeriodMs());
        
        // update polling
        doPollMonerod();
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
            if (monerodPollLooper != null) monerodPollLooper.stop();
            monerodPollLooper = new TaskLooper(() -> pollMonerod());
            monerodPollLooper.start(getInternalRefreshPeriodMs());
        }
    }

    private void stopPolling() {
        synchronized (lock) {
            if (monerodPollLooper != null) {
                monerodPollLooper.stop();
                monerodPollLooper = null;
            }
        }
    }

    private void pollMonerod() {
        if (pollInProgress) return;
        doPollMonerod();
    }

    private void doPollMonerod() {
        synchronized (pollLock) {
            pollInProgress = true;
            if (isShutDownStarted) return;
            try {

                // poll monerod
                if (monerod == null && !fallbackRequiredBeforeConnectionSwitch()) switchToBestConnection();
                try {
                    if (monerod == null) throw new RuntimeException("No connection to Monero daemon");
                    lastInfo = monerod.getInfo();
                } catch (Exception e) {

                    // skip handling if shutting down
                    if (isShutDownStarted) return;

                    // invoke fallback handling on startup error
                    boolean canFallback = isFixedConnection() || isProvidedConnections() || isCustomConnections() || usedSyncingLocalNodeBeforeStartup;
                    if (lastInfo == null && canFallback) {
                        if (connectionServiceFallbackType.get() == null && (lastFallbackInvocation == null || System.currentTimeMillis() - lastFallbackInvocation > FALLBACK_INVOCATION_PERIOD_MS)) {
                            lastFallbackInvocation = System.currentTimeMillis();
                            if (usedSyncingLocalNodeBeforeStartup) {
                                log.warn("Failed to fetch monerod info from local connection on startup: " + e.getMessage());
                                connectionServiceFallbackType.set(XmrConnectionFallbackType.LOCAL);
                            } else if (isProvidedConnections()) {
                                log.warn("Failed to fetch monerod info from provided connections on startup: " + e.getMessage());
                                connectionServiceFallbackType.set(XmrConnectionFallbackType.PROVIDED);
                            } else {
                                log.warn("Failed to fetch monerod info from custom connection on startup: " + e.getMessage());
                                connectionServiceFallbackType.set(XmrConnectionFallbackType.CUSTOM);
                            }
                        }
                        return;
                    }

                    // log error message periodically
                    if (lastLogPollErrorTimestamp == null || System.currentTimeMillis() - lastLogPollErrorTimestamp > HavenoUtils.LOG_POLL_ERROR_PERIOD_MS) {
                        log.warn("Failed to fetch monerod info, trying to switch to best connection, error={}", e.getMessage());
                        if (DevEnv.isDevMode()) log.error(ExceptionUtils.getStackTrace(e));
                        lastLogPollErrorTimestamp = System.currentTimeMillis();
                    }

                    // switch to best connection
                    switchToBestConnection();
                    if (monerod == null) throw new RuntimeException("No connection to Monero daemon after error handling");
                    lastInfo = monerod.getInfo(); // caught internally if still fails
                }

                // connected to monerod
                isConnected = true;
                connectionServiceFallbackType.set(null);

                // determine if blockchain is syncing locally
                boolean blockchainSyncing = lastInfo.getHeight().equals(lastInfo.getHeightWithoutBootstrap()) || (lastInfo.getTargetHeight().equals(0l) && lastInfo.getHeightWithoutBootstrap().equals(0l)); // blockchain is syncing if height equals height without bootstrap, or target height and height without bootstrap both equal 0

                // write sync status to preferences
                preferences.getXmrNodeSettings().setSyncBlockchain(blockchainSyncing);

                // throttle warnings if monerod not synced
                if (!isSyncedWithinTolerance() && System.currentTimeMillis() - lastLogMonerodNotSyncedTimestamp > HavenoUtils.LOG_MONEROD_NOT_SYNCED_WARN_PERIOD_MS) {
                    log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), getTargetHeight());
                    lastLogMonerodNotSyncedTimestamp = System.currentTimeMillis();
                }

                // announce connection change if refresh period changes
                if (getRefreshPeriodMs() != lastRefreshPeriodMs) {
                    lastRefreshPeriodMs = getRefreshPeriodMs();
                    onConnectionChanged(getConnection()); // causes new poll
                    return;
                }

                // get the number of connections, which is only available if not restricted
                int numOutgoingConnections = Boolean.TRUE.equals(lastInfo.isRestricted()) ? -1 : lastInfo.getNumOutgoingConnections();

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

                    // set available connections
                    List<MoneroRpcConnection> availableConnections = new ArrayList<>();
                    for (MoneroRpcConnection connection : connectionManager.getConnections()) {
                        if (Boolean.TRUE.equals(connection.isOnline()) && Boolean.TRUE.equals(connection.isAuthenticated())) {
                            availableConnections.add(connection);
                        }
                    }
                    connections.set(availableConnections);
                    numConnections.set(numOutgoingConnections);

                    // notify update
                    numUpdates.set(numUpdates.get() + 1);
                });

                // invoke error handling if no connections
                if (numOutgoingConnections == 0) {
                    String errorMsg = "The Monero node has no connected peers. It may be experiencing a network connectivity issue.";
                    log.warn(errorMsg);
                    throw new RuntimeException(errorMsg);
                }

                // handle error recovery
                if (lastLogPollErrorTimestamp != null) {
                    log.info("Successfully fetched monerod info after previous error");
                    lastLogPollErrorTimestamp = null;
                }

                // clear error message
                getConnectionServiceErrorMsg().set(null);
            } catch (Exception e) {

                // not connected to monerod
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

    private boolean isFixedConnection() {
        return !"".equals(config.xmrNode) && !(HavenoUtils.isLocalHost(config.xmrNode) && xmrLocalNode.shouldBeIgnored()) && !fallbackApplied;
    }

    private boolean isCustomConnections() {
        return preferences.getMoneroNodesOption() == XmrNodes.MoneroNodesOption.CUSTOM;
    }

    private boolean isProvidedConnections() {
        return preferences.getMoneroNodesOption() == XmrNodes.MoneroNodesOption.PROVIDED;
    }
}
