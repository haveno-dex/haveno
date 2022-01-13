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

package bisq.core.btc.setup;

import bisq.core.btc.exceptions.InvalidHostException;
import bisq.core.btc.exceptions.RejectedTxException;
import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.model.AddressEntryList;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.nodes.BtcNetworkConfig;
import bisq.core.btc.nodes.BtcNodes;
import bisq.core.btc.nodes.BtcNodes.BtcNode;
import bisq.core.btc.nodes.BtcNodesRepository;
import bisq.core.btc.nodes.BtcNodesSetupPreferences;
import bisq.core.btc.nodes.LocalBitcoinNode;
import bisq.core.user.Preferences;

import bisq.network.Socks5MultiDiscovery;
import bisq.network.Socks5ProxyProvider;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.common.handlers.ExceptionHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.RejectMessage;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Service;

import org.apache.commons.lang3.StringUtils;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.nio.file.Paths;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;


import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroPeer;
import monero.wallet.MoneroWallet;

// Setup wallets and use WalletConfig for BitcoinJ wiring.
// Other like WalletConfig we are here always on the user thread. That is one reason why we do not
// merge WalletsSetup with WalletConfig to one class.
@Slf4j
public class WalletsSetup {

    public static final String PRE_SEGWIT_WALLET_BACKUP = "pre_segwit_haveno_BTC.wallet.backup";
    private static final int MIN_BROADCAST_CONNECTIONS = 2;
    private static final long DAEMON_POLL_INTERVAL_SECONDS = 20;

    @Getter
    public final BooleanProperty walletsSetupFailed = new SimpleBooleanProperty();

    private static final long STARTUP_TIMEOUT = 180;
    private static final String SPV_CHAIN_FILE_NAME = "haveno.spvchain";

    private final RegTestHost regTestHost;
    private final AddressEntryList addressEntryList;
    private final XmrAddressEntryList xmrAddressEntryList;
    private final Preferences preferences;
    private final Socks5ProxyProvider socks5ProxyProvider;
    private final Config config;
    private final LocalBitcoinNode localBitcoinNode;
    private final BtcNodes btcNodes;
    private final String xmrWalletFileName;
    private final int numConnectionsForBtc;
    private final String userAgent;
    private final NetworkParameters params;
    private final File walletDir;
    private final int walletRpcBindPort;
    private final int socks5DiscoverMode;
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final ObjectProperty<List<MoneroPeer>> peers = new SimpleObjectProperty<>();
    private final DownloadListener downloadListener = new DownloadListener();
    private final List<Runnable> setupCompletedHandlers = new ArrayList<>();
    public final BooleanProperty shutDownComplete = new SimpleBooleanProperty();
    private final boolean useAllProvidedNodes;
    private WalletConfig walletConfig;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public WalletsSetup(RegTestHost regTestHost,
                        AddressEntryList addressEntryList,
                        XmrAddressEntryList xmrAddressEntryList,
                        Preferences preferences,
                        Socks5ProxyProvider socks5ProxyProvider,
                        Config config,
                        LocalBitcoinNode localBitcoinNode,
                        BtcNodes btcNodes,
                        @Named(Config.USER_AGENT) String userAgent,
                        @Named(Config.WALLET_DIR) File walletDir,
                        @Named(Config.WALLET_RPC_BIND_PORT) int walletRpcBindPort,
                        @Named(Config.USE_ALL_PROVIDED_NODES) boolean useAllProvidedNodes,
                        @Named(Config.NUM_CONNECTIONS_FOR_BTC) int numConnectionsForBtc,
                        @Named(Config.SOCKS5_DISCOVER_MODE) String socks5DiscoverModeString) {
        this.regTestHost = regTestHost;
        this.addressEntryList = addressEntryList;
        this.xmrAddressEntryList = xmrAddressEntryList;
        this.preferences = preferences;
        this.socks5ProxyProvider = socks5ProxyProvider;
        this.config = config;
        this.localBitcoinNode = localBitcoinNode;
        this.btcNodes = btcNodes;
        this.numConnectionsForBtc = numConnectionsForBtc;
        this.useAllProvidedNodes = useAllProvidedNodes;
        this.userAgent = userAgent;
        this.socks5DiscoverMode = evaluateMode(socks5DiscoverModeString);
        this.walletDir = walletDir;
        this.walletRpcBindPort = walletRpcBindPort;

        xmrWalletFileName = "haveno_" + config.baseCurrencyNetwork.getCurrencyCode();
        params = Config.baseCurrencyNetworkParameters();
        PeerGroup.setIgnoreHttpSeeds(true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void initialize(@Nullable DeterministicSeed seed,
                           ResultHandler resultHandler,
                           ExceptionHandler exceptionHandler) {
        // Tell bitcoinj to execute event handlers on the JavaFX UI thread. This keeps things simple and means
        // we cannot forget to switch threads when adding event handlers. Unfortunately, the DownloadListener
        // we give to the app kit is currently an exception and runs on a library thread. It'll get fixed in
        // a future version.

        Threading.USER_THREAD = UserThread.getExecutor();

        Timer timeoutTimer = UserThread.runAfter(() ->
                exceptionHandler.handleException(new TimeoutException("Wallet did not initialize in " +
                        STARTUP_TIMEOUT + " seconds.")), STARTUP_TIMEOUT);

        backupWallets();

        final Socks5Proxy socks5Proxy = preferences.getUseTorForBitcoinJ() ? socks5ProxyProvider.getSocks5Proxy() : null;
        log.info("Socks5Proxy for bitcoinj: socks5Proxy=" + socks5Proxy);

        walletConfig = new WalletConfig(params, walletDir, walletRpcBindPort, "haveno") {
            @Override
            protected void onSetupCompleted() {
                //We are here in the btcj thread Thread[ STARTING,5,main]
                super.onSetupCompleted();

                final PeerGroup peerGroup = walletConfig.peerGroup();
                final BlockChain chain = walletConfig.chain();

                // We don't want to get our node white list polluted with nodes from AddressMessage calls.
                if (preferences.getBitcoinNodes() != null && !preferences.getBitcoinNodes().isEmpty())
                    peerGroup.setAddPeersFromAddressMessage(false);

                UserThread.runPeriodically(() -> {
                    peers.set(getPeerConnections());
                    numPeers.set(peers.get().size());
                    chainHeight.set(vXmrDaemon.getHeight());
                }, DAEMON_POLL_INTERVAL_SECONDS);

                // Need to be Threading.SAME_THREAD executor otherwise BitcoinJ will skip that listener
                peerGroup.addPreMessageReceivedEventListener(Threading.SAME_THREAD, (peer, message) -> {
                    if (message instanceof RejectMessage) {
                        UserThread.execute(() -> {
                            RejectMessage rejectMessage = (RejectMessage) message;
                            String msg = rejectMessage.toString();
                            log.warn(msg);
                            exceptionHandler.handleException(new RejectedTxException(msg, rejectMessage));
                        });
                    }
                    return message;
                });

                // Map to user thread
                UserThread.execute(() -> {
                    peers.set(getPeerConnections());
                    numPeers.set(peers.get().size());
                    chainHeight.set(vXmrDaemon.getHeight());
                    addressEntryList.onWalletReady(walletConfig.btcWallet());
                    xmrAddressEntryList.onWalletReady(walletConfig.getXmrWallet());
                    timeoutTimer.stop();
                    setupCompletedHandlers.forEach(Runnable::run);
                });

                // onSetupCompleted in walletAppKit is not the called on the last invocations, so we add a bit of delay
                UserThread.runAfter(resultHandler::handleResult, 100, TimeUnit.MILLISECONDS);
            }

            private List<MoneroPeer> getPeerConnections() {
                return vXmrDaemon.getPeers().stream()
                        .filter(peer -> peer.isOnline())
                        .collect(Collectors.toList());
            }
        };
        walletConfig.setSocks5Proxy(socks5Proxy);
        walletConfig.setConfig(config);
        walletConfig.setLocalBitcoinNode(localBitcoinNode);
        walletConfig.setUserAgent(userAgent, Version.VERSION);
        walletConfig.setNumConnectionsForBtc(numConnectionsForBtc);

        String checkpointsPath = null;
        if (params.getId().equals(NetworkParameters.ID_MAINNET)) {
            // Checkpoints are block headers that ship inside our app: for a new user, we pick the last header
            // in the checkpoints file and then download the rest from the network. It makes things much faster.
            // Checkpoint files are made using the BuildCheckpoints tool and usually we have to download the
            // last months worth or more (takes a few seconds).
            checkpointsPath = "/wallet/checkpoints.txt";
        } else if (params.getId().equals(NetworkParameters.ID_TESTNET)) {
            checkpointsPath = "/wallet/checkpoints.testnet.txt";
        }
        if (checkpointsPath != null) {
            walletConfig.setCheckpoints(getClass().getResourceAsStream(checkpointsPath));
        }


        if (params.getId().equals(NetworkParameters.ID_REGTEST)) {
            walletConfig.setMinBroadcastConnections(1);
            if (regTestHost == RegTestHost.LOCALHOST) {
                walletConfig.connectToLocalHost();
            } else if (regTestHost == RegTestHost.REMOTE_HOST) {
                configPeerNodesForRegTestServer();
            } else {
                try {
                    configPeerNodes(socks5Proxy);
                } catch (IllegalArgumentException e) {
                    timeoutTimer.stop();
                    walletsSetupFailed.set(true);
                    exceptionHandler.handleException(new InvalidHostException(e.getMessage()));
                    return;
                }
            }
        } else if (localBitcoinNode.shouldBeUsed()) {
            walletConfig.setMinBroadcastConnections(1);
            walletConfig.connectToLocalHost();
        } else {
            try {
                configPeerNodes(socks5Proxy);
            } catch (IllegalArgumentException e) {
                timeoutTimer.stop();
                walletsSetupFailed.set(true);
                exceptionHandler.handleException(new InvalidHostException(e.getMessage()));
                return;
            }
        }

        walletConfig.setDownloadListener(downloadListener);

        // If seed is non-null it means we are restoring from backup.
        if (seed != null) {
            walletConfig.restoreWalletFromSeed(seed);
        }

        walletConfig.addListener(new Service.Listener() {
            @Override
            public void failed(@NotNull Service.State from, @NotNull Throwable failure) {
                walletConfig = null;
                log.error("Service failure from state: {}; failure={}", from, failure);
                timeoutTimer.stop();
                UserThread.execute(() -> exceptionHandler.handleException(failure));
            }
        }, Threading.USER_THREAD);

        walletConfig.startAsync();
    }

    public void shutDown() {
        if (walletConfig != null) {
            try {
                log.info("walletConfig shutDown started");
                walletConfig.stopAsync();
                walletConfig.awaitTerminated(1, TimeUnit.SECONDS);
                log.info("walletConfig shutDown completed");
            } catch (Throwable ignore) {
                log.info("walletConfig shutDown interrupted by timeout");
            }
        }

        shutDownComplete.set(true);
    }

    public void reSyncSPVChain() throws IOException {
        FileUtil.deleteFileIfExists(new File(walletDir, SPV_CHAIN_FILE_NAME));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Initialize methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @VisibleForTesting
    private int evaluateMode(String socks5DiscoverModeString) {
        String[] socks5DiscoverModes = StringUtils.deleteWhitespace(socks5DiscoverModeString).split(",");
        int mode = 0;
        for (String socks5DiscoverMode : socks5DiscoverModes) {
            switch (socks5DiscoverMode) {
                case "ADDR":
                    mode |= Socks5MultiDiscovery.SOCKS5_DISCOVER_ADDR;
                    break;
                case "DNS":
                    mode |= Socks5MultiDiscovery.SOCKS5_DISCOVER_DNS;
                    break;
                case "ONION":
                    mode |= Socks5MultiDiscovery.SOCKS5_DISCOVER_ONION;
                    break;
                case "ALL":
                default:
                    mode |= Socks5MultiDiscovery.SOCKS5_DISCOVER_ALL;
                    break;
            }
        }
        return mode;
    }

    private void configPeerNodesForRegTestServer() {
        try {
            if (RegTestHost.HOST.endsWith(".onion")) {
                walletConfig.setPeerNodes(new PeerAddress(RegTestHost.HOST, params.getPort()));
            } else {
                walletConfig.setPeerNodes(new PeerAddress(params, InetAddress.getByName(RegTestHost.HOST), params.getPort()));
            }
        } catch (UnknownHostException e) {
            log.error(e.toString());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void configPeerNodes(@Nullable Socks5Proxy proxy) {
        walletConfig.setMinBroadcastConnections(MIN_BROADCAST_CONNECTIONS);

        BtcNodesSetupPreferences btcNodesSetupPreferences = new BtcNodesSetupPreferences(preferences);
        List<BtcNode> nodes = btcNodesSetupPreferences.selectPreferredNodes(btcNodes);

        BtcNodesRepository repository = new BtcNodesRepository(nodes);
        boolean isUseClearNodesWithProxies = (useAllProvidedNodes || btcNodesSetupPreferences.isUseCustomNodes());
        List<PeerAddress> peers = repository.getPeerAddresses(proxy, isUseClearNodesWithProxies);

        BtcNetworkConfig networkConfig = new BtcNetworkConfig(walletConfig, params, socks5DiscoverMode, proxy);
        networkConfig.proposePeers(peers);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Backup
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void backupWallets() {
        FileUtil.rollingBackup(walletDir, xmrWalletFileName, 20);
        FileUtil.rollingBackup(walletDir, xmrWalletFileName + ".keys", 20);
        FileUtil.rollingBackup(walletDir, xmrWalletFileName + ".address.txt", 20);
    }

    public void clearBackups() {
        try {
            FileUtil.deleteDirectory(new File(Paths.get(walletDir.getAbsolutePath(), "backup").toString()));
        } catch (IOException e) {
            log.error("Could not delete directory " + e.getMessage());
            e.printStackTrace();
        }

        File segwitBackup = new File(walletDir, PRE_SEGWIT_WALLET_BACKUP);
        try {
            FileUtil.deleteFileIfExists(segwitBackup);
        } catch (IOException e) {
            log.error(e.toString(), e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Restore
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void restoreSeedWords(@Nullable DeterministicSeed seed,
                                 ResultHandler resultHandler,
                                 ExceptionHandler exceptionHandler) {
        checkNotNull(seed, "Seed must be not be null.");

        backupWallets();

        Context ctx = Context.get();
        new Thread(() -> {
            try {
                Context.propagate(ctx);
                walletConfig.stopAsync();
                walletConfig.awaitTerminated();
                initialize(seed, resultHandler, exceptionHandler);
            } catch (Throwable t) {
                t.printStackTrace();
                log.error("Executing task failed. " + t.getMessage());
            }
        }, "RestoreBTCWallet-%d").start();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addSetupCompletedHandler(Runnable handler) {
        setupCompletedHandlers.add(handler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Wallet getBtcWallet() {
        return walletConfig.btcWallet();
    }

    public MoneroDaemon getXmrDaemon() {
        return walletConfig.getXmrDaemon();
    }

    public MoneroWallet getXmrWallet() {
      return walletConfig.getXmrWallet();
    }

    public NetworkParameters getParams() {
        return params;
    }

    @Nullable
    public BlockChain getChain() {
        return walletConfig != null && walletConfig.stateStartingOrRunning() ? walletConfig.chain() : null;
    }

    public PeerGroup getPeerGroup() {
        return walletConfig.peerGroup();
    }

    public WalletConfig getWalletConfig() {
        return walletConfig;
    }

    public ReadOnlyIntegerProperty numPeersProperty() {
        return numPeers;
    }

    public ReadOnlyObjectProperty<List<MoneroPeer>> peerConnectionsProperty() {
        return peers;
    }

    public LongProperty chainHeightProperty() {
        return chainHeight;
    }

    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return downloadListener.percentageProperty();
    }

    public boolean isDownloadComplete() {
        return downloadPercentageProperty().get() == 1d;
    }

    public boolean isChainHeightSyncedWithinTolerance() {
        Long peersChainHeight = walletConfig.vXmrDaemon.getSyncInfo().getTargetHeight();
        if (peersChainHeight == 0) return true; // monero-daemon-rpc sync_info's target_height returns 0 when node is fully synced
        long bestChainHeight = chainHeight.get();
        if (Math.abs(peersChainHeight - bestChainHeight) <= 3) {
            return true;
        }
        log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), peersChainHeight);
        return false;
    }

    public Set<Address> getAddressesByContext(@SuppressWarnings("SameParameterValue") AddressEntry.Context context) {
        return addressEntryList.getAddressEntriesAsListImmutable().stream()
                .filter(addressEntry -> addressEntry.getContext() == context)
                .map(AddressEntry::getAddress)
                .collect(Collectors.toSet());
    }

    public Set<Address> getAddressesFromAddressEntries(Set<AddressEntry> addressEntries) {
        return addressEntries.stream()
                .map(AddressEntry::getAddress)
                .collect(Collectors.toSet());
    }

    public boolean hasSufficientPeersForBroadcast() {
        return numPeers.get() >= getMinBroadcastConnections();
    }

    public int getMinBroadcastConnections() {
        return walletConfig.getMinBroadcastConnections();
    }

}
