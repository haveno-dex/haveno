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
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.XmrConnectionService;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.core.xmr.setup.DownloadListener;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import haveno.core.xmr.setup.WalletsSetup;
import java.io.File;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
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
import monero.wallet.model.MoneroSyncResult;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxPriority;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroWalletListenerI;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class XmrWalletService {
    private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

    // monero configuration
    public static final int NUM_BLOCKS_UNLOCK = 10;
    public static final String MONERO_BINS_DIR = Config.appDataDir().getAbsolutePath();
    public static final String MONERO_WALLET_RPC_NAME = Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc";
    public static final String MONERO_WALLET_RPC_PATH = MONERO_BINS_DIR + File.separator + MONERO_WALLET_RPC_NAME;
    public static final double MINER_FEE_TOLERANCE = 0.25; // miner fee must be within percent of estimated fee
    public static final MoneroTxPriority PROTOCOL_FEE_PRIORITY = MoneroTxPriority.ELEVATED;
    public static final int MONERO_LOG_LEVEL = -1; // monero library log level, -1 to disable
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = getMoneroNetworkType();
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    private static final String KEYS_FILE_POSTFIX = ".keys";
    private static final String ADDRESS_FILE_POSTFIX = ".address.txt";
    private static final int NUM_MAX_WALLET_BACKUPS = 1;
    private static final int MAX_SYNC_ATTEMPTS = 3;
    private static final boolean PRINT_RPC_STACK_TRACE = false;
    private static final String THREAD_ID = XmrWalletService.class.getSimpleName();
    private static final long SHUTDOWN_TIMEOUT_MS = 60000;
    private static final long NUM_BLOCKS_BEHIND_TOLERANCE = 5;
    private static final long LOG_POLL_ERROR_AFTER_MS = 180000; // log poll error if unsuccessful after this time
    private static Long lastPollSuccessTimestamp;

    private final User user;
    private final Preferences preferences;
    private final CoreAccountService accountService;
    private final XmrConnectionService xmrConnectionService;
    private final XmrAddressEntryList xmrAddressEntryList;
    private final WalletsSetup walletsSetup;
    private final DownloadListener downloadListener = new DownloadListener();
    private final LongProperty walletHeight = new SimpleLongProperty(0);

    private final File walletDir;
    private final File xmrWalletFile;
    private final int rpcBindPort;
    private final boolean useNativeXmrWallet;
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private ChangeListener<? super Number> walletInitListener;
    private TradeManager tradeManager;
    private MoneroWallet wallet;
    public static final Object WALLET_LOCK = new Object();
    private boolean wasWalletSynced = false;
    private final Map<String, Optional<MoneroTx>> txCache = new HashMap<String, Optional<MoneroTx>>();
    private boolean isClosingWallet = false;
    private boolean isShutDownStarted = false;
    private ExecutorService syncWalletThreadPool = Executors.newFixedThreadPool(10); // TODO: adjust based on connection type
    private Long syncStartHeight = null;
    private TaskLooper syncWithProgressLooper = null;
    CountDownLatch syncWithProgressLatch;

    // wallet polling and cache
    private TaskLooper pollLooper;
    private boolean pollInProgress;
    private Long pollPeriodMs;
    private final Object pollLock = new Object();
    private Long cachedHeight;
    private BigInteger cachedBalance;
    private BigInteger cachedAvailableBalance = null;
    private List<MoneroSubaddress> cachedSubaddresses;
    private List<MoneroOutputWallet> cachedOutputs;
    private List<MoneroTxWallet> cachedTxs;
    private boolean runReconnectTestOnStartup = false; // test reconnecting on startup while syncing so the wallet is blocked

    @SuppressWarnings("unused")
    @Inject
    XmrWalletService(User user,
                     Preferences preferences,
                     CoreAccountService accountService,
                     XmrConnectionService xmrConnectionService,
                     WalletsSetup walletsSetup,
                     XmrAddressEntryList xmrAddressEntryList,
                     @Named(Config.WALLET_DIR) File walletDir,
                     @Named(Config.WALLET_RPC_BIND_PORT) int rpcBindPort,
                     @Named(Config.USE_NATIVE_XMR_WALLET) boolean useNativeXmrWallet) {
        this.user = user;
        this.preferences = preferences;
        this.accountService = accountService;
        this.xmrConnectionService = xmrConnectionService;
        this.walletsSetup = walletsSetup;
        this.xmrAddressEntryList = xmrAddressEntryList;
        this.walletDir = walletDir;
        this.rpcBindPort = rpcBindPort;
        this.useNativeXmrWallet = useNativeXmrWallet;
        this.xmrWalletFile = new File(walletDir, MONERO_WALLET_NAME);
        HavenoUtils.xmrWalletService = this;

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
                    closeMainWallet(true);
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

    // TODO (woodser): need trade manager to get trade ids to change all wallet passwords?
    public void setTradeManager(TradeManager tradeManager) {
        this.tradeManager = tradeManager;
    }

    public MoneroWallet getWallet() {
        State state = walletsSetup.getWalletConfig().state();
        checkState(state == State.STARTING || state == State.RUNNING, "Cannot call until startup is complete and running, but state is: " + state);
        return wallet;
    }

    /**
     * Get the wallet creation date in seconds since epoch.
     *
     * @return the wallet creation date in seconds since epoch
     */
    public long getWalletCreationDate() {
        return user.getWalletCreationDate();
    }

    public void saveMainWallet() {
        saveMainWallet(!(Utilities.isWindows() && wallet != null));
    }

    public void saveMainWallet(boolean backup) {
        saveWallet(getWallet(), backup);
    }

    public void requestSaveMainWallet() {
        ThreadUtils.submitToPool(() -> saveMainWallet()); // save wallet off main thread
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

    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return downloadListener.percentageProperty();
    }

    private void doneDownload() {
        downloadListener.doneDownload();
    }

    public boolean isDownloadComplete() {
        return downloadPercentageProperty().get() == 1d;
    }

    public LongProperty walletHeightProperty() {
        return walletHeight;
    }

    public boolean isSyncedWithinTolerance() {
        if (!xmrConnectionService.isSyncedWithinTolerance()) return false;
        Long targetHeight = xmrConnectionService.getTargetHeight();
        if (targetHeight == null) return false;
        if (targetHeight - walletHeight.get() <= NUM_BLOCKS_BEHIND_TOLERANCE) return true; // synced if within a few blocks of target height
        return false;
    }

    public MoneroDaemonRpc getDaemon() {
        return xmrConnectionService.getDaemon();
    }

    public XmrConnectionService getConnectionService() {
        return xmrConnectionService;
    }

    public boolean isProxyApplied() {
        return isProxyApplied(wasWalletSynced);
    }

    public boolean isProxyApplied(boolean wasWalletSynced) {
        return preferences.isProxyApplied(wasWalletSynced) && xmrConnectionService.isProxyApplied();
    }

    public String getWalletPassword() {
        return accountService.getPassword() == null ? MONERO_WALLET_RPC_DEFAULT_PASSWORD : accountService.getPassword();
    }

    public boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + KEYS_FILE_POSTFIX).exists();
    }

    public MoneroWallet createWallet(String walletName) {
        return createWallet(walletName, null);
    }

    public MoneroWallet createWallet(String walletName, Integer walletRpcPort) {
        log.info("{}.createWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        MoneroWalletConfig config = getWalletConfig(walletName);
        return isNativeLibraryApplied() ? createWalletFull(config) : createWalletRpc(config, walletRpcPort);
    }

    public MoneroWallet openWallet(String walletName, boolean applyProxyUri) {
        return openWallet(walletName, null, applyProxyUri);
    }

    public MoneroWallet openWallet(String walletName, Integer walletRpcPort, boolean applyProxyUri) {
        log.info("{}.openWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet because shutting down");
        MoneroWalletConfig config = getWalletConfig(walletName);
        return isNativeLibraryApplied() ? openWalletFull(config, applyProxyUri) : openWalletRpc(config, walletRpcPort, applyProxyUri);
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

    private boolean isNativeLibraryApplied() {
        return useNativeXmrWallet && MoneroUtils.isNativeLibraryLoaded();
    }

    /**
     * Sync the given wallet in a thread pool with other wallets.
     */
    public MoneroSyncResult syncWallet(MoneroWallet wallet) {
        synchronized (HavenoUtils.getDaemonLock()) { // TODO: lock defeats purpose of thread pool
            Callable<MoneroSyncResult> task = () -> {
                return wallet.sync();
            };
            Future<MoneroSyncResult> future = syncWalletThreadPool.submit(task);
            try {
                return future.get();
            } catch (Exception e) {
                throw new MoneroError(e.getMessage());
            }
        }
    }

    public void saveWallet(MoneroWallet wallet) {
        saveWallet(wallet, false);
    }

    public void saveWallet(MoneroWallet wallet, boolean backup) {
        wallet.save();
        if (backup) backupWallet(getWalletName(wallet.getPath()));
    }

    public void closeWallet(MoneroWallet wallet, boolean save) {
        log.info("{}.closeWallet({}, {})", getClass().getSimpleName(), wallet.getPath(), save);
        MoneroError err = null;
        String path = wallet.getPath();
        try {
            wallet.close(save);
            if (save) backupWallet(getWalletName(path));
        } catch (MoneroError e) {
            err = e;
        }

        // stop wallet rpc instance if applicable
        if (wallet instanceof MoneroWalletRpc) MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, false);
        if (err != null) throw err;
    }

    public void forceCloseWallet(MoneroWallet wallet, String path) {
        if (wallet instanceof MoneroWalletRpc) {
            MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, true);
        } else {
            wallet.close(false);
        }
    }

    public void deleteWallet(String walletName) {
        assertNotPath(walletName);
        log.info("{}.deleteWallet({})", getClass().getSimpleName(), walletName);
        if (!walletExists(walletName)) throw new Error("Wallet does not exist at path: " + walletName);
        String path = walletDir.toString() + File.separator + walletName;
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet cache file: " + path);
        if (!new File(path + KEYS_FILE_POSTFIX).delete()) throw new RuntimeException("Failed to delete wallet keys file: " + path + KEYS_FILE_POSTFIX);
        if (!new File(path + ADDRESS_FILE_POSTFIX).delete() && !Config.baseCurrencyNetwork().isMainnet()) throw new RuntimeException("Failed to delete wallet address file: " + path + ADDRESS_FILE_POSTFIX); // mainnet does not have address file by default
    }

    public void backupWallet(String walletName) {
        assertNotPath(walletName);
        FileUtil.rollingBackup(walletDir, walletName, NUM_MAX_WALLET_BACKUPS);
        FileUtil.rollingBackup(walletDir, walletName + KEYS_FILE_POSTFIX, NUM_MAX_WALLET_BACKUPS);
        FileUtil.rollingBackup(walletDir, walletName + ADDRESS_FILE_POSTFIX, NUM_MAX_WALLET_BACKUPS);
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

    public MoneroTxWallet createTx(MoneroTxConfig txConfig) {
        synchronized (WALLET_LOCK) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                MoneroTxWallet tx = wallet.createTx(txConfig);
                if (Boolean.TRUE.equals(txConfig.getRelay())) {
                    cachedTxs.addFirst(tx);
                    cacheWalletInfo();
                    requestSaveMainWallet();
                }
                return tx;
            }
        }
    }

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        MoneroTxWallet tx = createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
        //printTxs("XmrWalletService.createTx", tx);
        return tx;
    }

    /**
     * Freeze reserved outputs and thaw unreserved outputs.
     */
    public void fixReservedOutputs() {
        synchronized (WALLET_LOCK) {

            // collect reserved outputs
            Set<String> reservedKeyImages = new HashSet<String>();
            for (Trade trade : tradeManager.getOpenTrades()) {
                if (trade.getSelf().getReserveTxKeyImages() == null) continue;
                reservedKeyImages.addAll(trade.getSelf().getReserveTxKeyImages());
            }
            for (OpenOffer openOffer : tradeManager.getOpenOfferManager().getOpenOffers()) {
                if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() == null) continue;
                reservedKeyImages.addAll(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages());
            }

            freezeReservedOutputs(reservedKeyImages);
            thawUnreservedOutputs(reservedKeyImages);
        }
    }

    private void freezeReservedOutputs(Set<String> reservedKeyImages) {
        synchronized (WALLET_LOCK) {

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
        synchronized (WALLET_LOCK) {

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
        synchronized (WALLET_LOCK) {

            // collect outputs to freeze
            List<String> unfrozenKeyImages = getOutputs(new MoneroOutputQuery().setIsFrozen(false).setIsSpent(false)).stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toList());
            unfrozenKeyImages.retainAll(keyImages);

            // freeze outputs
            for (String keyImage : unfrozenKeyImages) wallet.freezeOutput(keyImage);
            cacheWalletInfo();
            requestSaveMainWallet();
        }
    }

    /**
     * Thaw the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to thaw (ignored if null or empty)
     */
    public void thawOutputs(Collection<String> keyImages) {
        if (keyImages == null || keyImages.isEmpty()) return;
        synchronized (WALLET_LOCK) {

            // collect outputs to thaw
            List<String> frozenKeyImages = getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false)).stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toList());
            frozenKeyImages.retainAll(keyImages);

            // thaw outputs
            for (String keyImage : frozenKeyImages) wallet.thawOutput(keyImage);
            cacheWalletInfo();
            requestSaveMainWallet();
        }
    }

    public BigInteger getOutputsAmount(Collection<String> keyImages) {
        BigInteger sum = BigInteger.ZERO;
        for (String keyImage : keyImages) {
            List<MoneroOutputWallet> outputs = getOutputs(new MoneroOutputQuery().setIsSpent(false).setKeyImage(new MoneroKeyImage(keyImage)));
            if (!outputs.isEmpty()) sum = sum.add(outputs.get(0).getAmount());
        }
        return sum;
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
     * @param penaltyFee penalty fee for breaking protocol
     * @param tradeFee trade fee
     * @param sendTradeAmount trade amount to send peer
     * @param securityDeposit security deposit amount
     * @param returnAddress return address for reserved funds
     * @param reserveExactAmount specifies to reserve the exact input amount
     * @param preferredSubaddressIndex preferred source subaddress to spend from (optional)
     * @return the reserve tx
     */
    public MoneroTxWallet createReserveTx(BigInteger penaltyFee, BigInteger tradeFee, BigInteger sendTradeAmount, BigInteger securityDeposit, String returnAddress, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        synchronized (WALLET_LOCK) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                log.info("Creating reserve tx with preferred subaddress index={}, return address={}", preferredSubaddressIndex, returnAddress);
                long time = System.currentTimeMillis();
                BigInteger sendAmount = sendTradeAmount.add(securityDeposit).add(tradeFee).subtract(penaltyFee);
                MoneroTxWallet reserveTx = createTradeTx(penaltyFee, HavenoUtils.getBurnAddress(), sendAmount, returnAddress, reserveExactAmount, preferredSubaddressIndex);
                log.info("Done creating reserve tx in {} ms", System.currentTimeMillis() - time);
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
        synchronized (WALLET_LOCK) {
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
        synchronized (WALLET_LOCK) {
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
                    if (i == subaddressIndices.size() - 1 && reserveExactAmount) throw e; // throw if no subaddress with exact output
                }
            }

            // try any subaddress
            return createTradeTxFromSubaddress(feeAmount, feeAddress, sendAmount, sendAddress, null);
        }
    }

    private MoneroTxWallet createTradeTxFromSubaddress(BigInteger feeAmount, String feeAddress, BigInteger sendAmount, String sendAddress, Integer subaddressIndex) {

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
        MoneroDaemonRpc daemon = getDaemon();
        MoneroWallet wallet = getWallet();
        MoneroTx tx = null;
        synchronized (daemon) {
            try {

                // verify tx not submitted to pool
                tx = daemon.getTx(txHash);
                if (tx != null) throw new RuntimeException("Tx is already submitted");

                // submit tx to pool
                MoneroSubmitTxResult result = daemon.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
                if (!result.isGood()) throw new RuntimeException("Failed to submit tx to daemon: " + JsonUtils.serialize(result));

                // get pool tx which has weight and size
                for (MoneroTx poolTx : daemon.getTxPool()) if (poolTx.getHash().equals(txHash)) tx = poolTx;
                if (tx == null) throw new RuntimeException("Tx is not in pool after being submitted");

                // verify key images
                if (keyImages != null) {
                    Set<String> txKeyImages = new HashSet<String>();
                    for (MoneroOutput input : tx.getInputs()) txKeyImages.add(input.getKeyImage().getHex());
                    if (!txKeyImages.equals(new HashSet<String>(keyImages))) throw new Error("Tx inputs do not match claimed key images");
                }

                // verify unlock height
                if (!BigInteger.ZERO.equals(tx.getUnlockTime())) throw new RuntimeException("Unlock height must be 0");

                // verify miner fee
                BigInteger minerFeeEstimate = getElevatedFeeEstimate(tx.getWeight());
                double minerFeeDiff = tx.getFee().subtract(minerFeeEstimate).abs().doubleValue() / minerFeeEstimate.doubleValue();
                if (minerFeeDiff > MINER_FEE_TOLERANCE) throw new Error("Miner fee is not within " + (MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + minerFeeEstimate + " but was " + tx.getFee());
                log.info("Trade tx fee {} is within tolerance, diff%={}", tx.getFee(), minerFeeDiff);

                // verify proof to fee address
                BigInteger actualTradeFee = BigInteger.ZERO;
                if (tradeFeeAmount.compareTo(BigInteger.ZERO) > 0) {
                    MoneroCheckTx tradeFeeCheck = wallet.checkTxKey(txHash, txKey, feeAddress);
                    if (!tradeFeeCheck.isGood()) throw new RuntimeException("Invalid proof to trade fee address");
                    actualTradeFee = tradeFeeCheck.getReceivedAmount();
                }

                // verify proof to transfer address
                MoneroCheckTx transferCheck = wallet.checkTxKey(txHash, txKey, sendAddress);
                if (!transferCheck.isGood()) throw new RuntimeException("Invalid proof to transfer address");
                BigInteger actualSendAmount = transferCheck.getReceivedAmount();

                // verify trade fee amount
                if (!actualTradeFee.equals(tradeFeeAmount)) throw new RuntimeException("Invalid trade fee amount, expected " + tradeFeeAmount + " but was " + actualTradeFee);

                // verify send amount
                BigInteger expectedSendAmount = sendAmount.subtract(tx.getFee());
                if (!actualSendAmount.equals(expectedSendAmount)) throw new RuntimeException("Invalid send amount, expected " + expectedSendAmount + " but was " + actualSendAmount + " with tx fee " + tx.getFee());
                return tx;
            } catch (Exception e) {
                log.warn("Error verifying trade tx with offer id=" + offerId + (tx == null ? "" : ", tx=\n" + tx) + ": " + e.getMessage());
                throw e;
            } finally {
                try {
                    daemon.flushTxPool(txHash); // flush tx from pool
                } catch (MoneroRpcError err) {
                    System.out.println(daemon.getRpcConnection());
                    throw err.getCode().equals(-32601) ? new RuntimeException("Failed to flush tx from pool. Arbitrator must use trusted, unrestricted daemon") : err;
                }
            }
        }
    }

    /**
     * Get the tx fee estimate based on its weight.
     *
     * @param txWeight - the tx weight
     * @return the tx fee estimate
     */
    private BigInteger getElevatedFeeEstimate(long txWeight) {

        // get fee estimates per kB from daemon
        MoneroFeeEstimate feeEstimates = getDaemon().getFeeEstimate();
        BigInteger baseFeeEstimate = feeEstimates.getFees().get(2); // get elevated fee per kB
        BigInteger qmask = feeEstimates.getQuantizationMask();
        log.info("Monero base fee estimate={}, qmask={}: " + baseFeeEstimate, qmask);

        // get tx base fee
        BigInteger baseFee = baseFeeEstimate.multiply(BigInteger.valueOf(txWeight));

        // round up to multiple of quantization mask
        BigInteger[] quotientAndRemainder = baseFee.divideAndRemainder(qmask);
        BigInteger feeEstimate = qmask.multiply(quotientAndRemainder[0]);
        if (quotientAndRemainder[1].compareTo(BigInteger.ZERO) > 0) feeEstimate = feeEstimate.add(qmask);
        return feeEstimate;
    }

    public MoneroTx getDaemonTx(String txHash) {
        List<MoneroTx> txs = getDaemonTxs(Arrays.asList(txHash));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public List<MoneroTx> getDaemonTxs(List<String> txHashes) {
        synchronized (txCache) {

            // fetch txs
            if (getDaemon() == null) xmrConnectionService.verifyConnection(); // will throw
            List<MoneroTx> txs = getDaemon().getTxs(txHashes, true);

            // store to cache
            for (MoneroTx tx : txs) txCache.put(tx.getHash(), Optional.of(tx));

            // schedule txs to be removed from cache
            UserThread.runAfter(() -> {
                synchronized (txCache) {
                    for (MoneroTx tx : txs) txCache.remove(tx.getHash());
                }
            }, xmrConnectionService.getRefreshPeriodMs() / 1000);
            return txs;
        }
    }

    public MoneroTx getDaemonTxWithCache(String txHash) {
        List<MoneroTx> cachedTxs = getDaemonTxsWithCache(Arrays.asList(txHash));
        return cachedTxs.isEmpty() ? null : cachedTxs.get(0);
    }

    public List<MoneroTx> getDaemonTxsWithCache(List<String> txHashes) {
        synchronized (txCache) {
            try {
                // get cached txs
                List<MoneroTx> cachedTxs = new ArrayList<MoneroTx>();
                List<String> uncachedTxHashes = new ArrayList<String>();
                for (int i = 0; i < txHashes.size(); i++) {
                    if (txCache.containsKey(txHashes.get(i))) cachedTxs.add(txCache.get(txHashes.get(i)).orElse(null));
                    else uncachedTxHashes.add(txHashes.get(i));
                }

                // return txs from cache if available, otherwise fetch
                return uncachedTxHashes.isEmpty() ? cachedTxs : getDaemonTxs(txHashes);
            } catch (Exception e) {
                if (!isShutDownStarted) throw e;
                return null;
            }
        }
    }

    public void onShutDownStarted() {
        log.info("XmrWalletService.onShutDownStarted()");
        this.isShutDownStarted = true;
    }

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());

        // create task to shut down
        Runnable shutDownTask = () -> {

            // remove listeners
            synchronized (WALLET_LOCK) {
                if (wallet != null) {
                    for (MoneroWalletListenerI listener : new HashSet<>(wallet.getListeners())) {
                        wallet.removeListener(listener);
                    }
                }
                walletListeners.clear();
            }

            // shut down threads
            synchronized (this) {
                List<Runnable> shutDownThreads = new ArrayList<>();
                shutDownThreads.add(() -> ThreadUtils.shutDown(THREAD_ID));
                ThreadUtils.awaitTasks(shutDownThreads);
            }

            // shut down main wallet
            if (wallet != null) {
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
            log.warn("Error shutting down {}: {}", getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();

            // force close wallet
            forceCloseWallet(wallet, getWalletPath(MONERO_WALLET_NAME));
        }

        log.info("Done shutting down {}", getClass().getSimpleName());
    }

    // -------------------------- ADDRESS ENTRIES -----------------------------

    public synchronized XmrAddressEntry getNewAddressEntry() {
        return getNewAddressEntryAux(null, XmrAddressEntry.Context.AVAILABLE);
    }

    public synchronized XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {

        // try to use available and not yet used entries
        try {
            List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries();
            if (!unusedAddressEntries.isEmpty()) return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(unusedAddressEntries.get(0), context, offerId);
        } catch (Exception e) {
            log.warn("Error getting new address entry based on incoming transactions");
            e.printStackTrace();
        }

        // create new entry
        return getNewAddressEntryAux(offerId, context);
    }

    private XmrAddressEntry getNewAddressEntryAux(String offerId, XmrAddressEntry.Context context) {
        MoneroSubaddress subaddress = wallet.createSubaddress(0);
        XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), context, offerId, null);
        log.info("Add new XmrAddressEntry {}", entry);
        xmrAddressEntryList.addAddressEntry(entry);
        return entry;
    }

    public synchronized XmrAddressEntry getFreshAddressEntry() {
        List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries();
        if (unusedAddressEntries.isEmpty()) return getNewAddressEntry();
        else return unusedAddressEntries.get(0);
    }

    public synchronized XmrAddressEntry recoverAddressEntry(String offerId, String address, XmrAddressEntry.Context context) {
        var available = findAddressEntry(address, XmrAddressEntry.Context.AVAILABLE);
        if (!available.isPresent()) return null;
        return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(available.get(), context, offerId);
    }

    public synchronized XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        if (addressEntry.isPresent()) return addressEntry.get();
        else return getNewAddressEntry(offerId, context);
    }

    public synchronized Optional<XmrAddressEntry> getAddressEntry(String offerId, XmrAddressEntry.Context context) {
        List<XmrAddressEntry> entries = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).collect(Collectors.toList());
        if (entries.size() > 1) throw new RuntimeException("Multiple address entries exist with offer ID " + offerId + " and context " + context + ". That should never happen.");
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    public synchronized void swapAddressEntryToAvailable(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        addressEntryOptional.ifPresent(e -> {
            log.info("swap addressEntry with address {} and offerId {} from context {} to available", e.getAddressString(), e.getOfferId(), context);
            xmrAddressEntryList.swapToAvailable(e);
            saveAddressEntryList();
        });
    }

    public synchronized void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
        swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.OFFER_FUNDING);

        // swap trade payout to available if applicable
        if (tradeManager == null) return;
        Trade trade = tradeManager.getTrade(offerId);
        if (trade == null || trade.isPayoutUnlocked()) swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    public synchronized void resetAddressEntriesForTrade(String offerId) {
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

    public BigInteger getAvailableBalanceForSubaddress(int subaddressIndex) {
        MoneroSubaddress subaddress = getSubaddress(subaddressIndex);
        return subaddress == null ? BigInteger.ZERO : subaddress.getUnlockedBalance();
    }

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> available = getFundedAvailableAddressEntries().stream();
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream().filter(entry -> !tradeManager.getOpenOfferManager().getOpenOfferById(entry.getOfferId()).isPresent()));
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream().filter(entry -> tradeManager.getTrade(entry.getOfferId()) == null || tradeManager.getTrade(entry.getOfferId()).isPayoutUnlocked()));
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
        if (!balanceListeners.contains(listener)) balanceListeners.add(listener);
    }

    public void removeBalanceListener(XmrBalanceListener listener) {
        balanceListeners.remove(listener);
    }

    public void updateBalanceListeners() {
        BigInteger availableBalance = getAvailableBalance();
        for (XmrBalanceListener balanceListener : balanceListeners) {
            BigInteger balance;
            if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
            else balance = availableBalance;
            ThreadUtils.submitToPool(() -> {
                try {
                    balanceListener.onBalanceChanged(balance);
                } catch (Exception e) {
                    log.warn("Failed to notify balance listener of change");
                    e.printStackTrace();
                }
            });
        }
    }

    public void saveAddressEntryList() {
        xmrAddressEntryList.requestPersistence();
    }

    public long getHeight() {
        return walletHeight.get();
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
        return getTxs(new MoneroTxQuery().setHashes(txIds));
    }

    public MoneroTxWallet getTx(String txId) {
        List<MoneroTxWallet> txs = getTxs(new MoneroTxQuery().setHash(txId));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public BigInteger getBalance() {
        return cachedBalance;
    }

    public BigInteger getAvailableBalance() {
        return cachedAvailableBalance;
    }

    public List<MoneroSubaddress> getSubaddresses() {
        return cachedSubaddresses;
    }

    public List<MoneroOutputWallet> getOutputs(MoneroOutputQuery query) {
        List<MoneroOutputWallet> filteredOutputs = new ArrayList<MoneroOutputWallet>();
        for (MoneroOutputWallet output : cachedOutputs) {
            if (query == null || query.meetsCriteria(output)) filteredOutputs.add(output);
        }
        return filteredOutputs;
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
        if (useNativeXmrWallet && !MoneroUtils.isNativeLibraryLoaded()) {
            try {
                MoneroUtils.loadNativeLibrary();
            } catch (Exception | UnsatisfiedLinkError e) {
                log.warn("Failed to load Monero native libraries: " + e.getMessage());
            }
        }
        String appliedMsg = "Monero native libraries applied: " + isNativeLibraryApplied();
        if (useNativeXmrWallet && !isNativeLibraryApplied()) log.warn(appliedMsg);
        else log.info(appliedMsg);

        // listen for connection changes
        xmrConnectionService.addConnectionListener(connection -> {

            // force restart main wallet if connection changed before synced
            if (!wasWalletSynced) {
                if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) return;
                ThreadUtils.submitToPool(() -> {
                    log.warn("Force restarting main wallet because connection changed before inital sync");
                    forceRestartMainWallet();
                });
                return;
            } else {

                // apply connection changes
                ThreadUtils.execute(() -> onConnectionChanged(connection), THREAD_ID);
            }
        });

        // initialize main wallet when daemon synced
        walletInitListener = (obs, oldVal, newVal) -> initMainWalletIfConnected();
        xmrConnectionService.downloadPercentageProperty().addListener(walletInitListener);
        initMainWalletIfConnected();
    }

    private void initMainWalletIfConnected() {
        ThreadUtils.execute(() -> {
            synchronized (WALLET_LOCK) {
                if (wallet == null && xmrConnectionService.downloadPercentageProperty().get() == 1 && !isShutDownStarted) {
                    maybeInitMainWallet(true);
                    if (walletInitListener != null) xmrConnectionService.downloadPercentageProperty().removeListener(walletInitListener);
                }
            }
        }, THREAD_ID);
    }

    private void maybeInitMainWallet(boolean sync) {
        try {
            maybeInitMainWallet(sync, MAX_SYNC_ATTEMPTS);
        } catch (Exception e) {
            log.warn("Error initializing main wallet: " + e.getMessage());
            e.printStackTrace();
            HavenoUtils.havenoSetup.getTopErrorMsg().set(e.getMessage());
            throw e;
        }
    }

    private void maybeInitMainWallet(boolean sync, int numAttempts) {
        synchronized (WALLET_LOCK) {
            if (isShutDownStarted) return;

            // open or create wallet main wallet
            if (wallet == null) {
                MoneroDaemonRpc daemon = xmrConnectionService.getDaemon();
                log.info("Initializing main wallet with monerod=" + (daemon == null ? "null" : daemon.getRpcConnection().getUri()));
                if (MoneroUtils.walletExists(xmrWalletFile.getPath())) {
                    wallet = openWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied(wasWalletSynced));
                } else if (Boolean.TRUE.equals(xmrConnectionService.isConnected())) {
                    wallet = createWallet(MONERO_WALLET_NAME, rpcBindPort);

                    // set wallet creation date to yesterday to guarantee complete restore
                    LocalDateTime localDateTime = LocalDate.now().atStartOfDay().minusDays(1);
                    long date = localDateTime.toEpochSecond(ZoneOffset.UTC);
                    user.setWalletCreationDate(date);
                }
                isClosingWallet = false;
            }

            // sync wallet and register listener
            if (wallet != null && !isShutDownStarted) {
                log.info("Monero wallet path={}", wallet.getPath());

                // sync main wallet if applicable
                if (sync && numAttempts > 0) {
                    try {

                        // sync main wallet
                        log.info("Syncing main wallet");
                        long time = System.currentTimeMillis();
                        syncWithProgress(); // blocking
                        log.info("Done syncing main wallet in " + (System.currentTimeMillis() - time) + " ms");
                        doPollWallet(true);

                        // log wallet balances
                        if (getMoneroNetworkType() != MoneroNetworkType.MAINNET) {
                            BigInteger balance = getBalance();
                            BigInteger unlockedBalance = getAvailableBalance();
                            log.info("Monero wallet unlocked balance={}, pending balance={}, total balance={}", unlockedBalance, balance.subtract(unlockedBalance), balance);
                        }

                        // reapply connection after wallet synced
                        onConnectionChanged(xmrConnectionService.getConnection());

                        // reset internal state if main wallet was swapped
                        resetIfWalletChanged();

                        // signal that main wallet is synced
                        doneDownload();

                        // notify setup that main wallet is initialized
                        // TODO: app fully initializes after this is set to true, even though wallet might not be initialized if unconnected. wallet will be created when connection detected
                        // refactor startup to call this and sync off main thread? but the calls to e.g. getBalance() fail with 'wallet and network is not yet initialized'
                        HavenoUtils.havenoSetup.getWalletInitialized().set(true);

                        // save but skip backup on initialization
                        saveMainWallet(false);
                    } catch (Exception e) {
                        if (isClosingWallet || isShutDownStarted || HavenoUtils.havenoSetup.getWalletInitialized().get()) return; // ignore if wallet closing, shut down started, or app already initialized
                        log.warn("Error initially syncing main wallet: {}", e.getMessage());
                        if (numAttempts <= 1) {
                            log.warn("Failed to sync main wallet. Opening app without syncing", numAttempts);
                            HavenoUtils.havenoSetup.getWalletInitialized().set(true);
                            saveMainWallet(false);

                            // reschedule to init main wallet
                            UserThread.runAfter(() -> {
                                ThreadUtils.execute(() -> maybeInitMainWallet(true, MAX_SYNC_ATTEMPTS), THREAD_ID);
                            }, xmrConnectionService.getRefreshPeriodMs() / 1000);
                        } else {
                            log.warn("Trying again in {} seconds", xmrConnectionService.getRefreshPeriodMs() / 1000);
                            UserThread.runAfter(() -> {
                                ThreadUtils.execute(() -> maybeInitMainWallet(true, numAttempts - 1), THREAD_ID);
                            }, xmrConnectionService.getRefreshPeriodMs() / 1000);
                        }
                    }
                }

                // start polling main wallet
                startPolling();
            }
        }
    }

    private void resetIfWalletChanged() {
        getAddressEntryListAsImmutableList(); // TODO: using getter to create base address if necessary
        List<XmrAddressEntry> baseAddresses = getAddressEntries(XmrAddressEntry.Context.BASE_ADDRESS);
        if (baseAddresses.size() > 1 || (baseAddresses.size() == 1 && !baseAddresses.get(0).getAddressString().equals(wallet.getPrimaryAddress()))) {
            String warningMsg = "New Monero wallet detected. Resetting internal state.";
            if (!tradeManager.getOpenTrades().isEmpty()) warningMsg += "\n\nWARNING: Your open trades will settle to the payout address in the OLD wallet!"; // TODO: allow payout address to be updated in PaymentSentMessage, PaymentReceivedMessage, and DisputeOpenedMessage?
            HavenoUtils.havenoSetup.getTopErrorMsg().set(warningMsg);

            // reset address entries
            xmrAddressEntryList.clear();
            getAddressEntryListAsImmutableList(); // recreate base address

            // cancel offers
            tradeManager.getOpenOfferManager().removeAllOpenOffers(null);
        }
    }

    private void syncWithProgress() {

        // show sync progress
        updateSyncProgress(wallet.getHeight());

        // test connection changing on startup before wallet synced
        if (runReconnectTestOnStartup) {
            UserThread.runAfter(() -> {
                log.warn("Testing connection change on startup before wallet synced");
                xmrConnectionService.setConnection("http://node.community.rino.io:18081"); // TODO: needs to be online
            }, 1);
            runReconnectTestOnStartup = false; // only run once
        }

        // get sync notifications from native wallet
        if (wallet instanceof MoneroWalletFull) {
            if (runReconnectTestOnStartup) HavenoUtils.waitFor(1000); // delay sync to test
            wallet.sync(new MoneroWalletListener() {
                @Override
                public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
                    updateSyncProgress(height);
                }
            });
            wasWalletSynced = true;
            return;
        }

        // poll wallet for progress
        wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
        syncWithProgressLatch = new CountDownLatch(1);
        syncWithProgressLooper = new TaskLooper(() -> {
            if (wallet == null) return;
            long height = 0;
            try {
                height = wallet.getHeight(); // can get read timeout while syncing
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
            if (height < xmrConnectionService.getTargetHeight()) updateSyncProgress(height);
            else {
                syncWithProgressLooper.stop();
                wasWalletSynced = true;
                updateSyncProgress(height);
                syncWithProgressLatch.countDown();
            }
        });
        syncWithProgressLooper.start(1000);
        HavenoUtils.awaitLatch(syncWithProgressLatch);
        wallet.stopSyncing();
        if (!wasWalletSynced) throw new IllegalStateException("Failed to sync wallet with progress");
    }

    private void stopSyncWithProgress() {
        if (syncWithProgressLooper != null) {
            syncWithProgressLooper.stop();
            syncWithProgressLooper = null;
            syncWithProgressLatch.countDown();
        }
    }

    private void updateSyncProgress(long height) {
        UserThread.execute(() -> {
            walletHeight.set(height);

            // new wallet reports height 1 before synced
            if (height == 1) {
                downloadListener.progress(.0001, xmrConnectionService.getTargetHeight() - height, null); // >0% shows progress bar
                return;
            }

            // set progress
            long targetHeight = xmrConnectionService.getTargetHeight();
            long blocksLeft = targetHeight - walletHeight.get();
            if (syncStartHeight == null) syncStartHeight = walletHeight.get();
            double percent = Math.min(1.0, targetHeight == syncStartHeight ? 1.0 : ((double) Math.max(1, (double) walletHeight.get() - syncStartHeight) / (double) (targetHeight - syncStartHeight))); // grant at least 1 block to show progress
            downloadListener.progress(percent, blocksLeft, null);
        });
    }

    private MoneroWalletFull createWalletFull(MoneroWalletConfig config) {

        // must be connected to daemon
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletFull walletFull = null;
        try {

            // create wallet
            MoneroRpcConnection connection = xmrConnectionService.getConnection();
            log.info("Creating full wallet " + config.getPath() + " connected to monerod=" + connection.getUri());
            long time = System.currentTimeMillis();
            config.setServer(connection);
            walletFull = MoneroWalletFull.createWallet(config);
            walletFull.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done creating full wallet " + config.getPath() + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletFull;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletFull != null) forceCloseMainWallet();
            throw new IllegalStateException("Could not create wallet '" + config.getPath() + "'");
        }
    }

    private MoneroWalletFull openWalletFull(MoneroWalletConfig config, boolean applyProxyUri) {
        MoneroWalletFull walletFull = null;
        try {

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            if (!applyProxyUri) connection.setProxyUri(null);

            // open wallet
            config.setNetworkType(getMoneroNetworkType());
            config.setServer(connection);
            log.info("Opening full wallet " + config.getPath() + " with monerod=" + connection.getUri());
            walletFull = MoneroWalletFull.openWallet(config);
            if (walletFull.getDaemonConnection() != null) walletFull.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done opening full wallet " + config.getPath());
            return walletFull;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletFull != null) forceCloseWallet(walletFull, config.getPath());
            throw new IllegalStateException("Could not open full wallet '" + config.getPath() + "'");
        }
    }

    private MoneroWalletRpc createWalletRpc(MoneroWalletConfig config, Integer port) {

        // must be connected to daemon
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, isProxyApplied(false));
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // create wallet
            MoneroRpcConnection connection = xmrConnectionService.getConnection();
            log.info("Creating RPC wallet " + config.getPath() + " connected to monerod=" + connection.getUri());
            long time = System.currentTimeMillis();
            config.setServer(connection);
            walletRpc.createWallet(config);
            walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done creating RPC wallet " + config.getPath() + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            throw new IllegalStateException("Could not create wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes, and restart Haveno.");
        }
    }

    private MoneroWalletRpc openWalletRpc(MoneroWalletConfig config, Integer port, boolean applyProxyUri) {
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, applyProxyUri);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            if (!applyProxyUri) connection.setProxyUri(null);

            // open wallet
            log.info("Opening RPC wallet " + config.getPath() + " with monerod=" + connection.getUri());
            config.setServer(connection);
            walletRpc.openWallet(config);
            if (walletRpc.getDaemonConnection() != null) walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done opening RPC wallet " + config.getPath());
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            throw new IllegalStateException("Could not open wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes, and restart Haveno.\n\nError message: " + e.getMessage());
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port, boolean applyProxyUri) {

        // check if monero-wallet-rpc exists
        if (!new File(MONERO_WALLET_RPC_PATH).exists()) throw new Error("monero-wallet-rpc executable doesn't exist at path " + MONERO_WALLET_RPC_PATH
                + "; copy monero-wallet-rpc to the project root or set WalletConfig.java MONERO_WALLET_RPC_PATH for your system");

        // build command to start monero-wallet-rpc
        List<String> cmd = new ArrayList<>(Arrays.asList( // modifiable list
                MONERO_WALLET_RPC_PATH,
                "--rpc-login",
                MONERO_WALLET_RPC_USERNAME + ":" + getWalletPassword(),
                "--wallet-dir", walletDir.toString()));

        // omit --mainnet flag since it does not exist
        if (MONERO_NETWORK_TYPE != MoneroNetworkType.MAINNET) {
            cmd.add("--" + MONERO_NETWORK_TYPE.toString().toLowerCase());
        }

        // set connection flags
        MoneroRpcConnection connection = xmrConnectionService.getConnection();
        if (connection != null) {
            cmd.add("--daemon-address");
            cmd.add(connection.getUri());
            if (applyProxyUri && connection.getProxyUri() != null) { // TODO: only apply proxy if wallet is already synced, so we need a flag passed here
                cmd.add("--proxy");
                cmd.add(connection.getProxyUri());
                if (!connection.isOnion()) cmd.add("--daemon-ssl-allow-any-cert"); // necessary to use proxy with clearnet mmonerod
            }
            if (connection.getUsername() != null) {
                cmd.add("--daemon-login");
                cmd.add(connection.getUsername() + ":" + connection.getPassword());
            }
        }
        if (port != null && port > 0) {
            cmd.add("--rpc-bind-port");
            cmd.add(Integer.toString(port));
        }

        // start monero-wallet-rpc instance and return connected client
        return MONERO_WALLET_RPC_MANAGER.startInstance(cmd);
    }

    private void onConnectionChanged(MoneroRpcConnection connection) {
        synchronized (WALLET_LOCK) {
            if (wallet == null || isShutDownStarted) return;
            if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) return;
            String oldProxyUri = wallet == null || wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getProxyUri();
            String newProxyUri = connection == null ? null : connection.getProxyUri();
            log.info("Setting daemon connection for main wallet: uri={}, proxyUri={}", connection == null ? null : connection.getUri(), newProxyUri);
            if (wallet instanceof MoneroWalletRpc) {
                if (StringUtils.equals(oldProxyUri, newProxyUri)) {
                    wallet.setDaemonConnection(connection);
                } else {
                    log.info("Restarting main wallet because proxy URI has changed, old={}, new={}", oldProxyUri, newProxyUri);
                    closeMainWallet(true);
                    maybeInitMainWallet(false);
                }
            } else {
                wallet.setDaemonConnection(connection);
                wallet.setProxyUri(connection.getProxyUri());
            }

            // sync wallet on new thread
            if (connection != null && !isShutDownStarted) {
                wallet.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
                updatePollPeriod();
            }

            log.info("Done setting main wallet monerod=" + (wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getUri()));
        }
    }

    private void changeWalletPasswords(String oldPassword, String newPassword) {

        // create task to change main wallet password
        List<Runnable> tasks = new ArrayList<Runnable>();
        tasks.add(() -> {
            try {
                wallet.changePassword(oldPassword, newPassword);
                saveMainWallet();
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }
        });

        // create tasks to change trade wallet passwords
        List<Trade> trades = tradeManager.getAllTrades();
        for (Trade trade : trades) {
            tasks.add(() -> {
                if (trade.walletExists()) {
                    trade.changeWalletPassword(oldPassword, newPassword); // TODO (woodser): this unnecessarily connects and syncs unopen wallets and leaves open
                }
            });
        }

        // excute tasks in parallel
        ThreadUtils.awaitTasks(tasks, Math.min(10, 1 + trades.size()));
        log.info("Done changing all wallet passwords");
    }

    private void closeMainWallet(boolean save) {
        stopPolling();
        synchronized (WALLET_LOCK) {
            try {
                if (wallet != null) {
                    isClosingWallet = true;
                    closeWallet(wallet, true);
                    wallet = null;
                }
            } catch (Exception e) {
                log.warn("Error closing main wallet: {}. Was Haveno stopped manually with ctrl+c?", e.getMessage());
            }
        }
    }

    private void forceCloseMainWallet() {
        isClosingWallet = true;
        forceCloseWallet(wallet, getWalletPath(MONERO_WALLET_NAME));
        stopPolling();
        stopSyncWithProgress();
        wallet = null;
    }

    private void forceRestartMainWallet() {
        log.warn("Force restarting main wallet");
        forceCloseMainWallet();
        synchronized (WALLET_LOCK) {
            maybeInitMainWallet(true);
        }
    }

    private void startPolling() {
        synchronized (WALLET_LOCK) {
            if (isShutDownStarted || isPollInProgress()) return;
            log.info("Starting to poll main wallet");
            updatePollPeriod();
            pollLooper = new TaskLooper(() -> pollWallet());
            pollLooper.start(pollPeriodMs);
        }
    }

    private void stopPolling() {
        if (isPollInProgress()) {
            pollLooper.stop();
            pollLooper = null;
        }
    }

    private boolean isPollInProgress() {
        return pollLooper != null;
    }

    public void updatePollPeriod() {
        if (isShutDownStarted) return;
        setPollPeriod(getPollPeriod());
    }

    private long getPollPeriod() {
        return xmrConnectionService.getRefreshPeriodMs();
    }

    private void setPollPeriod(long pollPeriodMs) {
        synchronized (WALLET_LOCK) {
            if (this.isShutDownStarted) return;
            if (this.pollPeriodMs != null && this.pollPeriodMs == pollPeriodMs) return;
            this.pollPeriodMs = pollPeriodMs;
            if (isPollInProgress()) {
                stopPolling();
                startPolling();
            }
        }
    }

    private void pollWallet() {
        if (pollInProgress) return;
        doPollWallet(true);
    }

    private void doPollWallet(boolean updateTxs) {
        synchronized (pollLock) {
            if (isShutDownStarted) return;
            pollInProgress = true;
            try {

                // switch to best connection if daemon is too far behind
                MoneroDaemonInfo lastInfo = xmrConnectionService.getLastInfo();
                if (lastInfo == null) {
                    log.warn("Last daemon info is null");
                    return;
                }
                if (wasWalletSynced && walletHeight.get() < xmrConnectionService.getTargetHeight() - NUM_BLOCKS_BEHIND_TOLERANCE && !Config.baseCurrencyNetwork().isTestnet()) {
                    log.warn("Updating connection because main wallet is {} blocks behind monerod, wallet height={}, monerod height={}", xmrConnectionService.getTargetHeight() - walletHeight.get(), walletHeight.get(), lastInfo.getHeight());
                    xmrConnectionService.switchToBestConnection();
                }

                // sync wallet if behind daemon
                if (walletHeight.get() < xmrConnectionService.getTargetHeight()) {
                    synchronized (WALLET_LOCK) {  // avoid long sync from blocking other operations
                        syncMainWallet();
                    }
                }

                // fetch transactions from pool and store to cache
                // TODO: ideally wallet should sync every poll and then avoid updating from pool on fetching txs?
                if (updateTxs) {
                    synchronized (WALLET_LOCK) { // avoid long fetch from blocking other operations
                        synchronized (HavenoUtils.getDaemonLock()) {
                            try {
                                cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true));
                                lastPollSuccessTimestamp = System.currentTimeMillis();
                            } catch (Exception e) { // fetch from pool can fail
                                if (!isShutDownStarted) {
                                    if (lastPollSuccessTimestamp == null || System.currentTimeMillis() - lastPollSuccessTimestamp > LOG_POLL_ERROR_AFTER_MS) { // only log if not recently successful
                                        log.warn("Error polling main wallet's transactions from the pool: {}", e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }

                // cache wallet info
                cacheWalletInfo();
            } catch (Exception e) {
                if (wallet == null || isShutDownStarted) return;
                boolean isConnectionRefused = e.getMessage() != null && e.getMessage().contains("Connection refused");
                if (isConnectionRefused) forceRestartMainWallet();
                else if (isWalletConnectedToDaemon()) {
                    log.warn("Error polling main wallet, errorMessage={}. Monerod={}", e.getMessage(), getConnectionService().getConnection());
                    //e.printStackTrace();
                }
            } finally {
                pollInProgress = false;
            }
        }
    }

    private MoneroSyncResult syncMainWallet() {
        synchronized (WALLET_LOCK) {
            MoneroSyncResult result = syncWallet(wallet);
            walletHeight.set(wallet.getHeight());
            return result;
        }
    }

    public boolean isWalletConnectedToDaemon() {
        synchronized (WALLET_LOCK) {
            try {
                if (wallet == null) return false;
                return wallet.isConnectedToDaemon();
            } catch (Exception e) {
                return false;
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
        
        // get basic wallet info
        long height = wallet.getHeight();
        BigInteger balance = wallet.getBalance();
        BigInteger unlockedBalance = wallet.getUnlockedBalance();
        cachedSubaddresses = wallet.getSubaddresses(0);
        cachedOutputs = wallet.getOutputs();

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

    private void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
        updateBalanceListeners();
        for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onBalancesChanged(newBalance, newUnlockedBalance));
    }
}
