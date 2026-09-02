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

package haveno.core.xmr.wallet;

import static com.google.common.base.Preconditions.checkState;
import com.google.common.util.concurrent.Service.State;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import common.utils.JsonUtils;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.XmrConnectionService;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.exceptions.WalletUnavailableException;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.network.utils.EventThrottler;
import java.io.File;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.LongProperty;
import javafx.beans.value.ChangeListener;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroRpcError;
import monero.common.MoneroUtils;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import monero.daemon.model.MoneroFeeEstimate;
import monero.daemon.model.MoneroKeyImage;
import monero.daemon.model.MoneroNetworkType;
import monero.daemon.model.MoneroOutput;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletFull;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroCheckTx;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxPriority;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListenerI;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class XmrWalletService extends XmrWalletBase {
    private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

    // monero configuration
    public static final int NUM_BLOCKS_UNLOCK = 10;
    private static volatile String moneroBinsDir;
    public static final String MONERO_WALLET_RPC_NAME = Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc";
    public static final MoneroTxPriority PROTOCOL_FEE_PRIORITY = MoneroTxPriority.DEFAULT;
    public static final int MONERO_LOG_LEVEL = -1; // monero library log level, -1 to disable
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = getMoneroNetworkType();
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    private static final String KEYS_FILE_POSTFIX = ".keys";
    private static final String ADDRESS_FILE_POSTFIX = ".address.txt";
    private static final int NUM_WALLET_BACKUPS = 3;
    private static final boolean PRINT_RPC_STACK_TRACE = false;
    private static final long SHUTDOWN_TIMEOUT_MS = 60000;
    private static final long FORCE_CLOSE_TIMEOUT_MS = 15000; // bounded wait since native close can block draining a stalled network request
    private static final long PENDING_CLOSE_TIMEOUT_MS = 240000; // max wait to reopen, covering wallet2's 3.5 minute rpc timeout
    private static final long NUM_BLOCKS_BEHIND_TOLERANCE = 5;
    private static final long POLL_TXS_TOLERANCE_MS = 1000 * 60 * 3; // request connection switch if txs not updated within 3 minutes
    private static final boolean TEST_STARTUP_SYNC_ERROR = false;
    private static final long INIT_WALLET_DELAY_MS = 5000;
    private static final int NUM_CHECK_TX_KEY_ATTEMPTS = 3;
    private static final long CHECK_TX_KEY_ATTEMPT_DELAY_MS = 1000;
    private static final String THREAD_ID = XmrWalletService.class.getSimpleName();

    public static String getMoneroBinsDir() {
        if (moneroBinsDir == null) {
            moneroBinsDir = Config.appDataDir().getAbsolutePath();
        }
        return moneroBinsDir;
    }

    public static String getMoneroWalletRpcPath() {
        return getMoneroBinsDir() + File.separator + MONERO_WALLET_RPC_NAME;
    }

    private final User user;
    private final Preferences preferences;
    private final CoreAccountService accountService;
    private final XmrAddressEntryList xmrAddressEntryList;
    private final WalletsSetup walletsSetup;

    private final File walletDir;
    private final int rpcBindPort;
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private ChangeListener<? super Number> walletInitListener;

    private final Object lock = new Object();
    private final Object addressEntryLock = new Object(); // never acquire walletLock while holding this lock
    private final Object seedValidationLock = new Object();
    private TaskLooper pollLooper;
    private boolean pollInProgress;
    private Long pollPeriodMs;
    private EventThrottler logMonerodNotSyncedThrottler = new EventThrottler(HavenoUtils.LOG_MONEROD_NOT_SYNCED_WARN_PERIOD_MS, TimeUnit.MILLISECONDS);
    private EventThrottler logPollErrorRateThrottler = new EventThrottler(HavenoUtils.LOG_POLL_ERROR_PERIOD_MS, TimeUnit.MILLISECONDS);
    private long lastPollTxsTimestamp; 
    private final Object pollLock = new Object();
    private final Map<String, Future<?>> pendingWalletCloses = new ConcurrentHashMap<>(); // wallets force closing in background by path
    private Long cachedHeight;
    private BigInteger cachedBalance;
    private BigInteger cachedAvailableBalance = null;
    private List<MoneroSubaddress> cachedSubaddresses;
    private List<MoneroOutputWallet> cachedOutputs;
    private List<MoneroTxWallet> cachedTxs;
    private boolean isInitializingWallet;
    private Long walletRestoreHeight; // tracked in-process because wallet rpc cannot report it

    private static final Object WALLET_HEIGHT_MONITOR_LOCK = new Object();
    private static final long WALLET_HEIGHT_MONITOR_PERIOD_SEC = 1200; // request connection change if wallet height is not updated within this period
    private long lastWalletHeightMonitorUpdate;
    private Timer walletHeightMonitorTimer;
    private static final Object requestConnectionSwitchSynchronousLock = new Object();
    private boolean isProcessingRequestConnectionSwitchSynchronous;

    @SuppressWarnings("unused")
    @Inject
    XmrWalletService(User user,
                     Preferences preferences,
                     CoreAccountService accountService,
                     XmrConnectionService xmrConnectionService,
                     WalletsSetup walletsSetup,
                     XmrAddressEntryList xmrAddressEntryList,
                     @Named(Config.WALLET_DIR) File walletDir,
                     @Named(Config.WALLET_RPC_BIND_PORT) int rpcBindPort) {
        this.user = user;
        this.preferences = preferences;
        this.accountService = accountService;
        this.walletsSetup = walletsSetup;
        this.xmrAddressEntryList = xmrAddressEntryList;
        this.walletDir = walletDir;
        this.rpcBindPort = rpcBindPort;
        this.xmrConnectionService = xmrConnectionService; // TODO: super's is null unless set here from injection
        HavenoUtils.xmrWalletService = this;
        MONERO_WALLET_RPC_MANAGER.onStartUp(); // clear the shared manager's shutdown state, which persists across in-process restarts

        // set monero logging
        if (MONERO_LOG_LEVEL >= 0) MoneroUtils.setLogLevel(MONERO_LOG_LEVEL);

        // initialize after account open and basic setup
        walletsSetup.addSetupTaskHandler(() -> { // TODO: use something better than legacy WalletSetup for notification to initialize

            // initialize
            initialize();

            // listen for account updates
            accountService.addListener(new AccountServiceListener() {

                @Override
                public void onAccountCreated() {
                    log.info("onAccountCreated()");
                    initialize();
                }

                @Override
                public void onAccountOpened() {
                    log.info("onAccountOpened()");
                    initialize();
                }

                @Override
                public void onAccountClosed() {
                    log.info("onAccountClosed()");
                    wasWalletSynced = false;
                    closeMainWallet(true);
                    clearSyncProgress();
                    // TODO: reset more properties?
                }

                @Override
                public void onPasswordChanged(String oldPassword, String newPassword) {
                    log.info(getClass() + "accountservice.onPasswordChanged()");
                    if (oldPassword == null || oldPassword.isEmpty()) oldPassword = MONERO_WALLET_RPC_DEFAULT_PASSWORD;
                    if (newPassword == null || newPassword.isEmpty()) newPassword = MONERO_WALLET_RPC_DEFAULT_PASSWORD;
                    changeWalletPasswords(oldPassword, newPassword);
                }
            });
        });
    }

    public MoneroWallet getWallet() {
        State state = walletsSetup.getWalletConfig().state();
        checkState(state == State.STARTING || state == State.RUNNING, "Cannot call until startup is complete and running, but state is: " + state);
        return wallet;
    }

    private MoneroWallet getInitializedWallet() {
        synchronized (walletLock) {
            if (wallet == null || !isPolling()) initMainWallet();
            return wallet;
        }
    }

    /**
     * Get the wallet creation date in seconds since epoch.
     *
     * @return the wallet creation date in seconds since epoch
     */
    public long getWalletCreationDate() {
        return user.getWalletCreationDate();
    }

    @Override
    protected void saveWalletNoSync() {
        if (wallet == null) throw new IllegalStateException("Cannot save main wallet because it's not open");
        wallet.save();
        lastSaveTimeMs = System.currentTimeMillis();
    }

    public boolean isWalletAvailable() {
        try {
            return getWallet() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isWalletEncrypted() {
        return accountService.getPassword() != null;
    }

    public LongProperty walletHeightProperty() {
        return walletHeight;
    }

    public boolean isSyncedWithinTolerance() {
        if (!xmrConnectionService.isSyncedWithinTolerance()) return false;
        Long targetHeight = xmrConnectionService.getTargetHeight();
        if (targetHeight == null) return false;
        if (targetHeight - 1 - walletHeight.get() <= NUM_BLOCKS_BEHIND_TOLERANCE) return true; // synced if within a few blocks of target height
        return false;
    }

    public MoneroDaemonRpc getMonerod() {
        return xmrConnectionService.getMonerod();
    }

    public String getWalletPassword() {
        return accountService.getPassword() == null ? MONERO_WALLET_RPC_DEFAULT_PASSWORD : accountService.getPassword();
    }

    public boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + KEYS_FILE_POSTFIX).exists();
    }

    public MoneroWallet createWallet(String walletName, boolean applyProxyUri, boolean trustDaemon) {
        return createWallet(walletName, null, applyProxyUri, trustDaemon);
    }

    private MoneroWallet createWallet(String walletName, Integer walletRpcPort, boolean applyProxyUri, boolean trustDaemon) {
        log.info("{}.createWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        MoneroWalletConfig config = getWalletConfig(walletName);
        return isNativeLibraryApplied() ? createWalletFull(config, applyProxyUri) : createWalletRpc(config, walletRpcPort, applyProxyUri, trustDaemon);
    }

    private MoneroWallet createWalletFromSeed(String walletName, Integer walletRpcPort, boolean applyProxyUri, boolean trustDaemon, String seed, long restoreHeight) {
        log.info("{}.createWalletFromSeed({}, {})", getClass().getSimpleName(), walletName, restoreHeight);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        if (!isSeedValid(seed)) throw new IllegalArgumentException("Invalid wallet seed");
        MoneroWalletConfig config = getWalletConfig(walletName).setSeed(seed).setRestoreHeight(restoreHeight);
        return isNativeLibraryApplied() ? createWalletFull(config, applyProxyUri) : createWalletRpc(config, walletRpcPort, applyProxyUri, trustDaemon);
    }

    // mainnet genesis timestamp and v2 fork height, to translate between block heights and dates
    // (1-minute target blocks before the v2 fork, 2-minute after)
    private static final long GENESIS_TIMESTAMP = 1397818193;
    private static final long V2_FORK_HEIGHT = 1009827;

    // estimate the timestamp of a mainnet block, capped at yesterday
    private static long estimateHeightTimestamp(long height) {
        long timestamp = GENESIS_TIMESTAMP + Math.min(height, V2_FORK_HEIGHT) * 60 + Math.max(0, height - V2_FORK_HEIGHT) * 120;
        return Math.min(timestamp, LocalDate.now().atStartOfDay().minusDays(1).toEpochSecond(ZoneOffset.UTC));
    }

    /** Estimate the mainnet height at the given UTC date offline, padded low since target block times drift from the chain. */
    public static long estimateHeightForDate(LocalDate date) {
        long height = estimateTimestampHeight(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC));
        return Math.max(0, height - Math.max(30 * 720, height / 100)); // pad by a month or 1%, whichever is more
    }

    // estimate the mainnet height at the given UTC timestamp, the unpadded inverse of estimateHeightTimestamp
    private static long estimateTimestampHeight(long timestamp) {
        long v2ForkTimestamp = GENESIS_TIMESTAMP + V2_FORK_HEIGHT * 60;
        return Math.max(0, timestamp <= v2ForkTimestamp
                ? (timestamp - GENESIS_TIMESTAMP) / 60
                : V2_FORK_HEIGHT + (timestamp - v2ForkTimestamp) / 120);
    }

    /** Check whether the seed is a valid wallet seed, using an offline temporary wallet (native or RPC). */
    public boolean isSeedValid(String seed) {
        if (isUseNativeXmrWallet()) MoneroUtils.tryLoadNativeLibrary();
        synchronized (seedValidationLock) {
            return isNativeLibraryApplied() ? isSeedValidNative(seed) : isSeedValidRpc(seed);
        }
    }

    /** Preload the native library and wallet creation path so the first seed validation is fast. */
    public void warmUpSeedValidation() {
        if (!isUseNativeXmrWallet()) return; // rpc validation starts a new process per check, so nothing to warm
        MoneroUtils.tryLoadNativeLibrary();
        if (!isNativeLibraryApplied()) return;
        synchronized (seedValidationLock) {
            MoneroWalletFull wallet = null;
            try {
                wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                        .setNetworkType(getMoneroNetworkType())
                        .setPassword(""));
            } finally {
                if (wallet != null) forceCloseWallet(wallet, null);
            }
        }
    }

    // validate the seed by creating an in-memory wallet with the native library
    private boolean isSeedValidNative(String seed) {
        MoneroWalletFull wallet = null;
        try {
            wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                    .setNetworkType(getMoneroNetworkType())
                    .setPassword("")
                    .setSeed(seed));
            return true;
        } catch (MoneroError e) {
            log.info("Seed failed validation: {}", e.getMessage());
            return false;
        } finally {
            if (wallet != null) forceCloseWallet(wallet, null);
        }
    }

    // validate the seed by restoring a temporary wallet with an offline monero-wallet-rpc instance
    private boolean isSeedValidRpc(String seed) {
        String walletName = MONERO_WALLET_NAME + "_seed_validation";
        deleteWalletFiles(walletName); // remove stale files from any previous validation
        MoneroWalletRpc walletRpc = startWalletRpcInstance(null, null);
        try {
            walletRpc.createWallet(getWalletConfig(walletName).setSeed(seed).setPassword(UUID.randomUUID().toString())); // never reopened, so leftover files after a crash stay unreadable
            return true;
        } catch (MoneroError e) {
            log.info("Seed failed validation: {}", e.getMessage());
            return false;
        } finally {
            forceCloseWallet(walletRpc, walletName);
            deleteWalletFiles(walletName);
        }
    }

    public MoneroWallet openWallet(String walletName, boolean applyProxyUri, boolean trustDaemon) {
        return openWallet(walletName, null, applyProxyUri, trustDaemon);
    }

    public MoneroWallet openWallet(String walletName, Integer walletRpcPort, boolean applyProxyUri, boolean trustDaemon) {
        log.debug("{}.openWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet '" + walletName + "' because shutting down");
        MoneroWalletConfig config = getWalletConfig(walletName);
        return isNativeLibraryApplied() ? openWalletFull(config, applyProxyUri) : openWalletRpc(config, walletRpcPort, applyProxyUri, trustDaemon);
    }

    // Restore the main wallet from the given seed, scanning from restoreHeight or else restoreDate. The restored wallet
    // is fully created and saved before the current one is backed up and replaced, so failure leaves the current wallet intact.
    public void restoreWalletFromSeed(String seed, Long restoreHeight, LocalDate restoreDate) {
        if (!isSeedValid(seed)) throw new IllegalArgumentException("Invalid wallet seed");
        synchronized (walletLock) {
            if (isShutDownStarted) throw new IllegalStateException("Cannot restore wallet because shutting down");
            if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Cannot restore wallet because there is no connection to Monero daemon");
            if (restoreHeight == null && restoreDate != null) restoreHeight = estimateHeightForDate(restoreDate);
            long height = restoreHeight == null ? 0 : restoreHeight;
            log.info("{}.restoreWalletFromSeed(restoreHeight={})", getClass().getSimpleName(), height);

            // create the restored wallet under a temporary name so the current wallet is preserved if the seed is invalid
            String restoreName = MONERO_WALLET_NAME + "_restore";
            completeInterruptedRestore(); // else the only complete wallet files would be deleted
            deleteWalletFiles(restoreName);
            MoneroWalletConfig config = getWalletConfig(restoreName).setSeed(seed).setRestoreHeight(height);
            MoneroWallet restored = isNativeLibraryApplied() ? createWalletFull(config, isProxyApplied()) : createWalletRpc(config, null, isProxyApplied(), xmrConnectionService.isTrustedDaemon());
            closeWallet(restored, true);

            // replace the current wallet with the restored wallet, keeping a backup
            if (!closeMainWallet(true)) {

                // reopen the wallet with a fresh handle since its state is unknown after a failed close
                forceCloseMainWallet();
                reopenMainWallet();
                throw new IllegalStateException("Cannot restore wallet because closing the current wallet failed");
            }
            long previousWalletCreationDate = user.getWalletCreationDate();
            user.setWalletCreationDate(estimateHeightTimestamp(height)); // before replacing files, so a replacement completed on startup keeps it
            try {
                if (walletExists(MONERO_WALLET_NAME)) {
                    if (!backupWallet(MONERO_WALLET_NAME)) throw new IllegalStateException("Cannot restore wallet because backing up the current wallet failed");
                    deleteWallet(MONERO_WALLET_NAME);
                }
                moveWallet(restoreName, MONERO_WALLET_NAME);
            } catch (RuntimeException e) {

                // resume with the current wallet if it is still in place
                if (walletExists(MONERO_WALLET_NAME) && walletExists(restoreName)) {
                    user.setWalletCreationDate(previousWalletCreationDate);
                    reopenMainWallet();
                }
                throw e;
            }
        }
    }

    // Reopen the main wallet and resume polling after a failed operation left it closed.
    private void reopenMainWallet() {
        try {
            wallet = openWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied(), xmrConnectionService.isTrustedDaemon());
            startPolling();
        } catch (Exception e) {
            log.warn("Error reopening main wallet: {}\n", e.getMessage(), e);
        }
    }

    // Complete a restore from seed interrupted after the current wallet was deleted but before the restored wallet was moved.
    private void completeInterruptedRestore() {
        String restoreName = MONERO_WALLET_NAME + "_restore";
        if (!walletExists(MONERO_WALLET_NAME) && walletExists(restoreName)) {
            log.warn("Completing restore from seed interrupted while replacing the main wallet");
            moveWallet(restoreName, MONERO_WALLET_NAME);
        }
    }

    // Move a wallet's files to another name within the wallet directory, moving the keys file last since it determines which wallet exists.
    private void moveWallet(String fromName, String toName) {
        for (String postfix : new String[] {"", ADDRESS_FILE_POSTFIX, KEYS_FILE_POSTFIX}) {
            File from = new File(walletDir, fromName + postfix);
            if (!from.exists()) continue; // address file is absent on mainnet
            File to = new File(walletDir, toName + postfix);
            if (!from.renameTo(to)) throw new RuntimeException("Failed to move wallet file " + Utilities.redactSensitiveInfo(from.toString()) + " to " + Utilities.redactSensitiveInfo(to.toString()));
        }
    }

    // Delete a wallet's files if present, tolerating a partially written wallet.
    private void deleteWalletFiles(String walletName) {
        assertNotPath(walletName);
        for (String postfix : new String[] {"", KEYS_FILE_POSTFIX, ADDRESS_FILE_POSTFIX}) {
            File file = new File(walletDir, walletName + postfix);
            if (file.exists() && !file.delete()) throw new RuntimeException("Failed to delete wallet file " + Utilities.redactSensitiveInfo(file.toString()));
        }
    }

    private MoneroWalletConfig getWalletConfig(String walletName) {
        MoneroWalletConfig config = new MoneroWalletConfig().setPath(getWalletPath(walletName)).setPassword(getWalletPassword());
        if (isNativeLibraryApplied()) config.setNetworkType(getMoneroNetworkType());
        return config;
    }

    private String getWalletPath(String walletName) {
        return (isNativeLibraryApplied() ? walletDir.getPath() + File.separator : "") + walletName;
    }

    private static String getWalletName(String walletPath) {
        return walletPath.substring(walletPath.lastIndexOf(File.separator) + 1);
    }

    private boolean isUseNativeXmrWallet() {
        return preferences.isUseNativeXmrWallet();
    }

    private boolean isNativeLibraryApplied() {
        return isUseNativeXmrWallet() && MoneroUtils.isNativeLibraryLoaded();
    }

    public void closeWallet(MoneroWallet wallet, boolean save) {
        log.debug("Closing wallet with path={}, save={}", Utilities.redactSensitiveInfo(wallet.getPath()), save);
        MoneroError err = null;
        String path = wallet.getPath();
        try {
            if (save && wallet instanceof MoneroWalletRpc) {
                ((MoneroWalletRpc) wallet).stop(); // saves wallet and stops rpc server
            } else {
                wallet.close(save);
            }
        } catch (MoneroError e) {
            err = e;
        }

        // stop wallet rpc instance if applicable
        if (wallet instanceof MoneroWalletRpc) MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, false);
        if (err != null) throw err;
    }

    public void forceCloseWallet(MoneroWallet wallet, String path) {
        if (wallet == null) {
            log.warn("Ignoring force close wallet because wallet is null, path={}", Utilities.redactSensitiveInfo(path));
            return;
        }
        if (wallet instanceof MoneroWalletRpc) {
            MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, true);
        } else {

            // close natively in background with bounded wait, since closing can block draining a stalled network request
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "force-close-wallet");
                thread.setDaemon(true); // do not block application exit
                return thread;
            });
            Future<?> closeTask = executor.submit(() -> wallet.close(false));
            executor.shutdown();
            if (path != null) pendingWalletCloses.put(path, closeTask);
            try {
                closeTask.get(FORCE_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Timeout force closing wallet after {} ms, path={}, will finish closing in background", FORCE_CLOSE_TIMEOUT_MS, Utilities.redactSensitiveInfo(path));
            } catch (Exception e) {
                log.warn("Error force closing wallet, path={}: {}", Utilities.redactSensitiveInfo(path), e.getMessage());
            } finally {
                if (path != null && closeTask.isDone()) pendingWalletCloses.remove(path, closeTask); // keep entry if still closing so reopening awaits it
            }
        }
    }

    // reopening before a background close finishes fails on the wallet keys file lock, so await any pending close
    private void awaitPendingWalletClose(String path) {
        if (path == null) return;
        Future<?> pendingClose = pendingWalletCloses.remove(path);
        if (pendingClose == null || pendingClose.isDone()) return;
        log.warn("Waiting for wallet to finish closing in background before opening, path={}", Utilities.redactSensitiveInfo(path));
        long startTime = System.currentTimeMillis();
        try {
            pendingClose.get(PENDING_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info("Done waiting {} ms for wallet to close, path={}", System.currentTimeMillis() - startTime, Utilities.redactSensitiveInfo(path));
        } catch (Exception e) {
            log.warn("Error waiting for wallet to finish closing, path={}: {}", Utilities.redactSensitiveInfo(path), e.getMessage());
        }
    }

    public void deleteWallet(String walletName) {
        assertNotPath(walletName);
        log.info("{}.deleteWallet({})", getClass().getSimpleName(), walletName);
        if (!walletExists(walletName)) throw new RuntimeException("Wallet does not exist at path: " + walletName);
        String path = walletDir.toString() + File.separator + walletName;
        String redactedPath = Utilities.redactSensitiveInfo(path);
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet cache file: " + redactedPath);
        if (!new File(path + KEYS_FILE_POSTFIX).delete()) throw new RuntimeException("Failed to delete wallet keys file: " + redactedPath + KEYS_FILE_POSTFIX);
        if (!new File(path + ADDRESS_FILE_POSTFIX).delete() && !Config.baseCurrencyNetwork().isMainnet()) throw new RuntimeException("Failed to delete wallet address file: " + redactedPath + ADDRESS_FILE_POSTFIX); // mainnet does not have address file by default
    }

    // returns false if backing up any existing wallet file failed
    public boolean backupWallet(String walletName) {
        assertNotPath(walletName);
        boolean success = FileUtil.rollingBackup(walletDir, walletName, NUM_WALLET_BACKUPS);
        success &= FileUtil.rollingBackup(walletDir, walletName + KEYS_FILE_POSTFIX, NUM_WALLET_BACKUPS);
        success &= FileUtil.rollingBackup(walletDir, walletName + ADDRESS_FILE_POSTFIX, NUM_WALLET_BACKUPS);
        return success;
    }

    public void deleteWalletBackups(String walletName) {
        assertNotPath(walletName);
        FileUtil.deleteRollingBackup(walletDir, walletName);
        FileUtil.deleteRollingBackup(walletDir, walletName + KEYS_FILE_POSTFIX);
        FileUtil.deleteRollingBackup(walletDir, walletName + ADDRESS_FILE_POSTFIX);
    }

    private static void assertNotPath(String name) {
        if (name.contains(File.separator)) throw new IllegalArgumentException("Path not expected: " + name);
    }

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        MoneroTxWallet tx = createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
        //printTxs("XmrWalletService.createTx", tx);
        return tx;
    }

    public MoneroTxWallet createTx(MoneroTxConfig txConfig) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                MoneroTxWallet tx = wallet.createTx(txConfig);
                if (Boolean.TRUE.equals(txConfig.getRelay())) {
                    cachedTxs.addFirst(tx);
                    cacheWalletInfo();
                    saveWallet();
                }
                return tx;
            }
        }
    }

    public List<MoneroTxWallet> createSweepTxs(String address) {
        return createSweepTxs(new MoneroTxConfig().setAccountIndex(0).setAddress(address).setRelay(false));
    }

    public List<MoneroTxWallet> createSweepTxs(MoneroTxConfig txConfig) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                List<MoneroTxWallet> txs = wallet.sweepUnlocked(txConfig);
                if (Boolean.TRUE.equals(txConfig.getRelay())) {
                    for (MoneroTxWallet tx : txs) cachedTxs.addFirst(tx);
                    cacheWalletInfo();
                    saveWallet();
                }
                return txs;
            }
        }
    }

    public List<String> relayTxs(List<String> metadatas) {
        synchronized (walletLock) {
            List<String> txIds = wallet.relayTxs(metadatas);
            saveWallet();
            return txIds;
        }
    }

    /**
     * Freeze reserved outputs and thaw unreserved outputs.
     */
    public void fixReservedOutputs() {
        synchronized (walletLock) {

            // collect reserved outputs including split outputs awaiting reserve txs
            OpenOfferManager openOfferManager = HavenoUtils.tradeManager.getOpenOfferManager();
            List<OpenOffer> openOffers = openOfferManager.getOpenOffers();
            Set<String> reservedKeyImages = getReservedKeyImages(openOffers);
            for (OpenOffer openOffer : openOffers) reservedKeyImages.addAll(openOfferManager.getSplitOutputKeyImages(openOffer));

            freezeReservedOutputs(reservedKeyImages);
            thawUnreservedOutputs(reservedKeyImages);
        }
    }

    /**
     * Get the key images of reserve and deposit tx inputs of open trades and the given open offers.
     */
    public Set<String> getReservedKeyImages(List<OpenOffer> openOffers) {
        Set<String> reservedKeyImages = new HashSet<String>();
        for (Trade trade : HavenoUtils.tradeManager.getOpenTrades()) {
            if (trade.getSelf().getReserveTxKeyImages() == null) continue;
            reservedKeyImages.addAll(trade.getSelf().getReserveTxKeyImages());
        }
        for (OpenOffer openOffer : openOffers) {
            if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() == null) continue;
            reservedKeyImages.addAll(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages());
        }
        return reservedKeyImages;
    }

    private void freezeReservedOutputs(Set<String> reservedKeyImages) {
        synchronized (walletLock) {

            // ensure wallet is open
            if (wallet == null) {
                log.warn("Cannot freeze reserved outputs because wallet not open");
                return;
            }

            // freeze reserved outputs
            Set<String> reservedUnfrozenKeyImages = getOutputs(new MoneroOutputQuery()
                    .setIsFrozen(false)
                    .setIsSpent(false))
                    .stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toSet());
            reservedUnfrozenKeyImages.retainAll(reservedKeyImages);
            if (!reservedUnfrozenKeyImages.isEmpty()) {
                log.warn("Freezing unfrozen outputs which are reserved for offer or trade: " + reservedUnfrozenKeyImages);
                freezeOutputs(reservedUnfrozenKeyImages);
            }
        }
    }

    private void thawUnreservedOutputs(Set<String> reservedKeyImages) {
        synchronized (walletLock) {

            // ensure wallet is open
            if (wallet == null) {
                log.warn("Cannot thaw unreserved outputs because wallet not open");
                return;
            }

            // thaw unreserved outputs
            Set<String> unreservedFrozenKeyImages = getOutputs(new MoneroOutputQuery()
                    .setIsFrozen(true)
                    .setIsSpent(false))
                    .stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toSet());
            unreservedFrozenKeyImages.removeAll(reservedKeyImages);
            if (!unreservedFrozenKeyImages.isEmpty()) {
                log.warn("Thawing frozen outputs which are not reserved for offer or trade: " + unreservedFrozenKeyImages);
                thawOutputs(unreservedFrozenKeyImages);
            }
        }
    }

    /**
     * Freeze the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to freeze (ignored if null or empty)
     */
    public void freezeOutputs(Collection<String> keyImages) {
        if (keyImages == null || keyImages.isEmpty()) return;
        synchronized (walletLock) {

            // collect outputs to freeze
            List<String> unfrozenKeyImages = getOutputs(new MoneroOutputQuery().setIsFrozen(false).setIsSpent(false)).stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toList());
            unfrozenKeyImages.retainAll(keyImages);
            if (unfrozenKeyImages.isEmpty()) return;

            // freeze outputs
            for (String keyImage : unfrozenKeyImages) getInitializedWallet().freezeOutput(keyImage);
            cacheNonPoolTxs();
            cacheWalletInfo();
            saveWallet();
        }
    }

    /**
     * Thaw the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to thaw (ignored if null or empty)
     */
    public void thawOutputs(Collection<String> keyImages) {
        if (keyImages == null || keyImages.isEmpty()) return;
        synchronized (walletLock) {

            // collect outputs to thaw
            List<String> frozenKeyImages = getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false)).stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toList());
            frozenKeyImages.retainAll(keyImages);
            if (frozenKeyImages.isEmpty()) return;

            // thaw outputs
            for (String keyImage : frozenKeyImages) getInitializedWallet().thawOutput(keyImage);
            cacheNonPoolTxs();
            cacheWalletInfo();
            saveWallet();
        }
    }

    private void cacheNonPoolTxs() {

        // get non-pool txs
        List<MoneroTxWallet> nonPoolTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true).setInTxPool(false));

        // replace non-pool txs in cache
        for (MoneroTxWallet nonPoolTx : nonPoolTxs) {
            boolean replaced = false;
            for (int i = 0; i < cachedTxs.size(); i++) {
                if (cachedTxs.get(i).getHash().equals(nonPoolTx.getHash())) {
                    cachedTxs.set(i, nonPoolTx);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) cachedTxs.add(nonPoolTx);
        }
    }

    private List<Integer> getSubaddressesWithExactInput(BigInteger amount) {

        // fetch unspent, unfrozen, unlocked outputs
        List<MoneroOutputWallet> exactOutputs = getOutputs(new MoneroOutputQuery()
                .setAmount(amount)
                .setIsSpent(false)
                .setIsFrozen(false)
                .setTxQuery(new MoneroTxQuery().setIsLocked(false)));

        // collect subaddresses indices as sorted set
        TreeSet<Integer> subaddressIndices = new TreeSet<Integer>();
        for (MoneroOutputWallet output : exactOutputs) subaddressIndices.add(output.getSubaddressIndex());
        return new ArrayList<Integer>(subaddressIndices);
    }

    /**
     * Create the reserve tx and freeze its inputs. The full amount is returned
     * to the sender's payout address less the penalty and mining fees.
     *
     * @param tradable the offer or trade to reserve funds for
     * @param penaltyFee penalty fee for breaking protocol
     * @param tradeFee trade fee
     * @param sendTradeAmount trade amount to send peer
     * @param securityDeposit security deposit amount
     * @param returnAddress return address for reserved funds
     * @param reserveExactAmount specifies to reserve the exact input amount
     * @param preferredSubaddressIndex preferred source subaddress to spend from (optional)
     * @return the reserve tx
     */
    public MoneroTxWallet createReserveTx(Tradable tradable, BigInteger penaltyFee, BigInteger tradeFee, BigInteger sendTradeAmount, BigInteger securityDeposit, String returnAddress, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                log.info("Creating reserve tx for {} {} with preferred subaddress index={}, return address={}", tradable.getClass().getSimpleName(), tradable.getShortId(), preferredSubaddressIndex, returnAddress);
                long time = System.currentTimeMillis();
                BigInteger sendAmount = sendTradeAmount.add(securityDeposit).add(tradeFee).subtract(penaltyFee);
                MoneroTxWallet reserveTx = createTradeTx(penaltyFee, HavenoUtils.getBurnAddress(), sendAmount, returnAddress, reserveExactAmount, preferredSubaddressIndex);
                log.info("Done creating reserve tx for {} {} in {} ms", tradable.getClass().getSimpleName(), tradable.getShortId(), System.currentTimeMillis() - time);
                return reserveTx;
            }
        }
    }

    /**
     * Create the multisig deposit tx and freeze its inputs.
     *
     * @param trade the trade to create a deposit tx from
     * @param reserveExactAmount specifies to reserve the exact input amount
     * @param preferredSubaddressIndex preferred source subaddress to spend from (optional)
     * @return MoneroTxWallet the multisig deposit tx
     */
    public MoneroTxWallet createDepositTx(Trade trade, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                BigInteger feeAmount = trade instanceof MakerTrade ? trade.getMakerFee() : trade.getTakerFee();
                String feeAddress = trade.getProcessModel().getTradeFeeAddress();
                BigInteger sendTradeAmount = trade instanceof BuyerTrade ? BigInteger.ZERO : trade.getAmount();
                BigInteger securityDeposit = trade instanceof BuyerTrade ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
                BigInteger sendAmount = sendTradeAmount.add(securityDeposit);
                String multisigAddress = trade.getProcessModel().getMultisigAddress();
                long time = System.currentTimeMillis();
                log.info("Creating deposit tx for trade {} {} with multisig address={}", trade.getClass().getSimpleName(), trade.getShortId(), multisigAddress);
                MoneroTxWallet depositTx = createTradeTx(feeAmount, feeAddress, sendAmount, multisigAddress, reserveExactAmount, preferredSubaddressIndex);
                log.info("Done creating deposit tx for trade {} {} in {} ms", trade.getClass().getSimpleName(), trade.getShortId(), System.currentTimeMillis() - time);
                return depositTx;
            }
        }
    }

    private MoneroTxWallet createTradeTx(BigInteger feeAmount, String feeAddress, BigInteger sendAmount, String sendAddress, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        synchronized (walletLock) {
            MoneroWallet wallet = getWallet();

            // create a list of subaddresses to attempt spending from in preferred order
            List<Integer> subaddressIndices = new ArrayList<Integer>();
            if (reserveExactAmount) {
                BigInteger exactInputAmount = feeAmount.add(sendAmount);
                List<Integer> subaddressIndicesWithExactInput = getSubaddressesWithExactInput(exactInputAmount);
                if (preferredSubaddressIndex != null) subaddressIndicesWithExactInput.remove(preferredSubaddressIndex);
                Collections.sort(subaddressIndicesWithExactInput);
                Collections.reverse(subaddressIndicesWithExactInput);
                subaddressIndices.addAll(subaddressIndicesWithExactInput);
            }
            if (preferredSubaddressIndex != null) {
                if (wallet.getBalance(0, preferredSubaddressIndex).compareTo(BigInteger.ZERO) > 0) {
                    subaddressIndices.add(0, preferredSubaddressIndex); // try preferred subaddress first if funded
                } else if (reserveExactAmount) {
                    subaddressIndices.add(preferredSubaddressIndex); // otherwise only try preferred subaddress if using exact output
                }
            }

            // first try preferred subaddressess
            for (int i = 0; i < subaddressIndices.size(); i++) {
                try {
                    return createTradeTxFromSubaddress(feeAmount, feeAddress, sendAmount, sendAddress, subaddressIndices.get(i));
                } catch (Exception e) {
                    log.info("Cannot create trade tx from preferred subaddress index " + subaddressIndices.get(i) + ": " + e.getMessage());
                }
            }

            // try any subaddress
            if (!subaddressIndices.isEmpty()) log.info("Could not create trade tx from preferred subaddresses, trying any subaddress");
            return createTradeTxFromSubaddress(feeAmount, feeAddress, sendAmount, sendAddress, null);
        }
    }

    private MoneroTxWallet createTradeTxFromSubaddress(BigInteger feeAmount, String feeAddress, BigInteger sendAmount, String sendAddress, Integer subaddressIndex) {
        synchronized (walletLock) {

            // create tx
            MoneroTxConfig txConfig = new MoneroTxConfig()
                    .setAccountIndex(0)
                    .setSubaddressIndices(subaddressIndex)
                    .addDestination(sendAddress, sendAmount)
                    .setSubtractFeeFrom(0) // pay mining fee from send amount
                    .setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY);
            if (!BigInteger.valueOf(0).equals(feeAmount)) txConfig.addDestination(feeAddress, feeAmount);
            MoneroTxWallet tradeTx = createTx(txConfig);

            // freeze inputs
            List<String> keyImages = new ArrayList<String>();
            for (MoneroOutput input : tradeTx.getInputs()) keyImages.add(input.getKeyImage().getHex());
            freezeOutputs(keyImages);
            return tradeTx;
        }
    }

    public MoneroTx verifyReserveTx(String offerId, BigInteger penaltyFee, BigInteger tradeFee, BigInteger sendTradeAmount, BigInteger securityDeposit, String returnAddress, String txHash, String txHex, String txKey, List<String> keyImages) {
        BigInteger sendAmount = sendTradeAmount.add(securityDeposit).add(tradeFee).subtract(penaltyFee);
        return verifyTradeTx(offerId, penaltyFee, HavenoUtils.getBurnAddress(), sendAmount, returnAddress, txHash, txHex, txKey, keyImages);
    }

    public MoneroTx verifyDepositTx(String offerId, BigInteger feeAmount, String feeAddress, BigInteger sendTradeAmount, BigInteger securityDeposit, String multisigAddress, String txHash, String txHex, String txKey, List<String> keyImages) {
        BigInteger sendAmount = sendTradeAmount.add(securityDeposit);
        return verifyTradeTx(offerId, feeAmount, feeAddress, sendAmount, multisigAddress, txHash, txHex, txKey, keyImages);
    }

    /**
     * Verify a reserve or deposit transaction.
     * Checks double spends, trade fee, deposit amount and destination, and miner fee.
     * The transaction is submitted to the pool then flushed without relaying.
     *
     * @param offerId id of offer to verify trade tx
     * @param tradeFeeAmount amount sent to fee address
     * @param feeAddress fee address
     * @param sendAmount amount sent to transfer address
     * @param sendAddress transfer address
     * @param txHash transaction hash
     * @param txHex transaction hex
     * @param txKey transaction key
     * @param keyImages expected key images of inputs, ignored if null
     * @return the verified tx
     */
    public MoneroTx verifyTradeTx(String offerId, BigInteger tradeFeeAmount, String feeAddress, BigInteger sendAmount, String sendAddress, String txHash, String txHex, String txKey, List<String> keyImages) {
        if (txHash == null) throw new IllegalArgumentException("Cannot verify trade tx with null id");
        MoneroDaemonRpc monerod = getMonerod();
        MoneroTx tx = null;
        synchronized (lock) {
            try {

                // verify tx not submitted to pool
                tx = monerod.getTx(txHash);
                if (tx != null) throw new RuntimeException("Tx is already submitted");

                // submit tx to pool
                MoneroSubmitTxResult result = monerod.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
                if (!result.isGood()) throw new RuntimeException("Failed to submit tx to daemon: " + JsonUtils.serialize(result));

                // get pool tx which has weight and size
                for (MoneroTx poolTx : monerod.getTxPool()) if (poolTx.getHash().equals(txHash)) tx = poolTx;
                if (tx == null) throw new RuntimeException("Tx is not in pool after being submitted");

                // verify key images
                if (keyImages != null) {
                    Set<String> txKeyImages = new HashSet<String>();
                    for (MoneroOutput input : tx.getInputs()) txKeyImages.add(input.getKeyImage().getHex());
                    if (!txKeyImages.equals(new HashSet<String>(keyImages))) throw new RuntimeException("Tx inputs do not match claimed key images");
                }

                // verify unlock height
                if (!BigInteger.ZERO.equals(tx.getUnlockTime())) throw new RuntimeException("Unlock height must be 0");

                // verify miner fee
                BigInteger minerFeeEstimate = getFeeEstimate(tx.getWeight());
                HavenoUtils.verifyMinerFee(minerFeeEstimate, tx.getFee());
                log.info("Trade miner fee {} is within tolerance", tx.getFee());

                // verify proof to fee address
                BigInteger actualTradeFee = BigInteger.ZERO;
                if (tradeFeeAmount.compareTo(BigInteger.ZERO) > 0) {
                    MoneroCheckTx tradeFeeCheck = checkTxKey(txHash, txKey, feeAddress);
                    if (!tradeFeeCheck.isGood()) throw new RuntimeException("Invalid proof to trade fee address");
                    actualTradeFee = tradeFeeCheck.getReceivedAmount();
                }

                // verify proof to transfer address
                MoneroCheckTx transferCheck = checkTxKey(txHash, txKey, sendAddress);
                if (!transferCheck.isGood()) throw new RuntimeException("Invalid proof to transfer address");
                BigInteger actualSendAmount = transferCheck.getReceivedAmount();

                // verify trade fee amount
                if (!actualTradeFee.equals(tradeFeeAmount)) {
                    if (equalsWithinFractionError(actualTradeFee, tradeFeeAmount)) {
                        log.warn("Trade fee amount is within fraction error, expected " + tradeFeeAmount + " but was " + actualTradeFee);
                    } else {
                        throw new RuntimeException("Invalid trade fee amount, expected " + tradeFeeAmount + " but was " + actualTradeFee);
                    }
                }

                // verify send amount
                BigInteger expectedSendAmount = sendAmount.subtract(tx.getFee());
                if (!actualSendAmount.equals(expectedSendAmount)) {
                    if (equalsWithinFractionError(actualSendAmount, expectedSendAmount)) {
                        log.warn("Trade tx send amount is within fraction error, expected " + expectedSendAmount + " but was " + actualSendAmount + " with tx fee " + tx.getFee());
                    } else {
                        throw new RuntimeException("Invalid send amount, expected " + expectedSendAmount + " but was " + actualSendAmount + " with tx fee " + tx.getFee());
                    }
                }
                return tx;
            } catch (Exception e) {
                log.warn("Error verifying trade tx with offer id=" + offerId + (tx == null ? "" : ", tx=\n" + tx) + ": " + e.getMessage());
                throw e;
            } finally {
                try {
                    monerod.flushTxPool(txHash); // flush tx from pool
                } catch (MoneroRpcError err) {
                    System.out.println(monerod.getRpcConnection());
                    throw err.getCode().equals(-32601) ? new RuntimeException("Failed to flush tx from pool. Arbitrator must use trusted, unrestricted daemon") : err;
                }
            }
        }
    }

    // check tx key with bounded retries since the request can fail transiently under load
    private MoneroCheckTx checkTxKey(String txHash, String txKey, String address) {
        int numAttempts = 0;
        while (true) {
            try {
                return getWallet().checkTxKey(txHash, txKey, address);
            } catch (Exception e) {
                if (++numAttempts >= NUM_CHECK_TX_KEY_ATTEMPTS) throw e;
                log.warn("Error checking tx key, attempt={}/{}, txHash={}: {}", numAttempts, NUM_CHECK_TX_KEY_ATTEMPTS, txHash, e.getMessage());
                HavenoUtils.waitFor(CHECK_TX_KEY_ATTEMPT_DELAY_MS);
            }
        }
    }

    // TODO: old bug in atomic unit conversion could cause fractional difference error, remove this in future release, maybe re-sign all offers then
    private static boolean equalsWithinFractionError(BigInteger a, BigInteger b) {
        return a.subtract(b).abs().compareTo(new BigInteger("1")) <= 0;
    }

    /**
     * Get the tx fee estimate based on its weight.
     *
     * @param txWeight - the tx weight
     * @return the tx fee estimate
     */
    public BigInteger getFeeEstimate(long txWeight) {

        // get fee priority
        MoneroTxPriority priority;
        if (PROTOCOL_FEE_PRIORITY == MoneroTxPriority.DEFAULT) {
            priority = wallet.getDefaultFeePriority();
        } else {
            priority = PROTOCOL_FEE_PRIORITY;
        }

        // fall back to lowest priority if wallet cannot determine, e.g. on internal daemon error
        if (priority == MoneroTxPriority.DEFAULT) {
            log.warn("Wallet returned default fee priority, falling back to {}", MoneroTxPriority.UNIMPORTANT);
            priority = MoneroTxPriority.UNIMPORTANT;
        }

        // get fee estimates per kB from daemon
        MoneroFeeEstimate feeEstimates = getMonerod().getFeeEstimate();
        BigInteger baseFeeEstimate = feeEstimates.getFees().get(priority.ordinal() - 1);
        BigInteger qmask = feeEstimates.getQuantizationMask();
        log.info("Monero base fee estimate={}, qmask={}", baseFeeEstimate, qmask);

        // get tx base fee
        BigInteger baseFee = baseFeeEstimate.multiply(BigInteger.valueOf(txWeight));

        // round up to multiple of quantization mask
        BigInteger[] quotientAndRemainder = baseFee.divideAndRemainder(qmask);
        BigInteger feeEstimate = qmask.multiply(quotientAndRemainder[0]);
        if (quotientAndRemainder[1].compareTo(BigInteger.ZERO) > 0) feeEstimate = feeEstimate.add(qmask);
        return feeEstimate;
    }

    public void onShutDownStarted() {
        log.info("XmrWalletService.onShutDownStarted()");
        this.isShutDownStarted = true;
        MONERO_WALLET_RPC_MANAGER.onShutDownStarted();
    }

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());

        // create task to shut down
        Runnable shutDownTask = () -> {

            // close main wallet, force close if syncing
            if (isSyncing()) forceCloseMainWallet();
            else {
                try {
                    closeMainWallet(true);
                } catch (Exception e) {
                    log.warn("Error closing main wallet: {}. Was Haveno stopped manually with ctrl+c?", e.getMessage());
                }
            }
        };

        // shut down with timeout
        try {
            ThreadUtils.awaitTask(shutDownTask, SHUTDOWN_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("Error shutting down {}: {}\n", getClass().getSimpleName(), e.getMessage(), e);

            // force close wallet
            forceCloseMainWallet();
        }

        log.info("Done shutting down {}", getClass().getSimpleName());
    }

    // -------------------------- ADDRESS ENTRIES -----------------------------

    public XmrAddressEntry getNewAddressEntry() {
        return getNewAddressEntryAux(null, XmrAddressEntry.Context.AVAILABLE);
    }

    public XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {
        synchronized (walletLock) { // wallet lock first, since a new subaddress may be created
            synchronized (addressEntryLock) {

                // try to use available and not yet used entries
                try {
                    List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries();
                    if (!unusedAddressEntries.isEmpty()) return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(unusedAddressEntries.get(0), context, offerId);
                } catch (Exception e) {
                    log.warn("Error getting new address entry based on incoming transactions: {}\n", e.getMessage(), e);
                }

                // create new entry
                return getNewAddressEntryAux(offerId, context);
            }
        }
    }

    private XmrAddressEntry getNewAddressEntryAux(String offerId, XmrAddressEntry.Context context) {
        synchronized (walletLock) {
            synchronized (addressEntryLock) {
                MoneroSubaddress subaddress = wallet.createSubaddress(0);
                XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), context, offerId, null);
                log.info("Add new XmrAddressEntry {}", entry);
                xmrAddressEntryList.addAddressEntry(entry);
                return entry;
            }
        }
    }

    public XmrAddressEntry getFreshAddressEntry() {
        synchronized (walletLock) { // wallet lock first, since a new subaddress may be created
            synchronized (addressEntryLock) {
                List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries();
                if (unusedAddressEntries.isEmpty()) return getNewAddressEntry();
                else return unusedAddressEntries.get(0);
            }
        }
    }

    public XmrAddressEntry recoverAddressEntry(String offerId, String address, XmrAddressEntry.Context context) {
        synchronized (addressEntryLock) {
            var available = findAddressEntry(address, XmrAddressEntry.Context.AVAILABLE);
            if (!available.isPresent()) return null;
            return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(available.get(), context, offerId);
        }
    }

    public XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        if (addressEntry.isPresent()) return addressEntry.get();
        synchronized (walletLock) { // wallet lock first, since a new subaddress may be created
            synchronized (addressEntryLock) {
                addressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
                if (addressEntry.isPresent()) return addressEntry.get();
                else return getNewAddressEntry(offerId, context);
            }
        }
    }

    public Optional<XmrAddressEntry> getAddressEntry(String offerId, XmrAddressEntry.Context context) {
        synchronized (addressEntryLock) {
            List<XmrAddressEntry> entries = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).collect(Collectors.toList());
            if (entries.size() > 1) throw new RuntimeException("Multiple address entries exist with offer ID " + offerId + " and context " + context + ". That should never happen.");
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
        }
    }

    public void swapAddressEntryToAvailable(String offerId, XmrAddressEntry.Context context) {
        synchronized (addressEntryLock) {
            Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
            addressEntryOptional.ifPresent(e -> {
                xmrAddressEntryList.swapToAvailable(e);
                saveAddressEntryList();
            });
        }
    }

    public void cloneAddressEntries(String offerId, String cloneOfferId) {
        synchronized (addressEntryLock) {
            List<XmrAddressEntry> entries = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).collect(Collectors.toList());
            for (XmrAddressEntry entry : entries) {
                XmrAddressEntry clonedEntry = new XmrAddressEntry(entry.getSubaddressIndex(), entry.getAddressString(), entry.getContext(), cloneOfferId, null);
                Optional<XmrAddressEntry> existingEntry = getAddressEntry(clonedEntry.getOfferId(), clonedEntry.getContext());
                if (existingEntry.isPresent()) continue;
                xmrAddressEntryList.addAddressEntry(clonedEntry);
            }
        }
    }

    public void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);

        // skip if failed trade is scheduled for processing // TODO: do not call this function in this case?
        if (HavenoUtils.tradeManager.hasFailedScheduledTrade(offerId)) {
            log.warn("Refusing to reset address entries because trade is scheduled for deletion with offerId={}", offerId);
            return;
        }

        swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.OFFER_FUNDING);

        // swap trade payout to available if applicable
        if (HavenoUtils.tradeManager == null) return;
        Trade trade = HavenoUtils.tradeManager.getTrade(offerId);
        if (trade == null || trade.isPayoutFinalized()) swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    public void swapPayoutAddressEntryToAvailable(String offerId) {
        swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    private Optional<XmrAddressEntry> findAddressEntry(String address, XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(e -> address.equals(e.getAddressString())).filter(e -> context == e.getContext()).findAny();
    }

    public List<XmrAddressEntry> getAddressEntries() {
        return getAddressEntryListAsImmutableList().stream().collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAvailableAddressEntries() {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> XmrAddressEntry.Context.AVAILABLE == addressEntry.getContext()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntriesForOpenOffer() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> XmrAddressEntry.Context.OFFER_FUNDING == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntriesForTrade() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> XmrAddressEntry.Context.TRADE_PAYOUT == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntries(XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> context == addressEntry.getContext()).collect(Collectors.toList());
    }

    public XmrAddressEntry getBaseAddressEntry() {
        return getAddressEntryListAsImmutableList().stream().filter(e -> e.getContext() == XmrAddressEntry.Context.BASE_ADDRESS).findAny().orElse(null);
    }

    public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
        return getAvailableAddressEntries().stream().filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.ZERO) > 0).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
        for (MoneroSubaddress subaddress : cachedSubaddresses) {
            boolean exists = xmrAddressEntryList.getAddressEntriesAsListImmutable().stream().filter(addressEntry -> addressEntry.getAddressString().equals(subaddress.getAddress())).findAny().isPresent();
            if (!exists) {
                XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), subaddress.getIndex() == 0 ? XmrAddressEntry.Context.BASE_ADDRESS : XmrAddressEntry.Context.AVAILABLE, null, null);
                xmrAddressEntryList.addAddressEntry(entry);
            }
        }
        return xmrAddressEntryList.getAddressEntriesAsListImmutable();
    }

    public List<XmrAddressEntry> getUnusedAddressEntries() {
        return getAvailableAddressEntries().stream()
                .filter(e -> e.getContext() == XmrAddressEntry.Context.AVAILABLE && !subaddressHasIncomingTransfers(e.getSubaddressIndex()))
                .collect(Collectors.toList());
    }

    public boolean subaddressHasIncomingTransfers(int subaddressIndex) {
        return getNumOutputsForSubaddress(subaddressIndex) > 0;
    }

    public int getNumOutputsForSubaddress(int subaddressIndex) {
        int numUnspentOutputs = 0;
        for (MoneroTxWallet tx : cachedTxs) {
            //if (tx.getTransfers(new MoneroTransferQuery().setSubaddressIndex(subaddressIndex)).isEmpty()) continue; // TODO monero-project: transfers are occluded by transfers from/to same account, so this will return unused when used
            numUnspentOutputs += tx.getOutputsWallet(new MoneroOutputQuery().setAccountIndex(0).setSubaddressIndex(subaddressIndex)).size(); // TODO: monero-project does not provide outputs for unconfirmed txs
        }
        boolean positiveBalance = getBalanceForSubaddress(subaddressIndex).compareTo(BigInteger.ZERO) > 0;
        if (positiveBalance && numUnspentOutputs == 0) return 1; // outputs do not appear until confirmed and internal transfers are occluded, so report 1 if positive balance
        return numUnspentOutputs;
    }

    private MoneroSubaddress getSubaddress(int subaddressIndex) {
        for (MoneroSubaddress subaddress : cachedSubaddresses) {
            if (subaddress.getIndex() == subaddressIndex) return subaddress;
        }
        return null;
    }

    public int getNumTxsWithIncomingOutputs(int subaddressIndex) {
        List<MoneroTxWallet> txsWithIncomingOutputs = getTxsWithIncomingOutputs(subaddressIndex);
        if (txsWithIncomingOutputs.isEmpty() && subaddressHasIncomingTransfers(subaddressIndex)) return 1; // outputs do not appear until confirmed and internal transfers are occluded, so report 1 if positive balance
        return txsWithIncomingOutputs.size();
    }

    public List<MoneroTxWallet> getTxsWithIncomingOutputs() {
        return getTxsWithIncomingOutputs(null);
    }

    public List<MoneroTxWallet> getTxsWithIncomingOutputs(Integer subaddressIndex) {
        List<MoneroTxWallet> incomingTxs = new ArrayList<>();
        for (MoneroTxWallet tx : cachedTxs) {
            boolean isIncoming = false;
            if (tx.getIncomingTransfers() != null) {
                for (MoneroIncomingTransfer transfer : tx.getIncomingTransfers()) {
                    if (transfer.getAccountIndex().equals(0) && (subaddressIndex == null || transfer.getSubaddressIndex().equals(subaddressIndex))) {
                        isIncoming = true;
                        break;
                    }
                }
            }
            if (tx.getOutputs() != null && !isIncoming) {
                for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                    if (output.getAccountIndex().equals(0) && (subaddressIndex == null || output.getSubaddressIndex().equals(subaddressIndex))) {
                        isIncoming = true;
                        break;
                    }
                }
            }
            if (isIncoming) incomingTxs.add(tx);
        }
        return incomingTxs;
    }

    public BigInteger getBalanceForAddress(String address) {
        return getBalanceForSubaddress(wallet.getAddressIndex(address).getIndex());
    }

    public BigInteger getBalanceForSubaddress(int subaddressIndex) {
        MoneroSubaddress subaddress = getSubaddress(subaddressIndex);
        return subaddress == null ? BigInteger.ZERO : subaddress.getBalance();
    }

    public BigInteger getBalanceForSubaddress(int subaddressIndex, boolean includeFrozen) {
        return getBalanceForSubaddress(subaddressIndex).add(includeFrozen ? getFrozenBalanceForSubaddress(subaddressIndex) : BigInteger.ZERO);
    }

    public BigInteger getFrozenBalanceForSubaddress(int subaddressIndex) {
        List<MoneroOutputWallet> outputs = getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false).setAccountIndex(0).setSubaddressIndex(subaddressIndex));
        return outputs.stream().map(output -> output.getAmount()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    public BigInteger getAvailableBalanceForSubaddress(int subaddressIndex) {
        MoneroSubaddress subaddress = getSubaddress(subaddressIndex);
        return subaddress == null ? BigInteger.ZERO : subaddress.getUnlockedBalance();
    }

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> available = getFundedAvailableAddressEntries().stream();
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream().filter(entry -> !HavenoUtils.tradeManager.getOpenOfferManager().getOpenOffer(entry.getOfferId()).isPresent()));
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream().filter(entry -> HavenoUtils.tradeManager.getTrade(entry.getOfferId()) == null || HavenoUtils.tradeManager.getTrade(entry.getOfferId()).isPayoutFinalized()));
        return available.filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.ZERO) > 0);
    }

    public void addWalletListener(MoneroWalletListenerI listener) {
        synchronized (walletListeners) {
            walletListeners.add(listener);
        }
    }

    public void removeWalletListener(MoneroWalletListenerI listener) {
        synchronized (walletListeners) {
            if (!walletListeners.contains(listener)) throw new RuntimeException("Listener is not registered with wallet");
            walletListeners.remove(listener);
        }
    }

    // TODO (woodser): update balance and other listening
    public void addBalanceListener(XmrBalanceListener listener) {
        if (listener == null) throw new IllegalArgumentException("Cannot add null balance listener");
        synchronized (balanceListeners) {
            if (!balanceListeners.contains(listener)) balanceListeners.add(listener);
        }
    }

    public void removeBalanceListener(XmrBalanceListener listener) {
        if (listener == null) throw new IllegalArgumentException("Cannot add null balance listener");
        synchronized (balanceListeners) {
            balanceListeners.remove(listener);
        }
    }

    public void updateBalanceListeners() {
        synchronized (walletLock) {
            BigInteger availableBalance = getAvailableBalance();
            synchronized (balanceListeners) {
                for (XmrBalanceListener balanceListener : balanceListeners) {
                    BigInteger balance;
                    if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
                    else balance = availableBalance;
                    try {
                        balanceListener.onBalanceChanged(balance);
                    } catch (Exception e) {
                        log.warn("Failed to notify balance listener of change: {}\n", e.getMessage(), e);
                    }
                }
            }
        }
    }

    public void saveAddressEntryList() {
        xmrAddressEntryList.requestPersistence();
    }

    public long getHeight() {
        return walletHeight.get();
    }

    public String getSeed() {
        synchronized (walletLock) {
            return wallet.getSeed();
        }
    }

    public String getPrimaryAddress() {
        synchronized (walletLock) {
            return wallet.getPrimaryAddress();
        }
    }

    public String getWalletAsString(boolean includePrivKeys) {
        synchronized (walletLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("Primary address: ").append(wallet.getPrimaryAddress()).append("\n");
            sb.append("Wallet height: ").append(getHeight()).append("\n");
            sb.append("Public view key: ").append(wallet.getPublicViewKey()).append("\n");
            sb.append("Public spend key: ").append(wallet.getPublicSpendKey()).append("\n");
            if (includePrivKeys) {
                sb.append("Seed: ").append(wallet.getSeed()).append("\n");
                sb.append("Private view key: ").append(wallet.getPrivateViewKey()).append("\n");
                sb.append("Private spend key: ").append(wallet.getPrivateSpendKey()).append("\n");
            }
            return sb.toString();
        }
    }

    public List<MoneroTxWallet> getTxs(boolean includeFailed) {
        List<MoneroTxWallet> txs = getTxs();
        if (includeFailed) return txs;
        return txs.stream().filter(tx -> !tx.isFailed()).collect(Collectors.toList());
    }

    public List<MoneroTxWallet> getTxs() {
        return getTxs(new MoneroTxQuery().setIncludeOutputs(true));
    }

    public List<MoneroTxWallet> getTxs(MoneroTxQuery query) {
        if (cachedTxs == null) {
            log.warn("Transactions not cached, fetching from wallet");
            cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true)); // fetches from pool
        }
        return cachedTxs.stream().filter(tx -> query.meetsCriteria(tx)).collect(Collectors.toList());
    }

    public List<MoneroTxWallet> getTxs(List<String> txIds) {
        return getTxs(new MoneroTxQuery().setIncludeOutputs(true).setHashes(txIds));
    }

    public MoneroTxWallet getTx(String txId) {
        List<MoneroTxWallet> txs = getTxs(new MoneroTxQuery().setIncludeOutputs(true).setHash(txId));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public BigInteger getBalance() {
        return cachedBalance;
    }

    public BigInteger getAvailableBalance() {
        return cachedAvailableBalance;
    }

    public boolean hasAddress(String address) {
        for (MoneroSubaddress subaddress : getSubaddresses()) {
            if (subaddress.getAddress().equals(address)) return true;
        }
        return false;
    }

    public List<MoneroSubaddress> getSubaddresses() {
        return cachedSubaddresses;
    }

    public BigInteger getAmountSentToSelf(MoneroTxWallet tx) {
        BigInteger sentToSelfAmount = BigInteger.ZERO;
        if (tx.getOutgoingTransfer() != null && tx.getOutgoingTransfer().getDestinations() != null) {
            for (MoneroDestination destination : tx.getOutgoingTransfer().getDestinations()) {
                if (hasAddress(destination.getAddress())) {
                    sentToSelfAmount = sentToSelfAmount.add(destination.getAmount());
                }
            }
        }
        return sentToSelfAmount;
    }

    public List<MoneroOutputWallet> getOutputs(MoneroOutputQuery query) {
        List<MoneroOutputWallet> filteredOutputs = new ArrayList<MoneroOutputWallet>();
        for (MoneroOutputWallet output : cachedOutputs) {
            if (query == null || query.meetsCriteria(output)) filteredOutputs.add(output);
        }
        return filteredOutputs;
    }

    public List<MoneroOutputWallet> getOutputs(Collection<String> keyImages) {
        List<MoneroOutputWallet> outputs = new ArrayList<MoneroOutputWallet>();
        for (String keyImage : keyImages) {
            List<MoneroOutputWallet> outputList = getOutputs(new MoneroOutputQuery().setIsSpent(false).setKeyImage(new MoneroKeyImage(keyImage)));
            if (!outputList.isEmpty()) outputs.add(outputList.get(0));
        }
        return outputs;
    }

    public BigInteger getOutputsAmount(Collection<String> keyImages) {
        return getOutputs(keyImages).stream().map(output -> output.getAmount()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Util
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static MoneroNetworkType getMoneroNetworkType() {
        switch (Config.baseCurrencyNetwork()) {
        case XMR_LOCAL:
            return MoneroNetworkType.TESTNET;
        case XMR_STAGENET:
            return MoneroNetworkType.STAGENET;
        case XMR_MAINNET:
            return MoneroNetworkType.MAINNET;
        default:
            throw new RuntimeException("Unhandled base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    public static void printTxs(String tracePrefix, MoneroTxWallet... txs) {
        StringBuilder sb = new StringBuilder();
        for (MoneroTxWallet tx : txs) sb.append('\n' + tx.toString());
        log.info("\n" + tracePrefix + ":" + sb.toString());
    }

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void initialize() {

        // try to load native monero library
        if (isUseNativeXmrWallet() && !MoneroUtils.isNativeLibraryLoaded()) {
            try {
                MoneroUtils.loadNativeLibrary();
            } catch (Exception | UnsatisfiedLinkError e) {
                log.warn("Failed to load Monero native libraries: " + e.getMessage());
            }
        }
        String appliedMsg = "Monero native libraries applied: " + isNativeLibraryApplied();
        if (isUseNativeXmrWallet() && !isNativeLibraryApplied()) log.warn(appliedMsg);
        else log.info(appliedMsg);

        // listen for connection changes
        xmrConnectionService.addConnectionListener(connection -> {

            // skip default handling if processing a synchronous connection switch (this is assumed to be called on same thread as requester)
            if (isProcessingRequestConnectionSwitchSynchronous) return;
            
            // process off thread; notifier can hold a daemon lock, so taking walletLock here could deadlock
            ThreadUtils.submitToPool(() -> {
                if (wasWalletSynced && !isSyncing()) {
                    onConnectionChanged(connection);
                } else {

                    // check if ignored
                    if (wallet == null || isShutDownStarted) return;
                    if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) {
                        updatePollPeriod();
                        return;
                    }

                    // force restart main wallet if connection changed while syncing
                    if (isSyncing()) {
                        log.warn("Force restarting main wallet because connection changed while syncing");
                        forceRestartMainWallet();
                    }
                }
            });
        });

        // initialize main wallet when daemon synced
        walletInitListener = (obs, oldVal, newVal) -> initMainWalletIfConnected();
        xmrConnectionService.downloadPercentageProperty().addListener(walletInitListener);
        initMainWalletIfConnected();
    }

    private void startWalletHeightMonitor() {
        synchronized (WALLET_HEIGHT_MONITOR_LOCK) {
            if (walletHeightMonitorTimer != null) walletHeightMonitorTimer.stop();
            walletHeightMonitorTimer = UserThread.runPeriodically(() -> {
                ThreadUtils.execute(() -> {
                    try {
                        if (System.currentTimeMillis() - lastWalletHeightMonitorUpdate >= WALLET_HEIGHT_MONITOR_PERIOD_SEC * 1000) {
                            log.warn("Requesting connection change because main wallet height has not updated in over {} minutes", (double) WALLET_HEIGHT_MONITOR_PERIOD_SEC / (double) 60);
                            requestConnectionSwitchSynchronous(null);
                            lastWalletHeightMonitorUpdate = System.currentTimeMillis();
                        }
                    } catch (Throwable t) {
                        log.warn("Error in wallet height monitor: {}\n", t.getMessage(), t);
                    }
                }, THREAD_ID);
            }, WALLET_HEIGHT_MONITOR_PERIOD_SEC);
        }
    }

    private void initMainWalletIfConnected() {
        if (wallet == null && xmrConnectionService.downloadPercentageProperty().get() == 1 && !isShutDownStarted) {
            requestInitMainWallet();
        }
    }

    private void requestInitMainWallet() {
        ThreadUtils.submitToPool(() -> {
            try {
                initMainWallet();
            } catch (Exception e) {
                if (isShutDownStarted) return;
                log.warn("Error initializing main wallet: {}\n", e.getMessage(), e);
                HavenoUtils.setTopError(e.getMessage());
                throw e;
            }
        });
    }

    private void initMainWallet() {
        synchronized (walletLock) {
            if (isShutDownStarted) return;
            if (wallet != null && isPolling()) return;

            // open or create main wallet
            for (int i = 0; i < MAX_SYNC_ATTEMPTS; i++) {
                try {
                    openOrCreateMainWallet();
                    break;
                } catch (Exception e) {
                    if (isShutDownStarted) return;
                    log.warn("Error opening or creating main wallet, attempt={}/{}: {}", i + 1, MAX_SYNC_ATTEMPTS, e.getMessage());
                    if (i + 1 >= MAX_SYNC_ATTEMPTS) {
                        log.warn("Failed to open or create main wallet after {} attempts: {}", MAX_SYNC_ATTEMPTS, e.getMessage());
                        throw e;
                    } else {
                        HavenoUtils.waitFor(INIT_WALLET_DELAY_MS); // wait before retrying
                    }
                }
            }

            // stop recursion if already initializing
            if (isInitializingWallet) return;
            isInitializingWallet = true;

            // try to sync wallet on startup, otherwise start polling
            try {
                if (isWalletServiceInitialized()) {
                    startPolling();
                } else {

                    // repeatedly attempt to sync wallet on startup, otherwise open application
                    long initialSyncTimeoutMs = getInitialSyncTimeoutMs();
                    for (int i = 0; i < MAX_SYNC_ATTEMPTS; i++) {
                        try {
                            doPollWallet(initialSyncTimeoutMs);
                            break;
                        } catch (Exception e) { // error is logged when polling
                            if (isShutDownStarted) return;
                            if (i + 1 >= MAX_SYNC_ATTEMPTS) {
                                log.warn("Opening application without syncing main wallet after {} attempts, last error: {}", MAX_SYNC_ATTEMPTS, e.getMessage());
                                HavenoUtils.setTopError("Could not sync main wallet on startup.\n\nError: " + e.getMessage());
                                UserThread.execute(() -> onWalletServiceInitialized());
                            } else {
                                initialSyncTimeoutMs = Math.min(XmrWalletBase.SYNC_TIMEOUT_MS, initialSyncTimeoutMs * 2);
                                log.warn("Retrying to sync main wallet on startup in {}s, attempt={}/{}, last error: {}", INIT_WALLET_DELAY_MS / 1000, i + 2, MAX_SYNC_ATTEMPTS, e.getMessage());
                                HavenoUtils.waitFor(INIT_WALLET_DELAY_MS); // wait before retrying
                            }
                        }
                    }

                    // start polling wallet
                    startPolling(true); // skip first poll because we already polled
                }
            } finally {
                isInitializingWallet = false;
            }
        }
    }

    private void resetIfWalletChanged() {
        getAddressEntryListAsImmutableList(); // TODO: using getter to create base address if necessary
        List<XmrAddressEntry> baseAddresses = getAddressEntries(XmrAddressEntry.Context.BASE_ADDRESS);
        if (baseAddresses.size() > 1 || (baseAddresses.size() == 1 && !baseAddresses.get(0).getAddressString().equals(wallet.getPrimaryAddress()))) {
            String warningMsg = "New Monero wallet detected. Resetting internal state.";
            if (!HavenoUtils.tradeManager.getOpenTrades().isEmpty()) warningMsg += "\n\nWARNING: Your open trades will settle to the payout address in the OLD wallet!"; // TODO: allow payout address to be updated in PaymentSentMessage, PaymentReceivedMessage, and DisputeOpenedMessage?
            HavenoUtils.setTopError(warningMsg);

            // reset address entries
            xmrAddressEntryList.clear();
            getAddressEntryListAsImmutableList(); // recreate base address

            // cancel offers
            HavenoUtils.tradeManager.getOpenOfferManager().removeAllOpenOffers(null);
        }
    }

    private MoneroWalletFull createWalletFull(MoneroWalletConfig config, boolean applyProxyUri) {
        awaitPendingWalletClose(config.getPath());

        // must be connected to daemon
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletFull walletFull = null;
        try {

            // configure wallet connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            xmrConnectionService.applyWalletProxyUri(connection, getWalletName(config.getPath()), applyProxyUri);

            // create wallet
            log.debug("Creating full wallet '{}' with monerod={}, proxyUri={}", Utilities.redactSensitiveInfo(config.getPath()), connection.getUri(), connection.getProxyUri());
            long time = System.currentTimeMillis();
            config.setServer(connection);
            walletFull = MoneroWalletFull.createWallet(config);
            walletFull.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done creating full wallet " + Utilities.redactSensitiveInfo(config.getPath()) + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletFull;
        } catch (Exception e) {
            String errorMsg = "Could not create wallet '" + Utilities.redactSensitiveInfo(config.getPath()) + "': " + e.getMessage();
            log.warn(errorMsg + "\n", e);
            if (walletFull != null) forceCloseWallet(walletFull, config.getPath());
            throw new WalletUnavailableException(errorMsg, e);
        }
    }

    private MoneroWalletFull openWalletFull(MoneroWalletConfig config, boolean applyProxyUri) {
        awaitPendingWalletClose(config.getPath());
        MoneroWalletFull walletFull = null;
        try {

            // configure wallet connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            xmrConnectionService.applyWalletProxyUri(connection, getWalletName(config.getPath()), applyProxyUri);

            // try opening wallet
            config.setNetworkType(getMoneroNetworkType());
            config.setServer(connection);
            log.debug("Opening full wallet '{}' with monerod={}, proxyUri={}", Utilities.redactSensitiveInfo(config.getPath()), connection.getUri(), connection.getProxyUri());
            try {
                walletFull = MoneroWalletFull.openWallet(config);
            } catch (Exception e) {
                if (isShutDownStarted) throw e;
                log.warn("Failed to open full wallet '{}', attempting to use backup cache files, error={}", Utilities.redactSensitiveInfo(config.getPath()), e.getMessage());
                boolean retrySuccessful = false;
                try {
                    
                    // rename wallet cache to backup
                    String cachePath = walletDir.toString() + File.separator + getWalletName(config.getPath());
                    File originalCacheFile = new File(cachePath);
                    if (originalCacheFile.exists()) originalCacheFile.renameTo(new File(cachePath + ".backup"));

                    // try opening wallet with backup cache files in descending order
                    List<File> backupCacheFiles = FileUtil.getBackupFiles(walletDir, getWalletName(config.getPath()));
                    Collections.reverse(backupCacheFiles);
                    for (File backupCacheFile : backupCacheFiles) {
                        try {
                            FileUtil.copyFile(backupCacheFile, new File(cachePath));
                            walletFull = MoneroWalletFull.openWallet(config);
                            log.warn("Successfully opened full wallet using backup cache");
                            retrySuccessful = true;
                            break;
                        } catch (Exception e2) {

                            // delete cache file if failed to open
                            File cacheFile = new File(cachePath);
                            if (cacheFile.exists()) cacheFile.delete();
                            File unportableCacheFile = new File(cachePath + ".unportable");
                            if (unportableCacheFile.exists()) unportableCacheFile.delete();
                        }
                    }

                    // handle success or failure
                    File originalCacheBackup = new File(cachePath + ".backup");
                    if (retrySuccessful) {
                        if (originalCacheBackup.exists()) originalCacheBackup.delete(); // delete original wallet cache backup
                    } else {

                        // retry opening wallet after cache deleted
                        try {
                            log.warn("Failed to open full wallet '{}' using backup cache files, retrying with cache deleted", Utilities.redactSensitiveInfo(config.getPath()));
                            walletFull = MoneroWalletFull.openWallet(config);
                            log.warn("Successfully opened full wallet after cache deleted");
                            retrySuccessful = true;
                        } catch (Exception e2) {
                            // ignore
                        }

                        // handle success or failure
                        if (retrySuccessful) {
                            if (originalCacheBackup.exists()) originalCacheBackup.delete(); // delete original wallet cache backup
                        } else {
    
                            // restore original wallet cache
                            log.warn("Failed to open full wallet '{}' after deleting cache, restoring original cache", Utilities.redactSensitiveInfo(config.getPath()));
                            File cacheFile = new File(cachePath);
                            if (cacheFile.exists()) cacheFile.delete();
                            if (originalCacheBackup.exists()) originalCacheBackup.renameTo(new File(cachePath));
    
                            // throw original exception
                            throw e;
                        }
                    }
                } catch (Exception e2) {
                    throw e; // throw original exception
                }
            }
            if (walletFull.getDaemonConnection() != null) walletFull.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.debug("Done opening full wallet " + Utilities.redactSensitiveInfo(config.getPath()));
            return walletFull;
        } catch (Exception e) {
            String errorMsg = "Could not open full wallet '" + Utilities.redactSensitiveInfo(config.getPath()) + "': " + e.getMessage();
            log.warn(errorMsg + "\n", e);
            if (walletFull != null) forceCloseWallet(walletFull, config.getPath());
            throw new WalletUnavailableException(errorMsg, e);
        }
    }

    private MoneroWalletRpc createWalletRpc(MoneroWalletConfig config, Integer port, boolean applyProxyUri, boolean trustDaemon) {

        // must be connected to daemon
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletRpc walletRpc = null;
        try {

            // get daemon connection
            MoneroRpcConnection serviceConnection = xmrConnectionService.getConnection();
            if (serviceConnection == null) throw new IllegalStateException("Cannot create wallet '" + config.getPath() + "' via RPC because daemon connection is null");

            // configure wallet connection
            MoneroRpcConnection connection = new MoneroRpcConnection(serviceConnection);
            xmrConnectionService.applyWalletProxyUri(connection, getWalletName(config.getPath()), applyProxyUri);

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, connection);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // create wallet
            if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet '" + config.getPath() + "' because shutdown is started");
            log.info("Creating RPC wallet '{}' with monerod={}, proxyUri={}", config.getPath(), connection.getUri(), connection.getProxyUri());
            long time = System.currentTimeMillis();
            walletRpc.createWallet(config);
            setDaemonConnection(walletRpc, connection, trustDaemon);
            walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done creating RPC wallet " + config.getPath() + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletRpc;
        } catch (Exception e) {
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            if (!isShutDownStarted) log.warn("Could not create RPC wallet '" + config.getPath() + "': " + e.getMessage() + "\n", e);
            throw new WalletUnavailableException("Could not create wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes in your task manager, and restart Haveno.\n\nError message: " + e.getMessage(), e);
        }
    }

    private MoneroWalletRpc openWalletRpc(MoneroWalletConfig config, Integer port, boolean applyProxyUri, boolean trustDaemon) {
        MoneroWalletRpc walletRpc = null;
        try {

            // get daemon connection from service
            MoneroRpcConnection serviceConnection = xmrConnectionService.getConnection();
            if (serviceConnection == null) throw new IllegalStateException("Cannot open wallet '" + config.getPath() + "' via RPC because daemon connection is null");

            // configure wallet connection
            MoneroRpcConnection connection = new MoneroRpcConnection(serviceConnection);
            xmrConnectionService.applyWalletProxyUri(connection, getWalletName(config.getPath()), applyProxyUri);

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, connection);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // try opening wallet
            if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet '" + config.getPath() + "' because shutdown is started");
            log.debug("Opening RPC wallet '{}' with monerod={}, proxyUri={}", config.getPath(), connection.getUri(), connection.getProxyUri());
            try {
                walletRpc.openWallet(config);
            } catch (Exception e) {
                if (isShutDownStarted) throw e;
                log.warn("Failed to open RPC wallet '{}', attempting to use backup cache files, error={}", config.getPath(), e.getMessage());
                boolean retrySuccessful = false;
                try {
                    
                    // rename wallet cache to backup
                    String cachePath = walletDir.toString() + File.separator + config.getPath();
                    File originalCacheFile = new File(cachePath);
                    if (originalCacheFile.exists()) originalCacheFile.renameTo(new File(cachePath + ".backup"));

                    // try opening wallet with backup cache files in descending order
                    List<File> backupCacheFiles = FileUtil.getBackupFiles(walletDir, config.getPath());
                    Collections.reverse(backupCacheFiles);
                    for (File backupCacheFile : backupCacheFiles) {
                        try {
                            FileUtil.copyFile(backupCacheFile, new File(cachePath));
                            walletRpc.openWallet(config);
                            log.warn("Successfully opened RPC wallet using backup cache");
                            retrySuccessful = true;
                            break;
                        } catch (Exception e2) {

                            // delete cache file if failed to open
                            File cacheFile = new File(cachePath);
                            if (cacheFile.exists()) cacheFile.delete();
                            File unportableCacheFile = new File(cachePath + ".unportable");
                            if (unportableCacheFile.exists()) unportableCacheFile.delete();
                        }
                    }

                    // handle success or failure
                    File originalCacheBackup = new File(cachePath + ".backup");
                    if (retrySuccessful) {
                        if (originalCacheBackup.exists()) originalCacheBackup.delete(); // delete original wallet cache backup
                    } else {

                        // retry opening wallet after cache deleted
                        try {
                            log.warn("Failed to open RPC wallet '{}' using backup cache files, retrying with cache deleted", config.getPath());
                            walletRpc.openWallet(config);
                            log.warn("Successfully opened RPC wallet after cache deleted");
                            retrySuccessful = true;
                        } catch (Exception e2) {
                            // ignore
                        }

                        // handle success or failure
                        if (retrySuccessful) {
                            if (originalCacheBackup.exists()) originalCacheBackup.delete(); // delete original wallet cache backup
                        } else {
    
                            // restore original wallet cache
                            log.warn("Failed to open RPC wallet '{}' after deleting cache, restoring original cache", config.getPath());
                            File cacheFile = new File(cachePath);
                            if (cacheFile.exists()) cacheFile.delete();
                            if (originalCacheBackup.exists()) originalCacheBackup.renameTo(new File(cachePath));
    
                            // throw original exception
                            throw e;
                        }
                    }
                } catch (Exception e2) {
                    throw e; // throw original exception
                }
            }
            setDaemonConnection(walletRpc, connection, trustDaemon);
            walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.debug("Done opening RPC wallet " + config.getPath());
            return walletRpc;
        } catch (Exception e) {
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            if (!isShutDownStarted) log.warn("Could not open RPC wallet '{}': {}\n", config.getPath(), e.getMessage(), e);
            throw new WalletUnavailableException("Could not open wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes in your task manager, and restart Haveno.\n\nError message: " + e.getMessage(), e);
        }
    }

    /** Install monero-wallet-rpc from resources if missing, or if updating and the resource differs. */
    public static void maybeInstallMoneroWalletRpc(boolean update) {
        try {
            File walletRpcFile = new File(getMoneroWalletRpcPath());
            String resourcePath = "bin/" + MONERO_WALLET_RPC_NAME;
            if (!walletRpcFile.exists() || (update && !FileUtil.resourceEqualToFile(resourcePath, walletRpcFile))) {
                log.info("Installing monero-wallet-rpc");
                walletRpcFile.getParentFile().mkdirs();
                FileUtil.resourceToFile(resourcePath, walletRpcFile);
                walletRpcFile.setExecutable(true);
            }
        } catch (Exception e) {
            log.warn("Failed to install monero-wallet-rpc: {}\n", e.getMessage(), e);
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port, MoneroRpcConnection connection) {

        // install monero-wallet-rpc if missing (e.g. validating a seed before initial setup)
        maybeInstallMoneroWalletRpc(false);

        // check if monero-wallet-rpc exists
        if (!new File(getMoneroWalletRpcPath()).exists()) {
            String errorMessage = "monero-wallet-rpc executable doesn't exist at path " + Utilities.redactSensitiveInfo(getMoneroWalletRpcPath());
            if (Utilities.isWindows()) errorMessage += ". It may have been quarantined by antivirus software; remove it from quarantine and mark it as safe, then restart Haveno";
            throw new RuntimeException(errorMessage);
        }

        // build command to start monero-wallet-rpc
        List<String> cmd = new ArrayList<>(Arrays.asList( // modifiable list
                getMoneroWalletRpcPath(),
                "--rpc-login",
                MONERO_WALLET_RPC_USERNAME + ":" + MONERO_WALLET_RPC_DEFAULT_PASSWORD,
                "--wallet-dir", walletDir.toString()));

        // omit --mainnet flag since it does not exist
        if (MONERO_NETWORK_TYPE != MoneroNetworkType.MAINNET) {
            cmd.add("--" + MONERO_NETWORK_TYPE.toString().toLowerCase());
        }

        // testnet may use custom hard fork heights
        if (MONERO_NETWORK_TYPE == MoneroNetworkType.TESTNET) {
            cmd.add("--allow-mismatched-daemon-version");
        }

        // set connection flags
        if (connection != null) {
            cmd.add("--daemon-address");
            cmd.add(connection.getUri());
            boolean allowAnyCert = !connection.getSslVerify() && !connection.isOnion();
            if (connection.getProxyUri() != null) { // TODO: remove this when wallet server is not started with proxy uri
                cmd.add("--proxy");
                cmd.add(connection.getProxyUri());
                if (!connection.isOnion()) allowAnyCert = true; // necessary to use proxy with clearnet monerod
            }
            if (allowAnyCert) {
                cmd.add("--daemon-ssl-allow-any-cert");
            }
            if (connection.getUsername() != null) {
                cmd.add("--daemon-login");
                cmd.add(connection.getUsername() + ":" + connection.getPassword());
            }
        } else {
            cmd.add("--offline");
        }
        if (port != null && port > 0) {
            cmd.add("--rpc-bind-port");
            cmd.add(Integer.toString(port));
        }

        // start monero-wallet-rpc instance and return connected client
        return MONERO_WALLET_RPC_MANAGER.startInstance(cmd);
    }

    protected void onConnectionChanged(MoneroRpcConnection connection) {
        synchronized (walletLock) {
            if (wallet == null || isShutDownStarted) return;

            // configure wallet connection
            connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            xmrConnectionService.applyWalletProxyUri(connection, MONERO_WALLET_NAME, isProxyApplied());

            // ignore if no change
            if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) {
                updatePollPeriod();
                return;
            }

            // update connection
            String oldProxyUri = wallet == null || wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getProxyUri();
            String newProxyUri = connection == null ? null : connection.getProxyUri();
            log.info("Setting daemon connection for main wallet, monerod={}, proxyUri={}", connection == null ? null : connection.getUri(), newProxyUri);
            if (wallet instanceof MoneroWalletRpc && !StringUtils.equals(oldProxyUri, newProxyUri)) {
                log.info("Restarting main wallet because proxy URI has changed, old={}, new={}", oldProxyUri, newProxyUri); // TODO: remove this when wallet server is not started with proxy uri
                closeMainWallet();
                initMainWallet();
                return; // wallet re-initializes off thread
            } else {
                setDaemonConnection(wallet, connection, xmrConnectionService.isTrustedDaemon());
            }

            // update poll period
            if (connection != null && !isShutDownStarted) {
                wallet.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
                updatePollPeriod();
            }

            log.info("Done setting daemon connection for main wallet, monerod=" + (wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getUri()));
        }
    }

    private void changeWalletPasswords(String oldPassword, String newPassword) {

        // create task to change main wallet password
        List<Runnable> tasks = new ArrayList<Runnable>();
        tasks.add(() -> {
            try {
                getInitializedWallet().changePassword(oldPassword, newPassword);
                saveWallet();
            } catch (Exception e) {
                log.warn("Error changing main wallet password: " + e.getMessage() + "\n", e);
                throw e;
            }
        });

        // create tasks to change trade wallet passwords
        List<Trade> trades = HavenoUtils.tradeManager.getAllTrades();
        for (Trade trade : trades) {
            tasks.add(() -> {
                synchronized (trade.getWalletLock()) {
                    if (trade.walletExists()) {
                        trade.changeWalletPassword(oldPassword, newPassword); // TODO (woodser): this unnecessarily connects and syncs unopen wallets and leaves open
                    }
                }
            });
        }

        // execute tasks in parallel
        ThreadUtils.awaitTasks(tasks, Math.min(10, 1 + trades.size()));
        log.info("Done changing all wallet passwords");
    }

    private MoneroWallet openOrCreateMainWallet() {
        synchronized (walletLock) {
            if (wallet != null) return wallet;
            if (isShutDownStarted) throw new IllegalStateException("Cannot open or create main wallet because shut down has started");
            try {

                // open or create wallet
                long time = System.currentTimeMillis();
                MoneroDaemonRpc monerod = xmrConnectionService.getMonerod();
                boolean isProxyApplied = isProxyApplied();
                log.info("Initializing main wallet with monerod=" + (monerod == null ? "null" : monerod.getRpcConnection().getUri()) + ", proxyUri=" + (monerod == null || !isProxyApplied ? "null" : monerod.getRpcConnection().getProxyUri()));
                completeInterruptedRestore(); // else a new wallet would be created
                if (walletExists(MONERO_WALLET_NAME)) {
                    wallet = openWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied, xmrConnectionService.isTrustedDaemon());
                } else {
                    if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Cannot create main wallet because there is no connection to Monero daemon");
                    String importSeed = accountService.getWalletImportSeed();
                    if (importSeed == null) {
                        wallet = createWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied, xmrConnectionService.isTrustedDaemon());

                        // set wallet creation date to yesterday to guarantee complete restore
                        LocalDateTime localDateTime = LocalDate.now().atStartOfDay().minusDays(1);
                        long date = localDateTime.toEpochSecond(ZoneOffset.UTC);
                        user.setWalletCreationDate(date);
                    } else {

                        // import wallet from seed with the user's restore height, estimated offline from a restore date
                        Long importHeight = accountService.getWalletImportRestoreHeight();
                        LocalDate importDate = accountService.getWalletImportRestoreDate();
                        if (importHeight == null && importDate != null) importHeight = estimateHeightForDate(importDate);
                        long restoreHeight = importHeight == null ? 0 : importHeight;
                        walletRestoreHeight = restoreHeight;
                        wallet = createWalletFromSeed(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied, xmrConnectionService.isTrustedDaemon(), importSeed, restoreHeight);
                        user.setWalletCreationDate(estimateHeightTimestamp(restoreHeight));
                        accountService.setWalletImportDetails(null, null, null);
                    }
                }

                // set state from wallet, reporting the restore height while syncing towards it
                walletHeight.set(Math.max(wallet.getHeight(), getSyncFromHeight()));
                cacheWalletInfo();
                resetIfWalletChanged();

                // backup wallet on successful open or create
                if (Utilities.isWindows()) {
                    log.info("Closing main wallet to create a backup on Windows");
                    closeMainWallet();
                    doBackupWallet();
                    log.info("Reopening main wallet with monerod=" + (monerod == null ? "null" : monerod.getRpcConnection().getUri()) + ", proxyUri=" + (monerod == null || !isProxyApplied ? "null" : monerod.getRpcConnection().getProxyUri()));
                    wallet = openWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied, xmrConnectionService.isTrustedDaemon());
                } else {
                    doBackupWallet();
                }
                log.info("Done initializing main wallet in " + (System.currentTimeMillis() - time) + " ms");
            } catch (Exception e) {
                log.warn("Error initializing main wallet: {}\n", e.getMessage(), e);
                throw e;
            }
            
            // wait before returning wallet to avoid rate limiting
            if (!xmrConnectionService.isConnectionLocalHost()) {
                HavenoUtils.waitFor(INIT_WALLET_DELAY_MS);
            }

            return wallet;
        }
    }

    private void doBackupWallet() {
        synchronized (walletLock) {
            backupWallet(MONERO_WALLET_NAME);
        }
    }

    private void closeMainWallet() {
        closeMainWallet(false);
    }

    // Close and save the main wallet, returning false if it could not be closed.
    private boolean closeMainWallet(boolean stopPolling) {
        synchronized (walletLock) {
            if (stopPolling) stopPolling();
            try {
                if (wallet != null) {
                    log.info("Closing main wallet");
                    closeWallet(wallet, true);
                    wallet = null;
                }
                return true;
            } catch (Exception e) {
                log.warn("Error closing main wallet: {}. Was Haveno stopped manually with ctrl+c?", e.getMessage());
                return false;
            }
        }
    }

    private void forceCloseMainWallet() {
        log.warn("Force closing main wallet");
        stopPolling();
        if (wallet != null) {
            MoneroWallet walletRef = wallet;
            wallet = null; // nullify wallet before force closing so state is updated for error handling
            forceCloseWallet(walletRef, getWalletPath(MONERO_WALLET_NAME));
        }
    }

    public void forceRestartMainWallet() {
        log.warn("Force restarting main wallet");
        forceCloseMainWallet();
        initMainWallet();
    }

    public void handleMainWalletError(Exception e, MoneroRpcConnection sourceConnection, int numAttempts) {
        if (HavenoUtils.isUnresponsive(e)) forceCloseMainWallet(); // wallet can be stuck a while
        if (numAttempts % TradeProtocol.REQUEST_CONNECTION_SWITCH_EVERY_NUM_ATTEMPTS == 0) requestConnectionSwitchSynchronous(sourceConnection); // request connection switch every n attempts
        initMainWallet();
    }

    private void startPolling() {
        startPolling(false);
    }

    private void startPolling(boolean skipFirstPoll) {
        synchronized (walletLock) {
            if (isShutDownStarted || isPolling()) return;
            updatePollPeriod();
            AtomicReference<Boolean> skipNextPoll = new AtomicReference<>(skipFirstPoll);
            pollLooper = new TaskLooper(() -> {
                if (skipNextPoll.get()) {
                    skipNextPoll.set(false);
                    return;
                }
                try {
                    pollWallet();
                } catch (Throwable e) {
                    // use default error handling
                }
            });
            pollLooper.start(pollPeriodMs);
        }
    }

    private void stopPolling() {
        if (isPolling()) {
            pollLooper.stop();
            pollLooper = null;
        }
    }

    private boolean isPolling() {
        return pollLooper != null;
    }

    public void updatePollPeriod() {
        if (isShutDownStarted) return;
        setPollPeriodMs(getPollPeriodMs());
    }

    private long getPollPeriodMs() {
        return xmrConnectionService.getRefreshPeriodMs();
    }

    private void setPollPeriodMs(long pollPeriodMs) {
        synchronized (walletLock) {
            if (this.isShutDownStarted) return;
            if (this.pollPeriodMs != null && this.pollPeriodMs == pollPeriodMs) return;
            this.pollPeriodMs = pollPeriodMs;
            if (isPolling()) {
                stopPolling();
                startPolling();
            }
        }
    }

    private void pollWallet() {
        synchronized (pollLock) {
            if (pollInProgress) return;
        }
        doPollWallet();
    }

    // height syncing begins from: the restore height when it is ahead of the wallet height
    @Override
    protected long getSyncFromHeight() {
        Long restoreHeight = walletRestoreHeight; // mixed long/Long ternary would unbox and NPE when null
        if (wallet instanceof MoneroWalletFull) restoreHeight = ((MoneroWalletFull) wallet).getRestoreHeight();
        if (restoreHeight == null && user.getWalletCreationDate() > 0) {

            // recover the restore height from the wallet creation date, which encodes it
            restoreHeight = estimateTimestampHeight(user.getWalletCreationDate());
            Long targetHeight = xmrConnectionService.getTargetHeight();
            if (targetHeight != null) restoreHeight = Math.min(restoreHeight, targetHeight);
        }
        return restoreHeight == null ? walletHeight.get() : Math.max(walletHeight.get(), restoreHeight);
    }

    public void doPollWallet() {
        doPollWallet(null);
    }

    @SuppressWarnings("unused")
    public void doPollWallet(Long initialSyncTimeoutMs) {

        // skip polling after wallet service initialized until all domain services are initialized
        if (isWalletServiceInitialized() && !HavenoUtils.isAllDomainServicesInitialized()) {
            return;
        }

        // skip if shut down started
        MoneroWallet sourceWallet = getInitializedWallet();
        if (isShutDownStarted || sourceWallet == null) return;
        MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();

        // set poll in progress
        boolean pollInProgressSet = false;
        synchronized (pollLock) {
            if (!pollInProgress) pollInProgressSet = true;
            pollInProgress = true;
        }

        // poll wallet
        boolean pollFailed = false;
        try {

            // test sync error on startup
            if (TEST_STARTUP_SYNC_ERROR && !isWalletServiceInitialized()) {
                throw new RuntimeException("Testing wallet sync error on startup");
            }

            // skip if shut down started
            if (isShutDownStarted) return;

            // skip if daemon not synced
            MoneroDaemonInfo lastInfo = xmrConnectionService.getLastInfo();
            if (lastInfo == null) {
                log.warn("Last daemon info is null");
                return;
            }
            if (!xmrConnectionService.isSyncedWithinTolerance()) {

                // throttle warnings
                if (!logMonerodNotSyncedThrottler.onEvent().throttled) {
                    log.warn("Monero daemon is not synced within tolerance, height={}, targetHeight={}, monerod={}", xmrConnectionService.chainHeightProperty().get(), xmrConnectionService.getTargetHeight(), xmrConnectionService.getConnection() == null ? null : xmrConnectionService.getConnection().getUri());
                }
                return;
            }

            // skip polling if trades are reserving main wallet (disable if testnet or too long since last poll)
            List<Trade> tradesReservingMainWallet = HavenoUtils.tradeManager.getTradesReservingMainWallet();
            boolean lastPollWithinTolerance = System.currentTimeMillis() - lastPollTxsTimestamp <= POLL_TXS_TOLERANCE_MS;
            if (!tradesReservingMainWallet.isEmpty() && lastPollWithinTolerance && !Config.baseCurrencyNetwork().isTestnet()) {
                List<String> tradeIds = tradesReservingMainWallet.stream().map(Trade::getShortId).collect(Collectors.toList());
                log.info("Skipping main wallet poll because trades are reserving main wallet: " + tradeIds);
                return;
            }

            // sync wallet if first sync or behind daemon
            boolean isFirstSync = !wasWalletSynced;
            if (isFirstSync || walletHeight.get() < xmrConnectionService.getTargetHeight() - 1) {
                if (isFirstSync) log.info("Syncing main wallet from height " + getSyncFromHeight());
                long startTime = System.currentTimeMillis();
                syncWithProgress(initialSyncTimeoutMs);
                if (isFirstSync) log.info("Done syncing main wallet in " + (System.currentTimeMillis() - startTime) + " ms");
            }

            // fetch transactions and store to cache
            // TODO: ideally wallet should sync every poll and then avoid updating from pool on fetching txs?
            synchronized (walletLock) { // lock wallet to prevent concurrent close
                if (wallet == null || isShutDownStarted) return;
                ReentrantLock daemonLock = HavenoUtils.acquireDaemonLock();
                try {
                    if (lastPollTxsTimestamp == 0) lastPollTxsTimestamp = System.currentTimeMillis(); // set initial timestamp
                    try {
                        cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true));
                        lastPollTxsTimestamp = System.currentTimeMillis();
                    } catch (Exception e) { // fetch from pool can fail
                        if (!isShutDownStarted && wallet == sourceWallet) {

                            // throttle error handling
                            if (!logPollErrorRateThrottler.onEvent().throttled) {
                                log.warn("Error polling main wallet's transactions from the pool: {}", e.getMessage());
                                if (System.currentTimeMillis() - lastPollTxsTimestamp > POLL_TXS_TOLERANCE_MS) ThreadUtils.submitToPool(() -> requestConnectionSwitchSynchronous(sourceConnection));
                            }
                        }
                    }
                } finally {
                    HavenoUtils.releaseDaemonLock(daemonLock);
                }
            }

            // handle sucessful sync before wallet service initialized
            if (isFirstSync || (wasWalletSynced && !isWalletServiceInitialized())) onFirstSync();
            resetDisconnectionTracking();
        } catch (Exception e) {
            pollFailed = true;

            // skip error handling if shut down or another thread force restarts while polling
            if (isShutDownStarted || wallet == null || wallet != sourceWallet) return;

            // log "expected" vs unexpected errors
            if (Boolean.TRUE.equals(xmrConnectionService.isConnected())) {
                if (isExpectedWalletError(e)) {
                    log.warn("Error polling main wallet, errorMessage={}. Monerod={}", e.getMessage(), getXmrConnectionService().getConnection());
                } else {
                    log.warn("Error polling main wallet, errorMessage={}. Monerod={}", e.getMessage(), getXmrConnectionService().getConnection(), e); // include stack trace for unexpected errors
                }
            }

            // force close wallet if unresponsive
            if (HavenoUtils.isUnresponsive(e)) {
                forceCloseMainWallet();
            }

            // request connection switch when uninitialized, unresponsive, or disconnected beyond a grace period
            if (!isWalletServiceInitialized() || HavenoUtils.isUnresponsive(e) || isSustainedDisconnection(e)) {
                requestConnectionSwitchSynchronous(sourceConnection);
            }

            // reinitialize main wallet if applicable
            initMainWallet();
            throw e;
        } finally {
            if (pollInProgressSet) {
                synchronized (pollLock) {
                    pollInProgress = false;
                }
            }
            requestSaveWalletIfElapsedTime();

            // cache wallet info last unless poll failed
            synchronized (walletLock) {
                if (!pollFailed && wallet != null && !isShutDownStarted) {
                    try {
                        cacheWalletInfo();
                    } catch (Exception e) {
                        log.warn("Error caching wallet info: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void onFirstSync() {
        wasWalletSynced = true;
        if (walletInitListener != null) {
            xmrConnectionService.downloadPercentageProperty().removeListener(walletInitListener);
            walletInitListener = null;
        }

        // log wallet balances
        if (getMoneroNetworkType() != MoneroNetworkType.MAINNET) {
            BigInteger balance = getBalance();
            BigInteger unlockedBalance = getAvailableBalance();
            log.info("Monero wallet unlocked balance={}, pending balance={}, total balance={}", unlockedBalance, balance.subtract(unlockedBalance), balance);
        }

        // reapply connection after wallet synced for config changes
        onConnectionChanged(xmrConnectionService.getConnection());

        // announce progress on main thread
        UserThread.execute(() -> {
            
            // signal that main wallet is synced
            syncProgressListener.doneDownload();

            // notify setup that main wallet is initialized
            onWalletServiceInitialized();
        });
    }

    private void onWalletServiceInitialized() {
        if (isWalletServiceInitialized()) return;

        // monitor wallet height updates to request connection change
        walletHeight.addListener((obs, oldVal, newVal) -> {
            lastWalletHeightMonitorUpdate = System.currentTimeMillis();
            startWalletHeightMonitor();
        });

        // start the wallet height monitor to request connection changes periodically if needed
        startWalletHeightMonitor();
        
        // update external state
        HavenoUtils.havenoSetup.getWalletInitialized().set(true);
    }

    private boolean isWalletServiceInitialized() {
        return HavenoUtils.havenoSetup.getWalletInitialized().get();
    }

    public boolean requestConnectionSwitchSynchronous(MoneroRpcConnection sourceConnection) {
        synchronized (requestConnectionSwitchSynchronousLock) {
            isProcessingRequestConnectionSwitchSynchronous = true;
            try {
                if (xmrConnectionService.requestConnectionSwitch(sourceConnection, this)) {
                    onConnectionChanged(xmrConnectionService.getConnection()); // handle connection change on same thread
                    return true;
                }
                return false;
            } finally {
                isProcessingRequestConnectionSwitchSynchronous = false;
            }
        }
    }

    private void onNewBlock(long height) {
        UserThread.execute(() -> {
            walletHeight.set(height);
            for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onNewBlock(height));
        });
    }

    private void cacheWalletInfo() {
        synchronized (walletLock) {
            MoneroWallet wallet = this.wallet; // snapshot because a concurrent force close can null the field without walletLock
            if (wallet == null) {
                log.warn("Cannot cache wallet info because wallet is null");
                return;
            }

            // get basic wallet info
            long height = wallet.getHeight();
            BigInteger balance = wallet.getBalance();
            BigInteger unlockedBalance = wallet.getUnlockedBalance();
            cachedSubaddresses = wallet.getSubaddresses(0);
            cachedOutputs = wallet.getOutputs();
            if (cachedTxs == null) cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true).setInTxPool(false));

            // cache and notify changes
            if (cachedHeight == null) {
                cachedHeight = height;
                cachedBalance = balance;
                cachedAvailableBalance = unlockedBalance;
                onNewBlock(height);
                onBalancesChanged(balance, unlockedBalance);
            } else {
                boolean heightChanged = height != cachedHeight;
                boolean balancesChanged = !balance.equals(cachedBalance) || !unlockedBalance.equals(cachedAvailableBalance);
                cachedHeight = height;
                cachedBalance = balance;
                cachedAvailableBalance = unlockedBalance;
                if (heightChanged) onNewBlock(height);
                if (balancesChanged) onBalancesChanged(balance, unlockedBalance);
            }
        }
    }

    private void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
        updateBalanceListeners();
        for (MoneroWalletListenerI listener : walletListeners) listener.onBalancesChanged(newBalance, newUnlockedBalance);
    }
}
