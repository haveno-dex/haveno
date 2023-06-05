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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.handlers.ExceptionHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.user.Preferences;
import haveno.core.xmr.exceptions.InvalidHostException;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.model.AddressEntryList;
import haveno.core.xmr.nodes.XmrNetworkConfig;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.core.xmr.nodes.XmrNodesRepository;
import haveno.core.xmr.nodes.XmrNodesSetupPreferences;
import haveno.core.xmr.nodes.LocalBitcoinNode;
import haveno.network.Socks5MultiDiscovery;
import haveno.network.Socks5ProxyProvider;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;



// Setup wallets and use WalletConfig for BitcoinJ wiring.
// Other like WalletConfig we are here always on the user thread. That is one reason why we do not
// merge WalletsSetup with WalletConfig to one class.
@Slf4j
public class WalletsSetup {

    public static final String PRE_SEGWIT_WALLET_BACKUP = "pre_segwit_haveno_BTC.wallet.backup";
    private static final int MIN_BROADCAST_CONNECTIONS = 0;

    @Getter
    public final BooleanProperty walletsSetupFailed = new SimpleBooleanProperty();

    private static final long STARTUP_TIMEOUT_SECONDS = 3600; // 1 hour
    private static final String SPV_CHAIN_FILE_NAME = "haveno.spvchain";

    private final RegTestHost regTestHost;
    private final AddressEntryList addressEntryList;
    private final Preferences preferences;
    private final Socks5ProxyProvider socks5ProxyProvider;
    private final Config config;
    private final LocalBitcoinNode localBitcoinNode;
    private final XmrNodes xmrNodes;
    private final int numConnectionsForBtc;
    private final String userAgent;
    private final NetworkParameters params;
    private final File walletDir;
    private final int socks5DiscoverMode;
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();
    private final List<Runnable> setupTaskHandlers = new ArrayList<>();
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
                        Preferences preferences,
                        Socks5ProxyProvider socks5ProxyProvider,
                        Config config,
                        LocalBitcoinNode localBitcoinNode,
                        XmrNodes xmrNodes,
                        @Named(Config.USER_AGENT) String userAgent,
                        @Named(Config.WALLET_DIR) File walletDir,
                        @Named(Config.USE_ALL_PROVIDED_NODES) boolean useAllProvidedNodes,
                        @Named(Config.NUM_CONNECTIONS_FOR_BTC) int numConnectionsForBtc,
                        @Named(Config.SOCKS5_DISCOVER_MODE) String socks5DiscoverModeString) {
        this.regTestHost = regTestHost;
        this.addressEntryList = addressEntryList;
        this.preferences = preferences;
        this.socks5ProxyProvider = socks5ProxyProvider;
        this.config = config;
        this.localBitcoinNode = localBitcoinNode;
        this.xmrNodes = xmrNodes;
        this.numConnectionsForBtc = numConnectionsForBtc;
        this.useAllProvidedNodes = useAllProvidedNodes;
        this.userAgent = userAgent;
        this.socks5DiscoverMode = evaluateMode(socks5DiscoverModeString);
        this.walletDir = walletDir;

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
                        STARTUP_TIMEOUT_SECONDS + " seconds.")), STARTUP_TIMEOUT_SECONDS);

        backupWallets();

        final Socks5Proxy socks5Proxy = preferences.getUseTorForMonero() ? socks5ProxyProvider.getSocks5Proxy() : null;
        log.info("Socks5Proxy for bitcoinj: socks5Proxy=" + socks5Proxy);

        walletConfig = new WalletConfig(params, walletDir, "haveno") {
            @Override
            protected void onSetupCompleted() {
                //We are here in the btcj thread Thread[ STARTING,5,main]
                super.onSetupCompleted();

                // run external startup handlers
                setupTaskHandlers.forEach(Runnable::run);

                // Map to user thread
                UserThread.execute(() -> {
                    timeoutTimer.stop();
                    setupCompletedHandlers.forEach(Runnable::run);
                });

                // onSetupCompleted in walletAppKit is not the called on the last invocations, so we add a bit of delay
                UserThread.runAfter(resultHandler::handleResult, 100, TimeUnit.MILLISECONDS);
            }
        };
        walletConfig.setSocks5Proxy(socks5Proxy);
        walletConfig.setConfig(config);
        walletConfig.setLocalBitcoinNode(localBitcoinNode); // TODO: adapt to xmr or remove
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

        // TODO: update this for xmr
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
                //configPeerNodes(socks5Proxy);
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

        XmrNodesSetupPreferences xmrNodesSetupPreferences = new XmrNodesSetupPreferences(preferences);
        List<XmrNode> nodes = xmrNodesSetupPreferences.selectPreferredNodes(xmrNodes);

        XmrNodesRepository repository = new XmrNodesRepository(nodes);
        boolean isUseClearNodesWithProxies = (useAllProvidedNodes || xmrNodesSetupPreferences.isUseCustomNodes());
        List<PeerAddress> peers = repository.getPeerAddresses(proxy, isUseClearNodesWithProxies);

        XmrNetworkConfig networkConfig = new XmrNetworkConfig(walletConfig, params, socks5DiscoverMode, proxy);
        networkConfig.proposePeers(peers);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Backup
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void backupWallets() {
        // TODO: remove?
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

    public void addSetupTaskHandler(Runnable handler) {
        setupTaskHandlers.add(handler);
    }

    public void addSetupCompletedHandler(Runnable handler) {
        setupCompletedHandlers.add(handler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Wallet getBtcWallet() {
        return walletConfig.btcWallet();
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
        throw new RuntimeException("WalletsSetup.isChainHeightSyncedWithinTolerance() not implemented for BTC");
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
