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
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.model.XmrAddressEntryList;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class XmrWalletService extends XmrWalletBase {
    private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

    // monero configuration
    public static final int NUM_BLOCKS_UNLOCK = 10;
    public static final String MONERO_BINS_DIR = Config.appDataDir().getAbsolutePath();
    public static final String MONERO_WALLET_RPC_NAME = Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc";
    public static final String MONERO_WALLET_RPC_PATH = MONERO_BINS_DIR + File.separator + MONERO_WALLET_RPC_NAME;
    public static final MoneroTxPriority PROTOCOL_FEE_PRIORITY = MoneroTxPriority.DEFAULT;
    public static final int MONERO_LOG_LEVEL = -1; // monero library log level, -1 to disable
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = getMoneroNetworkType();
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    private static final String KEYS_FILE_POSTFIX = ".keys";
    private static final String ADDRESS_FILE_POSTFIX = ".address.txt";
    private static final int NUM_MAX_WALLET_BACKUPS = 2;
    private static final int MAX_SYNC_ATTEMPTS = 3;
    private static final boolean PRINT_RPC_STACK_TRACE = false;
    private static final String THREAD_ID = XmrWalletService.class.getSimpleName();
    private static final long SHUTDOWN_TIMEOUT_MS = 60000;
    private static final long NUM_BLOCKS_BEHIND_TOLERANCE = 5;
    private static final long POLL_TXS_TOLERANCE_MS = 1000 * 60 * 3; // request connection switch if txs not updated within 3 minutes

    private final User user;
    private final Preferences preferences;
    private final CoreAccountService accountService;
    private final XmrAddressEntryList xmrAddressEntryList;
    private final WalletsSetup walletsSetup;

    private final File walletDir;
    private final int rpcBindPort;
    private final boolean useNativeXmrWallet;
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private ChangeListener<? super Number> walletInitListener;
    private TradeManager tradeManager;

    private final Object lock = new Object();
    private TaskLooper pollLooper;
    private boolean pollInProgress;
    private Long pollPeriodMs;
    private long lastLogDaemonNotSyncedTimestamp;
    private long lastLogPollErrorTimestamp;
    private long lastPollTxsTimestamp; 
    private final Object pollLock = new Object();
    private Long cachedHeight;
    private BigInteger cachedBalance;
    private BigInteger cachedAvailableBalance = null;
    private List<MoneroSubaddress> cachedSubaddresses;
    private List<MoneroOutputWallet> cachedOutputs;
    private List<MoneroTxWallet> cachedTxs;
    private int numInitSyncAttempts;

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
        this.walletsSetup = walletsSetup;
        this.xmrAddressEntryList = xmrAddressEntryList;
        this.walletDir = walletDir;
        this.rpcBindPort = rpcBindPort;
        this.useNativeXmrWallet = useNativeXmrWallet;
        HavenoUtils.xmrWalletService = this;
        HavenoUtils.xmrConnectionService = xmrConnectionService;
        this.xmrConnectionService = xmrConnectionService; // TODO: super's is null unless set here from injection

        // reset thread pool
        ThreadUtils.reset(THREAD_ID);

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
                    UserThread.execute(() -> syncProgressListener.progress(-1, -1));
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

    @Override
    public void saveWallet() {
        synchronized (walletLock) {
            saveWallet(shouldBackup(wallet));
            lastSaveTimeMs = System.currentTimeMillis();
        }
    }

    private boolean shouldBackup(MoneroWallet wallet) {
        return wallet != null && !Utilities.isWindows(); // TODO: cannot backup on windows because file is locked
    }

    public void saveWallet(boolean backup) {
        synchronized (walletLock) {
            saveWallet(getWallet(), backup);
        }
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
        if (targetHeight - walletHeight.get() <= NUM_BLOCKS_BEHIND_TOLERANCE) return true; // synced if within a few blocks of target height
        return false;
    }

    public MoneroDaemonRpc getMonerod() {
        return xmrConnectionService.getMonerod();
    }

    public boolean isProxyApplied() {
        return isProxyApplied(wasWalletSynced);
    }

    public boolean isProxyApplied(boolean wasWalletSynced) {
        MoneroRpcConnection connection = xmrConnectionService.getConnection();
        if (connection != null && connection.isOnion()) return true; // must use proxy if connected to onion
        return xmrConnectionService.isProxyApplied() && preferences.isProxyApplied(wasWalletSynced);
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

    private MoneroWallet createWallet(String walletName, Integer walletRpcPort) {
        log.info("{}.createWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        MoneroWalletConfig config = getWalletConfig(walletName);
        return isNativeLibraryApplied() ? createWalletFull(config) : createWalletRpc(config, walletRpcPort);
    }

    public MoneroWallet openWallet(String walletName, boolean applyProxyUri) {
        return openWallet(walletName, null, applyProxyUri);
    }

    public MoneroWallet openWallet(String walletName, Integer walletRpcPort, boolean applyProxyUri) {
        log.debug("{}.openWallet({})", getClass().getSimpleName(), walletName);
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

    public void saveWallet(MoneroWallet wallet) {
        saveWallet(wallet, false);
    }

    public void saveWallet(MoneroWallet wallet, boolean backup) {
        if (backup) backupWallet(getWalletName(wallet.getPath()));
        wallet.save();
    }

    public void closeWallet(MoneroWallet wallet, boolean save) {
        log.info("Closing wallet with path={}, save={}", wallet.getPath(), save);
        MoneroError err = null;
        String path = wallet.getPath();
        try {
            if (save && wallet instanceof MoneroWalletRpc) {
                ((MoneroWalletRpc) wallet).stop(); // saves wallet and stops rpc server
            } else {
                if (save) saveWallet(wallet);
                wallet.close();
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
            log.warn("Ignoring force close wallet because wallet is null, path={}", path);
            return;
        }
        if (wallet instanceof MoneroWalletRpc) {
            MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, true);
        } else {
            wallet.close(false);
        }
    }

    public void deleteWallet(String walletName) {
        assertNotPath(walletName);
        log.info("{}.deleteWallet({})", getClass().getSimpleName(), walletName);
        if (!walletExists(walletName)) throw new RuntimeException("Wallet does not exist at path: " + walletName);
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

            // freeze outputs
            for (String keyImage : unfrozenKeyImages) wallet.freezeOutput(keyImage);
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

            // thaw outputs
            for (String keyImage : frozenKeyImages) wallet.thawOutput(keyImage);
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
        synchronized (walletLock) {
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
        MoneroWallet wallet = getWallet();
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
                    MoneroCheckTx tradeFeeCheck = wallet.checkTxKey(txHash, txKey, feeAddress);
                    if (!tradeFeeCheck.isGood()) throw new RuntimeException("Invalid proof to trade fee address");
                    actualTradeFee = tradeFeeCheck.getReceivedAmount();
                }

                // verify proof to transfer address
                MoneroCheckTx transferCheck = wallet.checkTxKey(txHash, txKey, sendAddress);
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
    private BigInteger getFeeEstimate(long txWeight) {

        // get fee priority
        MoneroTxPriority priority;
        if (PROTOCOL_FEE_PRIORITY == MoneroTxPriority.DEFAULT) {
            priority = wallet.getDefaultFeePriority();
        } else {
            priority = PROTOCOL_FEE_PRIORITY;
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

    public MoneroTx getDaemonTx(String txHash) {
        List<MoneroTx> txs = getDaemonTxs(Arrays.asList(txHash));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public List<MoneroTx> getDaemonTxs(List<String> txHashes) {
        synchronized (txCache) {

            // fetch txs
            if (getMonerod() == null) xmrConnectionService.verifyConnection(); // will throw
            List<MoneroTx> txs = getMonerod().getTxs(txHashes, true);

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

            // shut down threads
            synchronized (lock) {
                List<Runnable> shutDownThreads = new ArrayList<>();
                shutDownThreads.add(() -> ThreadUtils.shutDown(THREAD_ID));
                ThreadUtils.awaitTasks(shutDownThreads);
            }

            // close main wallet, force close if syncing
            if (isSyncing()) forceCloseMainWallet();
            else if (wallet != null) {
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

    public synchronized XmrAddressEntry getNewAddressEntry() {
        return getNewAddressEntryAux(null, XmrAddressEntry.Context.AVAILABLE);
    }

    public synchronized XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {

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
            xmrAddressEntryList.swapToAvailable(e);
            saveAddressEntryList();
        });
    }

    public synchronized void cloneAddressEntries(String offerId, String cloneOfferId) {
        List<XmrAddressEntry> entries = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).collect(Collectors.toList());
        for (XmrAddressEntry entry : entries) {
            XmrAddressEntry clonedEntry = new XmrAddressEntry(entry.getSubaddressIndex(), entry.getAddressString(), entry.getContext(), cloneOfferId, null);
            Optional<XmrAddressEntry> existingEntry = getAddressEntry(clonedEntry.getOfferId(), clonedEntry.getContext());
            if (existingEntry.isPresent()) continue;
            xmrAddressEntryList.addAddressEntry(clonedEntry);
        }
    }

    public synchronized void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);

        // skip if failed trade is scheduled for processing // TODO: do not call this function in this case?
        if (tradeManager.hasFailedScheduledTrade(offerId)) {
            log.warn("Refusing to reset address entries because trade is scheduled for deletion with offerId={}", offerId);
            return;
        }

        swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.OFFER_FUNDING);

        // swap trade payout to available if applicable
        if (tradeManager == null) return;
        Trade trade = tradeManager.getTrade(offerId);
        if (trade == null || trade.isPayoutFinalized()) swapAddressEntryToAvailable(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    public synchronized void swapPayoutAddressEntryToAvailable(String offerId) {
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
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream().filter(entry -> !tradeManager.getOpenOfferManager().getOpenOffer(entry.getOfferId()).isPresent()));
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream().filter(entry -> tradeManager.getTrade(entry.getOfferId()) == null || tradeManager.getTrade(entry.getOfferId()).isPayoutFinalized()));
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
            if (wasWalletSynced && !isSyncingWithProgress) {
                ThreadUtils.execute(() -> {
                    onConnectionChanged(connection);
                 }, THREAD_ID);
            } else {

                // check if ignored
                if (wallet == null || isShutDownStarted) return;
                if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) {
                    updatePollPeriod();
                    return;
                }

                // force restart main wallet if connection changed while syncing
                log.warn("Force restarting main wallet because connection changed while syncing");
                forceRestartMainWallet();
            }
        });

        // initialize main wallet when daemon synced
        numInitSyncAttempts = 0;
        walletInitListener = (obs, oldVal, newVal) -> initMainWalletIfConnected();
        xmrConnectionService.downloadPercentageProperty().addListener(walletInitListener);
        initMainWalletIfConnected();
    }

    private void initMainWalletIfConnected() {
        if (wallet == null && xmrConnectionService.downloadPercentageProperty().get() == 1 && !isShutDownStarted) {
            requestInitMainWallet();
        }
    }

    private void requestInitMainWallet() {
        ThreadUtils.execute(() -> {
            initMainWallet();
        }, THREAD_ID);
    }

    private void initMainWallet() {
        synchronized (walletLock) {
            if (wallet != null) return;
            if (isShutDownStarted) return;
            try {
                openOrCreateMainWallet();
                startPolling();
            } catch (Exception e) {
                log.warn("Error initializing main wallet: {}\n", e.getMessage(), e);
                HavenoUtils.setTopError(e.getMessage());
                throw e;
            }
        }
    }

    private void resetIfWalletChanged() {
        getAddressEntryListAsImmutableList(); // TODO: using getter to create base address if necessary
        List<XmrAddressEntry> baseAddresses = getAddressEntries(XmrAddressEntry.Context.BASE_ADDRESS);
        if (baseAddresses.size() > 1 || (baseAddresses.size() == 1 && !baseAddresses.get(0).getAddressString().equals(wallet.getPrimaryAddress()))) {
            String warningMsg = "New Monero wallet detected. Resetting internal state.";
            if (!tradeManager.getOpenTrades().isEmpty()) warningMsg += "\n\nWARNING: Your open trades will settle to the payout address in the OLD wallet!"; // TODO: allow payout address to be updated in PaymentSentMessage, PaymentReceivedMessage, and DisputeOpenedMessage?
            HavenoUtils.setTopError(warningMsg);

            // reset address entries
            xmrAddressEntryList.clear();
            getAddressEntryListAsImmutableList(); // recreate base address

            // cancel offers
            tradeManager.getOpenOfferManager().removeAllOpenOffers(null);
        }
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
            String errorMsg = "Could not create wallet '" + config.getPath() + "': " + e.getMessage();
            log.warn(errorMsg + "\n", e);
            if (walletFull != null) forceCloseWallet(walletFull, config.getPath());
            throw new IllegalStateException(errorMsg);
        }
    }

    private MoneroWalletFull openWalletFull(MoneroWalletConfig config, boolean applyProxyUri) {
        MoneroWalletFull walletFull = null;
        try {

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            if (!applyProxyUri) connection.setProxyUri(null);

            // try opening wallet
            config.setNetworkType(getMoneroNetworkType());
            config.setServer(connection);
            log.info("Opening full wallet '{}' with monerod={}, proxyUri={}", config.getPath(), connection.getUri(), connection.getProxyUri());
            try {
                walletFull = MoneroWalletFull.openWallet(config);
            } catch (Exception e) {
                if (isShutDownStarted) throw e;
                log.warn("Failed to open full wallet '{}', attempting to use backup cache files, error={}", config.getPath(), e.getMessage());
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
                            log.warn("Failed to open full wallet '{}' using backup cache files, retrying with cache deleted", config.getPath());
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
                            log.warn("Failed to open full wallet '{}' after deleting cache, restoring original cache", config.getPath());
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
            log.info("Done opening full wallet " + config.getPath());
            return walletFull;
        } catch (Exception e) {
            String errorMsg = "Could not open full wallet '" + config.getPath() + "': " + e.getMessage();
            log.warn(errorMsg + "\n", e);
            if (walletFull != null) forceCloseWallet(walletFull, config.getPath());
            throw new IllegalStateException(errorMsg);
        }
    }

    private MoneroWalletRpc createWalletRpc(MoneroWalletConfig config, Integer port) {

        // must be connected to daemon
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            if (!isProxyApplied(false)) connection.setProxyUri(null);

            // create wallet
            if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet '" + config.getPath() + "' because shutdown is started");
            log.info("Creating RPC wallet " + config.getPath() + " connected to monerod=" + connection.getUri());
            long time = System.currentTimeMillis();
            config.setServer(connection);
            walletRpc.createWallet(config);
            walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done creating RPC wallet " + config.getPath() + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletRpc;
        } catch (Exception e) {
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            if (!isShutDownStarted) log.warn("Could not create RPC wallet '" + config.getPath() + "': " + e.getMessage() + "\n", e);
            throw new IllegalStateException("Could not create wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes in your task manager, and restart Haveno.\n\nError message: " + e.getMessage(), e);
        }
    }

    private MoneroWalletRpc openWalletRpc(MoneroWalletConfig config, Integer port, boolean applyProxyUri) {
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // get daemon connection from service
            MoneroRpcConnection serviceConnection = xmrConnectionService.getConnection();
            if (serviceConnection == null) throw new IllegalStateException("Cannot open wallet '" + config.getPath() + "' via RPC because daemon connection is null");

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(serviceConnection);
            if (!applyProxyUri) connection.setProxyUri(null);

            // try opening wallet
            if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet '" + config.getPath() + "' because shutdown is started");
            log.info("Opening RPC wallet '{}' with monerod={}, proxyUri={}", config.getPath(), connection.getUri(), connection.getProxyUri());
            config.setServer(connection);
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
            if (walletRpc.getDaemonConnection() != null) walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_RPC_STACK_TRACE);
            log.info("Done opening RPC wallet " + config.getPath());
            return walletRpc;
        } catch (Exception e) {
            if (walletRpc != null) forceCloseWallet(walletRpc, config.getPath());
            if (!isShutDownStarted) log.warn("Could not open RPC wallet '{}': {}\n", config.getPath(), e.getMessage(), e);
            throw new IllegalStateException("Could not open wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes in your task manager, and restart Haveno.\n\nError message: " + e.getMessage(), e);
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port) {

        // check if monero-wallet-rpc exists
        if (!new File(MONERO_WALLET_RPC_PATH).exists()) throw new RuntimeException("monero-wallet-rpc executable doesn't exist at path " + MONERO_WALLET_RPC_PATH
                + "; copy monero-wallet-rpc to the project root or set WalletConfig.java MONERO_WALLET_RPC_PATH for your system");

        // build command to start monero-wallet-rpc
        List<String> cmd = new ArrayList<>(Arrays.asList( // modifiable list
                MONERO_WALLET_RPC_PATH,
                "--rpc-login",
                MONERO_WALLET_RPC_USERNAME + ":" + MONERO_WALLET_RPC_DEFAULT_PASSWORD,
                "--wallet-dir", walletDir.toString()));

        // omit --mainnet flag since it does not exist
        if (MONERO_NETWORK_TYPE != MoneroNetworkType.MAINNET) {
            cmd.add("--" + MONERO_NETWORK_TYPE.toString().toLowerCase());
        }

        // set rpc bind port
        if (port != null && port > 0) {
            cmd.add("--rpc-bind-port");
            cmd.add(Integer.toString(port));
        }

        // start monero-wallet-rpc instance
        return MONERO_WALLET_RPC_MANAGER.startInstance(cmd);
    }

    @Override
    protected void onConnectionChanged(MoneroRpcConnection connection) {
        synchronized (walletLock) {

            // configure current connection
            connection = xmrConnectionService.getConnection();
            if (!isProxyApplied(wasWalletSynced)) connection.setProxyUri(null);

            // check if ignored
            if (wallet == null || isShutDownStarted) return;
            if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) {
                updatePollPeriod();
                return;
            }

            // set daemon connection
            log.info("Setting daemon connection for main wallet, monerod={}, proxyUri={}", connection == null ? null : connection.getUri(), connection == null ? null : connection.getProxyUri());
            wallet.setDaemonConnection(connection);

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
                wallet.changePassword(oldPassword, newPassword);
                saveWallet();
            } catch (Exception e) {
                log.warn("Error changing main wallet password: " + e.getMessage() + "\n", e);
                throw e;
            }
        });

        // create tasks to change trade wallet passwords
        List<Trade> trades = tradeManager.getAllTrades();
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
            if (isShutDownStarted) throw new IllegalStateException("Cannot open or create main wallet because shut down has started");
            if (wallet == null) {
                MoneroDaemonRpc monerod = xmrConnectionService.getMonerod();
                log.info("Initializing main wallet with monerod=" + (monerod == null ? "null" : monerod.getRpcConnection().getUri()));
                if (walletExists(MONERO_WALLET_NAME)) {
                    wallet = openWallet(MONERO_WALLET_NAME, rpcBindPort, isProxyApplied(wasWalletSynced));
                } else if (Boolean.TRUE.equals(xmrConnectionService.isConnected())) {
                    wallet = createWallet(MONERO_WALLET_NAME, rpcBindPort);

                    // set wallet creation date to yesterday to guarantee complete restore
                    LocalDateTime localDateTime = LocalDate.now().atStartOfDay().minusDays(1);
                    long date = localDateTime.toEpochSecond(ZoneOffset.UTC);
                    user.setWalletCreationDate(date);
                }
                if (wallet != null) walletHeight.set(wallet.getHeight());
                isClosingWallet = false;

                // cache wallet info
                cacheWalletInfo();

                // reset internal state if wallet changed
                resetIfWalletChanged();
            }
            return wallet;
        }
    }

    private void closeMainWallet(boolean save) {
        stopPolling();
        synchronized (walletLock) {
            try {
                if (wallet != null) {
                    isClosingWallet = true;
                    log.debug("Closing main wallet");
                    if (shouldBackup(wallet)) backupWallet(MONERO_WALLET_NAME);
                    closeWallet(wallet, true);
                    wallet = null;
                }
            } catch (Exception e) {
                log.warn("Error closing main wallet: {}. Was Haveno stopped manually with ctrl+c?", e.getMessage());
            }
        }
    }

    private void forceCloseMainWallet() {
        stopPolling();
        if (wallet != null && !isClosingWallet) {
            MoneroWallet walletRef = wallet;
            wallet = null; // nullify wallet before force closing so state is updated for error handling
            forceCloseWallet(walletRef, getWalletPath(MONERO_WALLET_NAME));
        }
    }

    public void forceRestartMainWallet() {
        log.warn("Force restarting main wallet");
        if (isClosingWallet) return;
        forceCloseMainWallet();
        initMainWallet();
    }

    public void handleWalletError(Exception e, MoneroRpcConnection sourceConnection, int numAttempts) {
        if (HavenoUtils.isUnresponsive(e)) forceCloseMainWallet(); // wallet can be stuck a while
        if (numAttempts % TradeProtocol.REQUEST_CONNECTION_SWITCH_EVERY_NUM_ATTEMPTS == 0) requestSwitchToNextBestConnection(sourceConnection); // request connection switch every n attempts
        initMainWallet();
    }

    private void startPolling() {
        synchronized (walletLock) {
            if (isShutDownStarted || isPolling()) return;
            updatePollPeriod();
            pollLooper = new TaskLooper(() -> new Thread(() -> pollWallet()).start());
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
        doPollWallet(true);
    }

    public void doPollWallet(boolean updateTxs) {

        // skip if shut down started
        MoneroWallet sourceWallet = wallet;
        if (isShutDownStarted || sourceWallet == null) return;
        MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();

        // set poll in progress
        boolean pollInProgressSet = false;
        synchronized (pollLock) {
            if (!pollInProgress) pollInProgressSet = true;
            pollInProgress = true;
        }

        // poll wallet
        try {

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
                if (System.currentTimeMillis() - lastLogDaemonNotSyncedTimestamp > HavenoUtils.LOG_MONEROD_NOT_SYNCED_WARN_PERIOD_MS) {
                    log.warn("Monero daemon is not synced within tolerance, height={}, targetHeight={}, monerod={}", xmrConnectionService.chainHeightProperty().get(), xmrConnectionService.getTargetHeight(), xmrConnectionService.getConnection() == null ? null : xmrConnectionService.getConnection().getUri());
                    lastLogDaemonNotSyncedTimestamp = System.currentTimeMillis();
                }
                return;
            }

            // skip polling if trades are reserving main wallet
            // TODO: we disable reserving main wallet with too many trades to prevent wallet from starving (usually only happens from api tests)
            List<Trade> tradesReservingMainWallet = tradeManager.getTradesReservingMainWallet();
            if (tradesReservingMainWallet.size() >= 1 && tradesReservingMainWallet.size() <= 2) {
                List<String> tradeIds = tradesReservingMainWallet.stream().map(Trade::getShortId).collect(Collectors.toList());
                log.info("Skipping main wallet poll because trades are reserving main wallet: " + tradeIds);
                return;
            }

            // sync wallet if first sync or behind daemon
            boolean isFirstSync = !wasWalletSynced;
            if (isFirstSync || walletHeight.get() < xmrConnectionService.getTargetHeight()) {
                if (isFirstSync) log.info("Syncing main wallet from height " + walletHeight.get());
                long startTime = System.currentTimeMillis();
                synchronized (walletLock) { // avoid long sync from blocking other operations
                    syncWithProgress();
                }
                if (isFirstSync) log.info("Done syncing main wallet in " + (System.currentTimeMillis() - startTime) + " ms");
            }

            // fetch transactions from pool and store to cache
            // TODO: ideally wallet should sync every poll and then avoid updating from pool on fetching txs?
            if (updateTxs) {
                synchronized (walletLock) { // avoid long fetch from blocking other operations
                    synchronized (HavenoUtils.getDaemonLock()) {
                        if (lastPollTxsTimestamp == 0) lastPollTxsTimestamp = System.currentTimeMillis(); // set initial timestamp
                        try {
                            cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true));
                            lastPollTxsTimestamp = System.currentTimeMillis();
                        } catch (Exception e) { // fetch from pool can fail
                            if (!isShutDownStarted && wallet != sourceWallet) {

                                // throttle error handling
                                if (System.currentTimeMillis() - lastLogPollErrorTimestamp > HavenoUtils.LOG_POLL_ERROR_PERIOD_MS) {
                                    log.warn("Error polling main wallet's transactions from the pool: {}", e.getMessage());
                                    lastLogPollErrorTimestamp = System.currentTimeMillis();
                                    if (System.currentTimeMillis() - lastPollTxsTimestamp > POLL_TXS_TOLERANCE_MS) ThreadUtils.submitToPool(() -> requestSwitchToNextBestConnection(sourceConnection));
                                }
                            }
                        }
                    }
                }
            }

            // handle first wallet sync
            if (isFirstSync) onFirstSync();
        } catch (Exception e) {
            if (isShutDownStarted || wallet == null || wallet != sourceWallet) return; // skip error handling if shut down or another thread force restarts while polling

            // fallback if unable to sync wallet on startup after max attempts
            if (!wasWalletSynced && !isWalletServiceInitialized()) {
                log.warn("Failed to sync main wallet on startup, attempt={}/{}", numInitSyncAttempts + 1, MAX_SYNC_ATTEMPTS);
                numInitSyncAttempts++;
                if (numInitSyncAttempts >= MAX_SYNC_ATTEMPTS) {
                    log.warn("Opening application without syncing main wallet", numInitSyncAttempts);
                    UserThread.execute(() -> {
                        onWalletServiceInitialized();
                    });
                }
            }

            // handle unresponsive wallet
            if (HavenoUtils.isUnresponsive(e)) {
                if (wasWalletSynced || isWalletServiceInitialized()) {
                    forceRestartMainWallet();
                } else {
                    forceCloseMainWallet();
                    requestSwitchToNextBestConnection(sourceConnection); // request connection switch on startup failures
                    initMainWallet();
                }
            }
            else if (Boolean.TRUE.equals(xmrConnectionService.isConnected())) {
                if (isExpectedWalletError(e)) {
                    log.warn("Error polling main wallet, errorMessage={}. Monerod={}", e.getMessage(), getXmrConnectionService().getConnection());
                } else {
                    log.warn("Error polling main wallet, errorMessage={}. Monerod={}", e.getMessage(), getXmrConnectionService().getConnection(), e); // include stack trace for unexpected errors
                }
            }
        } finally {
            if (pollInProgressSet) {
                synchronized (pollLock) {
                    pollInProgress = false;
                }
            }
            requestSaveWalletIfElapsedTime();

            // cache wallet info last
            synchronized (walletLock) {
                if (wallet != null && !isShutDownStarted) {
                    try {
                        cacheWalletInfo();
                    } catch (Exception e) {
                        log.warn("Error caching wallet info: " + e.getMessage() + "\n", e);
                    }
                }
            }
        }
    }

    private void onFirstSync() {
        if (walletInitListener != null) xmrConnectionService.downloadPercentageProperty().removeListener(walletInitListener);

        // log wallet balances
        if (getMoneroNetworkType() != MoneroNetworkType.MAINNET) {
            BigInteger balance = getBalance();
            BigInteger unlockedBalance = getAvailableBalance();
            log.info("Monero wallet unlocked balance={}, pending balance={}, total balance={}", unlockedBalance, balance.subtract(unlockedBalance), balance);
        }

        // reapply connection after wallet synced (might reinitialize wallet with proxy)
        onConnectionChanged(xmrConnectionService.getConnection());

        UserThread.execute(() -> {
            
            // signal that main wallet is synced
            syncProgressListener.doneDownload();

            // notify setup that main wallet is initialized
            // TODO: app fully initializes after this is set to true, even though wallet might not be initialized if unconnected. wallet will be created when connection detected
            // refactor startup to call this and sync off main thread? but the calls to e.g. getBalance() fail with 'wallet and network is not yet initialized'
            onWalletServiceInitialized();
        });
    }

    private void onWalletServiceInitialized() {
        HavenoUtils.havenoSetup.getWalletInitialized().set(true);
    }

    private boolean isWalletServiceInitialized() {
        return HavenoUtils.havenoSetup.getWalletInitialized().get();
    }

    private boolean requestSwitchToNextBestConnection() {
        return requestSwitchToNextBestConnection(null);
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

    private void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
        updateBalanceListeners();
        for (MoneroWalletListenerI listener : walletListeners) listener.onBalancesChanged(newBalance, newUnlockedBalance);
    }
}
