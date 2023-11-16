package haveno.core.api;

import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodesSetupPreferences;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.core.xmr.setup.DownloadListener;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroConnectionManagerListener;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import monero.daemon.model.MoneroPeer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public final class CoreMoneroConnectionsService {

    private static final int MIN_BROADCAST_CONNECTIONS = 0; // TODO: 0 for stagenet, 5+ for mainnet
    private static final long REFRESH_PERIOD_LOCAL_MS = 5000; // refresh period when connected to local node
    private static final long REFRESH_PERIOD_HTTP_MS = 20000; // refresh period when connected to remote node over http
    private static final long REFRESH_PERIOD_ONION_MS = 30000; // refresh period when connected to remote node over tor
    private static final long MIN_ERROR_LOG_PERIOD_MS = 300000; // minimum period between logging errors fetching daemon info
    private static Long lastErrorTimestamp;



    private final Object lock = new Object();
    private final Config config;
    private final CoreContext coreContext;
    private final Preferences preferences;
    private final CoreAccountService accountService;
    private final XmrNodes xmrNodes;
    private final LocalMoneroNode nodeService;
    private final MoneroConnectionManager connectionManager;
    private final EncryptedConnectionList connectionList;
    private final ObjectProperty<List<MoneroPeer>> peers = new SimpleObjectProperty<>();
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();
    private Socks5ProxyProvider socks5ProxyProvider;

    private boolean isInitialized;
    private MoneroDaemonRpc daemon;
    @Getter
    private MoneroDaemonInfo lastInfo;
    private TaskLooper daemonPollLooper;
    private boolean isShutDownStarted;
    private List<MoneroConnectionManagerListener> listeners = new ArrayList<>();

    @Inject
    public CoreMoneroConnectionsService(P2PService p2PService,
                                        Config config,
                                        CoreContext coreContext,
                                        Preferences preferences,
                                        WalletsSetup walletsSetup,
                                        CoreAccountService accountService,
                                        XmrNodes xmrNodes,
                                        LocalMoneroNode nodeService,
                                        MoneroConnectionManager connectionManager,
                                        EncryptedConnectionList connectionList,
                                        Socks5ProxyProvider socks5ProxyProvider) {
        this.config = config;
        this.coreContext = coreContext;
        this.preferences = preferences;
        this.accountService = accountService;
        this.xmrNodes = xmrNodes;
        this.nodeService = nodeService;
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
        synchronized (this) {
            // ensures request not in progress
        }
    }

    public void shutDown() {
        log.info("Shutting down started for {}", getClass().getSimpleName());
        synchronized (lock) {
            isInitialized = false;
            if (daemonPollLooper != null) daemonPollLooper.stop();
            connectionManager.stopPolling();
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
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public Boolean isConnected() {
        return connectionManager.isConnected();
    }

    public void addConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            if (coreContext.isApiUser()) connectionList.addConnection(connection);
            connectionManager.addConnection(connection);
        }
    }

    public void removeConnection(String uri) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionList.removeConnection(uri);
            connectionManager.removeConnection(uri);
        }
    }

    public MoneroRpcConnection getConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getConnection();
        }
    }

    public List<MoneroRpcConnection> getConnections() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getConnections();
        }
    }

    public void setConnection(String connectionUri) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setConnection(connectionUri); // listener will update connection list
        }
    }

    public void setConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setConnection(connection); // listener will update connection list
        }
    }

    public MoneroRpcConnection checkConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.checkConnection();
            return getConnection();
        }
    }

    public List<MoneroRpcConnection> checkConnections() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.checkConnections();
            return getConnections();
        }
    }

    public void startCheckingConnection(Long refreshPeriod) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionList.setRefreshPeriod(refreshPeriod);
            updatePolling();
        }
    }

    public void stopCheckingConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.stopPolling();
            connectionList.setRefreshPeriod(-1L);
        }
    }

    public MoneroRpcConnection getBestAvailableConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getBestAvailableConnection();
        }
    }

    public void setAutoSwitch(boolean autoSwitch) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setAutoSwitch(autoSwitch);
            connectionList.setAutoSwitch(autoSwitch);
        }
    }

    public boolean isConnectionLocal() {
        return isConnectionLocal(getConnection());
    }

    public long getRefreshPeriodMs() {
        return connectionList.getRefreshPeriod() > 0 ? connectionList.getRefreshPeriod() : getDefaultRefreshPeriodMs();
    }

    public void verifyConnection() {
        if (daemon == null) throw new RuntimeException("No connection to Monero node");
        if (!isSyncedWithinTolerance()) throw new RuntimeException("Monero node is not synced");
    }

    public boolean isSyncedWithinTolerance() {
        if (daemon == null) return false;
        Long targetHeight = lastInfo.getTargetHeight(); // the last time the node thought it was behind the network and was in active sync mode to catch up
        if (targetHeight == 0) return true; // monero-daemon-rpc sync_info's target_height returns 0 when node is fully synced
        long currentHeight = chainHeight.get();
        if (targetHeight - currentHeight <= 3) { // synced if not more than 3 blocks behind target height
            return true;
        }
        log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), targetHeight);
        return false;
    }

    // ----------------------------- APP METHODS ------------------------------

    public ReadOnlyIntegerProperty numPeersProperty() {
        return numPeers;
    }

    public ReadOnlyObjectProperty<List<MoneroPeer>> peerConnectionsProperty() {
        return peers;
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

    /**
     * Signals that both the daemon and wallet have synced.
     *
     * TODO: separate daemon and wallet download/done listeners
     */
    public void doneDownload() {
        downloadListener.doneDownload();
    }

    // ------------------------------- HELPERS --------------------------------

    private boolean isConnectionLocal(MoneroRpcConnection connection) {
        return connection != null && HavenoUtils.isLocalHost(connection.getUri());
    }

    private long getDefaultRefreshPeriodMs() {
        MoneroRpcConnection connection = getConnection();
        if (connection == null) return REFRESH_PERIOD_LOCAL_MS;
        if (isConnectionLocal(connection)) {
            if (lastInfo != null && (lastInfo.isBusySyncing() || (lastInfo.getHeightWithoutBootstrap() != null && lastInfo.getHeightWithoutBootstrap() > 0 && lastInfo.getHeightWithoutBootstrap() < lastInfo.getHeight()))) return REFRESH_PERIOD_HTTP_MS; // refresh slower if syncing or bootstrapped
            else return REFRESH_PERIOD_LOCAL_MS; // TODO: announce faster refresh after done syncing
        } else if (useProxy(connection)) {
            return REFRESH_PERIOD_ONION_MS;
        } else {
            return REFRESH_PERIOD_HTTP_MS;
        }
    }

    private boolean useProxy(MoneroRpcConnection connection) {
        return connection.isOnion() || (preferences.getUseTorForXmr().isUseTorForXmr() && !HavenoUtils.isLocalHost(connection.getUri()));
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
                    e.printStackTrace();
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
                nodeService.addListener(new LocalMoneroNodeListener() {
                    @Override
                    public void onNodeStarted(MoneroDaemonRpc daemon) {
                        log.info(getClass() + ".onNodeStarted() called");
                        daemon.getRpcConnection().checkConnection(connectionManager.getTimeout());
                        setConnection(daemon.getRpcConnection());
                        checkConnection();
                    }

                    @Override
                    public void onNodeStopped() {
                        log.info(getClass() + ".onNodeStopped() called");
                        checkConnection();
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
                if (connectionList.getCurrentConnectionUri().isPresent()) {
                    connectionManager.setConnection(connectionList.getCurrentConnectionUri().get());
                }

                // set connection proxies
                log.info("TOR proxy URI: " + getProxyUri());
                for (MoneroRpcConnection connection : connectionManager.getConnections()) {
                    if (useProxy(connection)) connection.setProxyUri(getProxyUri());
                }

                // restore auto switch
                if (coreContext.isApiUser()) connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
                else connectionManager.setAutoSwitch(true);

                // start local node if used last and offline
                maybeStartLocalNode();

                // update connection
                if (connectionManager.getConnection() == null || connectionManager.getAutoSwitch()) {
                    setConnection(getBestAvailableConnection());
                } else {
                    checkConnection();
                }
            } else if (!isInitialized) {

                // set connection from startup argument if given
                connectionManager.setAutoSwitch(false);
                MoneroRpcConnection connection = new MoneroRpcConnection(config.xmrNode, config.xmrNodeUsername, config.xmrNodePassword).setPriority(1);
                if (useProxy(connection)) connection.setProxyUri(getProxyUri());
                connectionManager.setConnection(connection);

                // start local node if used last and offline
                maybeStartLocalNode();

                // update connection
                checkConnection();
            }

            // register connection listener
            connectionManager.addListener(this::onConnectionChanged);

            // start polling for best connection after delay
            if ("".equals(config.xmrNode)) {
                UserThread.runAfter(() -> {
                    if (!isShutDownStarted) connectionManager.startPolling(getRefreshPeriodMs() * 2);
                }, getDefaultRefreshPeriodMs() * 2 / 1000);
            }

            // notify final connection
            isInitialized = true;
            onConnectionChanged(connectionManager.getConnection());
        }
    }

    private void maybeStartLocalNode() {

        // skip if seed node
        if (HavenoUtils.havenoSetup == null) return;

        // start local node if offline and used as last connection
        if (connectionManager.getConnection() != null && nodeService.equalsUri(connectionManager.getConnection().getUri()) && !nodeService.isDetected()) {
            try {
                log.info("Starting local node");
                nodeService.startMoneroNode();
            } catch (Exception e) {
                log.warn("Unable to start local monero node: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        log.info("CoreMoneroConnectionsService.onConnectionChanged() uri={}, connected={}", currentConnection == null ? null : currentConnection.getUri(), currentConnection == null ? "false" : currentConnection.isConnected());
        if (isShutDownStarted) return;
        synchronized (lock) {
            if (currentConnection == null) {
                daemon = null;
                connectionList.setCurrentConnectionUri(null);
            } else {
                daemon = new MoneroDaemonRpc(currentConnection);
                connectionList.removeConnection(currentConnection.getUri());
                connectionList.addConnection(currentConnection);
                connectionList.setCurrentConnectionUri(currentConnection.getUri());
            }
        }
        updatePolling();

        // notify listeners
        synchronized (lock) {
            for (MoneroConnectionManagerListener listener : listeners) listener.onConnectionChanged(currentConnection);
        }
    }

    private void updatePolling() {
        new Thread(() -> {
            synchronized (lock) {
                stopPolling();
                if (connectionList.getRefreshPeriod() >= 0) startPolling(); // 0 means default refresh poll
            }
        }).start();
    }

    private void startPolling() {
        synchronized (lock) {
            if (daemonPollLooper != null) daemonPollLooper.stop();
            daemonPollLooper = new TaskLooper(() -> pollDaemonInfo());
            daemonPollLooper.start(getRefreshPeriodMs());
        }
    }

    private void stopPolling() {
        synchronized (lock) {
            if (daemonPollLooper != null) daemonPollLooper.stop();
        }
    }

    private void pollDaemonInfo() {
        if (isShutDownStarted) return;
        try {
            log.debug("Polling daemon info");
            if (daemon == null) throw new RuntimeException("No daemon connection");
            synchronized (this) {
                lastInfo = daemon.getInfo();
            }
            chainHeight.set(lastInfo.getTargetHeight() == 0 ? lastInfo.getHeight() : lastInfo.getTargetHeight());

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
            
            // handle error recovery
            if (lastErrorTimestamp != null) {
                log.info("Successfully fetched daemon info after previous error");
                lastErrorTimestamp = null;
            }

            // update and notify connected state
            if (!Boolean.TRUE.equals(connectionManager.isConnected())) {
                connectionManager.checkConnection();
            }

            // clear error message
            if (Boolean.TRUE.equals(connectionManager.isConnected()) && HavenoUtils.havenoSetup != null) {
                HavenoUtils.havenoSetup.getWalletServiceErrorMsg().set(null);
            }
        } catch (Exception e) {

            // log error message periodically
            if ((lastErrorTimestamp == null || System.currentTimeMillis() - lastErrorTimestamp > MIN_ERROR_LOG_PERIOD_MS)) {
                lastErrorTimestamp = System.currentTimeMillis();
                log.warn("Could not update daemon info: " + e.getMessage());
                if (DevEnv.isDevMode()) e.printStackTrace();
            }

            // check connection which notifies of changes
            synchronized (this) {
                if (connectionManager.getAutoSwitch()) connectionManager.setConnection(connectionManager.getBestAvailableConnection());
                else connectionManager.checkConnection();
            }

            // set error message
            if (!Boolean.TRUE.equals(connectionManager.isConnected()) && HavenoUtils.havenoSetup != null) {
                HavenoUtils.havenoSetup.getWalletServiceErrorMsg().set(e.getMessage());
            }
        }
    }

    private List<MoneroPeer> getOnlinePeers() {
        return daemon.getPeers().stream()
                .filter(peer -> peer.isOnline())
                .collect(Collectors.toList());
    }
}
