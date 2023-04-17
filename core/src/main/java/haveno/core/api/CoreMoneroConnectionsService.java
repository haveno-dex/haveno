package haveno.core.api;

import haveno.common.app.DevEnv;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.model.EncryptedConnectionList;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // default Monero nodes
    private static final Map<BaseCurrencyNetwork, List<MoneroRpcConnection>> DEFAULT_CONNECTIONS;
    static {
        DEFAULT_CONNECTIONS = new HashMap<BaseCurrencyNetwork, List<MoneroRpcConnection>>();
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_LOCAL, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:28081").setPriority(1)
        ));
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_STAGENET, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:38081").setPriority(1), // localhost is first priority, use loopback address 127.0.0.1 to match url used by local node service
                new MoneroRpcConnection("http://127.0.0.1:39081").setPriority(2), // from makefile: `monerod-stagenet-custom`
                new MoneroRpcConnection("http://45.63.8.26:38081").setPriority(2), // hosted by haveno
                new MoneroRpcConnection("http://stagenet.community.rino.io:38081").setPriority(2),
                new MoneroRpcConnection("http://stagenet.melo.tools:38081").setPriority(2),
                new MoneroRpcConnection("http://node.sethforprivacy.com:38089").setPriority(2),
                new MoneroRpcConnection("http://node2.sethforprivacy.com:38089").setPriority(2),
                new MoneroRpcConnection("http://plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion:38089").setPriority(2)
        ));
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_MAINNET, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:18081").setPriority(1),
                new MoneroRpcConnection("http://node.community.rino.io:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-eu.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-usa-east.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-uk.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://node.sethforprivacy.com:18089").setPriority(2)
        ));
    }

    private final Object lock = new Object();
    private final Config config;
    private final CoreContext coreContext;
    private final CoreAccountService accountService;
    private final CoreMoneroNodeService nodeService;
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
                                        WalletsSetup walletsSetup,
                                        CoreAccountService accountService,
                                        CoreMoneroNodeService nodeService,
                                        MoneroConnectionManager connectionManager,
                                        EncryptedConnectionList connectionList,
                                        Socks5ProxyProvider socks5ProxyProvider) {
        this.config = config;
        this.coreContext = coreContext;
        this.accountService = accountService;
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
            connectionManager.stopCheckingConnection();
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

    public void addListener(MoneroConnectionManagerListener listener) {
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
            connectionList.addConnection(connection);
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
            connectionManager.stopCheckingConnection();
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
        return getConnection() != null && HavenoUtils.isLocalHost(getConnection().getUri());
    }

    public long getRefreshPeriodMs() {
        if (connectionList.getRefreshPeriod() < 0 || connectionList.getRefreshPeriod() > 0) {
            return connectionList.getRefreshPeriod();
        } else {
            return getDefaultRefreshPeriodMs();
        }
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

    private long getDefaultRefreshPeriodMs() {
        if (daemon == null) return REFRESH_PERIOD_LOCAL_MS;
        else {
            if (isConnectionLocal()) {
                if (lastInfo != null && (lastInfo.isBusySyncing() || (lastInfo.getHeightWithoutBootstrap() != null && lastInfo.getHeightWithoutBootstrap() > 0 && lastInfo.getHeightWithoutBootstrap() < lastInfo.getHeight()))) return REFRESH_PERIOD_HTTP_MS; // refresh slower if syncing or bootstrapped
                else return REFRESH_PERIOD_LOCAL_MS; // TODO: announce faster refresh after done syncing
            } else if (getConnection().isOnion()) {
                return REFRESH_PERIOD_ONION_MS;
            } else {
                return REFRESH_PERIOD_HTTP_MS;
            }
        }
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
                log.info(getClass() + ".onPasswordChanged({}, {}) called", oldPassword, newPassword);
                connectionList.changePassword(oldPassword, newPassword);
            }
        });
    }

    private void initializeConnections() {
        synchronized (lock) {

            // reset connection manager
            connectionManager.reset();
            connectionManager.setTimeout(REFRESH_PERIOD_HTTP_MS);

            // load connections
            log.info("TOR proxy URI: " + getProxyUri());
            for (MoneroRpcConnection connection : connectionList.getConnections()) {
                if (connection.isOnion()) connection.setProxyUri(getProxyUri());
                connectionManager.addConnection(connection);
            }
            log.info("Read " + connectionList.getConnections().size() + " connections from disk");

            // add default connections
            for (MoneroRpcConnection connection : DEFAULT_CONNECTIONS.get(Config.baseCurrencyNetwork())) {
                if (connectionList.hasConnection(connection.getUri())) continue;
                if (connection.isOnion()) connection.setProxyUri(getProxyUri());
                addConnection(connection);
            }

            // restore last used connection if unconfigured and present
            Optional<String> currentConnectionUri = null;
            if ("".equals(config.xmrNode)) {
                currentConnectionUri = connectionList.getCurrentConnectionUri();
                if (currentConnectionUri.isPresent()) connectionManager.setConnection(currentConnectionUri.get());
            } else if (!isInitialized) {

                // set monero connection from startup arguments
                MoneroRpcConnection connection = new MoneroRpcConnection(config.xmrNode, config.xmrNodeUsername, config.xmrNodePassword).setPriority(1);
                if (connection.isOnion()) connection.setProxyUri(getProxyUri());
                connectionManager.setConnection(connection);
                currentConnectionUri = Optional.of(connection.getUri());
            }

            // restore configuration
            if ("".equals(config.xmrNode)) connectionManager.setAutoSwitch(connectionList.getAutoSwitch());

            // check connection
            checkConnection();

            // run once
            if (!isInitialized) {

                // register local node listener
                nodeService.addListener(new MoneroNodeServiceListener() {
                    @Override
                    public void onNodeStarted(MoneroDaemonRpc daemon) {
                        log.info(getClass() + ".onNodeStarted() called");
                        daemon.getRpcConnection().checkConnection(connectionManager.getTimeout());
                        setConnection(daemon.getRpcConnection());
                    }

                    @Override
                    public void onNodeStopped() {
                        log.info(getClass() + ".onNodeStopped() called");
                        checkConnection();
                    }
                });
            }

            // if offline and last connection is local node, start local node if it's offline
            currentConnectionUri.ifPresent(uri -> {
                try {
                    if (!connectionManager.isConnected() && nodeService.equalsUri(uri) && !nodeService.isOnline()) {
                        log.info("Starting local node");
                        nodeService.startMoneroNode();
                    }
                } catch (Exception e) {
                    log.warn("Unable to start local monero node: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            // prefer to connect to local node unless prevented by configuration
            if ("".equals(config.xmrNode) &&
                    (!connectionManager.isConnected() || connectionManager.getAutoSwitch()) &&
                    nodeService.isConnected()) {
                MoneroRpcConnection connection = connectionManager.getConnectionByUri(nodeService.getDaemon().getRpcConnection().getUri());
                if (connection != null) {
                    connection.checkConnection(connectionManager.getTimeout());
                    setConnection(connection);
                }
            }

            // if using legacy desktop app, connect to best available connection
            if (!coreContext.isApiUser() && "".equals(config.xmrNode)) {
                connectionManager.setAutoSwitch(true);
                MoneroRpcConnection bestConnection = connectionManager.getBestAvailableConnection();
                log.info("Setting best available connection for monerod: " + (bestConnection == null ? null : bestConnection.getUri()));
                connectionManager.setConnection(bestConnection);
            }

            // register connection change listener
            connectionManager.addListener(this::onConnectionChanged);
            isInitialized = true;

            // update connection state
            onConnectionChanged(connectionManager.getConnection());
        }
    }

    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        log.info("CoreMoneroConnetionsService.onConnectionChanged() uri={}, connected=", currentConnection == null ? null : currentConnection.getUri(), currentConnection == null ? "false" : currentConnection.isConnected());
        if (isShutDownStarted) return;
        synchronized (lock) {
            if (currentConnection == null) {
                daemon = null;
                connectionList.setCurrentConnectionUri(null);
            } else {
                daemon = new MoneroDaemonRpc(connectionManager.getConnection());
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
                if (getRefreshPeriodMs() > 0) startPolling();
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
            
            // log recovery message
            if (lastErrorTimestamp != null) {
                log.info("Successfully fetched daemon info after previous error");
                lastErrorTimestamp = null;
            }

            // update and notify connected state
            if (!Boolean.TRUE.equals(connectionManager.isConnected())) {
                connectionManager.checkConnection();
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
        }
    }

    private List<MoneroPeer> getOnlinePeers() {
        return daemon.getPeers().stream()
                .filter(peer -> peer.isOnline())
                .collect(Collectors.toList());
    }
}
