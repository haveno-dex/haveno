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
import haveno.common.util.Tuple2;
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
import monero.daemon.model.MoneroFeeEstimate;
import monero.daemon.model.MoneroNetworkType;
import monero.daemon.model.MoneroOutput;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
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

    // Monero configuration
    public static final int NUM_BLOCKS_UNLOCK = 10;
    public static final String MONERO_WALLET_RPC_DIR = Config.baseCurrencyNetwork().isTestnet() ? System.getProperty("user.dir") + File.separator + ".localnet" : Config.appDataDir().getAbsolutePath(); // .localnet contains monero-wallet-rpc and wallet files
    public static final String MONERO_WALLET_RPC_NAME = Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc";
    public static final String MONERO_WALLET_RPC_PATH = MONERO_WALLET_RPC_DIR + File.separator + MONERO_WALLET_RPC_NAME;
    public static final double MINER_FEE_TOLERANCE = 0.25; // miner fee must be within percent of estimated fee
    public static final MoneroTxPriority PROTOCOL_FEE_PRIORITY = MoneroTxPriority.ELEVATED;
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = getMoneroNetworkType();
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    private static final String KEYS_FILE_POSTFIX = ".keys";
    private static final String ADDRESS_FILE_POSTFIX = ".address.txt";
    private static final int NUM_MAX_WALLET_BACKUPS = 1;
    private static final int MONERO_LOG_LEVEL = 0;
    private static final int MAX_SYNC_ATTEMPTS = 3;
    private static final boolean PRINT_STACK_TRACE = false;
    private static final String THREAD_ID = XmrWalletService.class.getSimpleName();

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
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private ChangeListener<? super Number> walletInitListener;
    private TradeManager tradeManager;
    private MoneroWalletRpc wallet;
    private Object walletLock = new Object();
    private boolean wasWalletSynced = false;
    private final Map<String, Optional<MoneroTx>> txCache = new HashMap<String, Optional<MoneroTx>>();
    private boolean isShutDownStarted = false;
    private ExecutorService syncWalletThreadPool = Executors.newFixedThreadPool(10); // TODO: adjust based on connection type
    private Long syncStartHeight = null;
    private TaskLooper syncLooper = null;
    private BigInteger cachedBalance = null;
    private BigInteger cachedAvailableBalance = null;
    private List<MoneroSubaddress> cachedSubaddresses;
    private List<MoneroTxWallet> cachedTxs;

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
        this.xmrConnectionService = xmrConnectionService;
        this.walletsSetup = walletsSetup;
        this.xmrAddressEntryList = xmrAddressEntryList;
        this.walletDir = walletDir;
        this.rpcBindPort = rpcBindPort;
        this.xmrWalletFile = new File(walletDir, MONERO_WALLET_NAME);

        // set monero logging
        MoneroUtils.setLogLevel(MONERO_LOG_LEVEL);

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
        if (targetHeight - walletHeight.get() <= 3) return true; // synced if within 3 blocks of target height
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
        return preferences.isProxyApplied(wasWalletSynced);
    }

    public String getWalletPassword() {
        return accountService.getPassword() == null ? MONERO_WALLET_RPC_DEFAULT_PASSWORD : accountService.getPassword();
    }

    public boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + KEYS_FILE_POSTFIX).exists();
    }

    public MoneroWalletRpc createWallet(String walletName) {
        log.info("{}.createWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        return createWalletRpc(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
                null);
    }

    public MoneroWalletRpc openWallet(String walletName, boolean applyProxyUri) {
        log.info("{}.openWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet because shutting down");
        return openWalletRpc(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
            null,
            applyProxyUri);
    }

    /**
     * Sync the given wallet in a thread pool with other wallets.
     */
    public MoneroSyncResult syncWallet(MoneroWallet wallet) {
        Callable<MoneroSyncResult> task = () -> wallet.sync();
        Future<MoneroSyncResult> future = syncWalletThreadPool.submit(task);
        try {
            return future.get();
        } catch (Exception e) {
            throw new MoneroError(e.getMessage());
        }
    }

    private MoneroSyncResult syncWallet() {
        return syncWallet(wallet);
    }

    public void saveWallet(MoneroWallet wallet) {
        saveWallet(wallet, false);
    }

    public void saveWallet(MoneroWallet wallet, boolean backup) {
        wallet.save();
        if (backup) backupWallet(wallet.getPath());
    }

    public void closeWallet(MoneroWallet wallet, boolean save) {
        log.info("{}.closeWallet({}, {})", getClass().getSimpleName(), wallet.getPath(), save);
        MoneroError err = null;
        String path = wallet.getPath();
        try {
            wallet.close(save);
            if (save) backupWallet(path);
        } catch (MoneroError e) {
            err = e;
        }
        stopWallet(wallet, path);
        if (err != null) throw err;
    }

    public void stopWallet(MoneroWallet wallet, String path) {
        stopWallet(wallet, path, false);
    }

    public void stopWallet(MoneroWallet wallet, String path, boolean force) {
        MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet, path, force);
    }

    public void deleteWallet(String walletName) {
        log.info("{}.deleteWallet({})", getClass().getSimpleName(), walletName);
        if (!walletExists(walletName)) throw new Error("Wallet does not exist at path: " + walletName);
        String path = walletDir.toString() + File.separator + walletName;
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet cache file: " + path);
        if (!new File(path + KEYS_FILE_POSTFIX).delete()) throw new RuntimeException("Failed to delete wallet keys file: " + path + KEYS_FILE_POSTFIX);
        if (!new File(path + ADDRESS_FILE_POSTFIX).delete() && !Config.baseCurrencyNetwork().isMainnet()) throw new RuntimeException("Failed to delete wallet address file: " + path + ADDRESS_FILE_POSTFIX); // mainnet does not have address file by default
    }

    public void backupWallet(String walletName) {
        FileUtil.rollingBackup(walletDir, walletName, NUM_MAX_WALLET_BACKUPS);
        FileUtil.rollingBackup(walletDir, walletName + KEYS_FILE_POSTFIX, NUM_MAX_WALLET_BACKUPS);
        FileUtil.rollingBackup(walletDir, walletName + ADDRESS_FILE_POSTFIX, NUM_MAX_WALLET_BACKUPS);
    }

    public void deleteWalletBackups(String walletName) {
        FileUtil.deleteRollingBackup(walletDir, walletName);
        FileUtil.deleteRollingBackup(walletDir, walletName + KEYS_FILE_POSTFIX);
        FileUtil.deleteRollingBackup(walletDir, walletName + ADDRESS_FILE_POSTFIX);
    }

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        synchronized (walletLock) {
            try {
                MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
                //printTxs("XmrWalletService.createTx", tx);
                return tx;
            } catch (Exception e) {
                throw e;
            }
        }
    }

    /**
     * Thaw all outputs not reserved for a trade.
     */
    public void thawUnreservedOutputs() {
        synchronized (walletLock) {

            // collect reserved outputs
            Set<String> reservedKeyImages = new HashSet<String>();
            for (Trade trade : tradeManager.getObservableList()) {
                if (trade.getSelf().getReserveTxKeyImages() == null) continue;
                reservedKeyImages.addAll(trade.getSelf().getReserveTxKeyImages());
            }
            for (OpenOffer openOffer : tradeManager.getOpenOfferManager().getObservableList()) {
                if (openOffer.getOffer().getOfferPayload().getReserveTxKeyImages() == null) continue;
                reservedKeyImages.addAll(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages());
            }

            // ensure wallet is open
            if (wallet == null) {
                log.warn("Cannot thaw unreserved outputs because wallet not open");
                return;
            }

            // thaw unreserved outputs
            Set<String> unreservedFrozenKeyImages = wallet.getOutputs(new MoneroOutputQuery()
                    .setIsFrozen(true)
                    .setIsSpent(false))
                    .stream()
                    .map(output -> output.getKeyImage().getHex())
                    .collect(Collectors.toSet());
            unreservedFrozenKeyImages.removeAll(reservedKeyImages);
            if (!unreservedFrozenKeyImages.isEmpty()) {
                log.warn("Thawing outputs which are not reserved for offer or trade: " + unreservedFrozenKeyImages);
                thawOutputs(unreservedFrozenKeyImages);
            }
        }
    }

    /**
     * Freeze the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to freeze
     */
    public void freezeOutputs(Collection<String> keyImages) {
        synchronized (walletLock) {
            for (String keyImage : keyImages) wallet.freezeOutput(keyImage);
            saveMainWallet();
            cacheWalletState();
        }
        updateBalanceListeners(); // TODO (monero-java): balance listeners not notified on freeze/thaw output
    }

    /**
     * Thaw the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to thaw
     */
    public void thawOutputs(Collection<String> keyImages) {
        synchronized (walletLock) {
            for (String keyImage : keyImages) wallet.thawOutput(keyImage);
            saveMainWallet();
            cacheWalletState();
        }
        updateBalanceListeners(); // TODO (monero-java): balance listeners not notified on freeze/thaw output
    }

    private List<Integer> getSubaddressesWithExactInput(BigInteger amount) {

        // fetch unspent, unfrozen, unlocked outputs
        List<MoneroOutputWallet> exactOutputs = wallet.getOutputs(new MoneroOutputQuery()
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
     * to the sender's payout address less the security deposit and mining fee.
     *
     * @param tradeFee trade fee
     * @param sendAmount amount to give peer
     * @param securityDeposit security deposit amount
     * @param returnAddress return address for reserved funds
     * @param reserveExactAmount specifies to reserve the exact input amount
     * @param preferredSubaddressIndex preferred source subaddress to spend from (optional)
     * @return a transaction to reserve a trade
     */
    public MoneroTxWallet createReserveTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String returnAddress, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        log.info("Creating reserve tx with preferred subaddress index={}, return address={}", preferredSubaddressIndex, returnAddress);
        long time = System.currentTimeMillis();
        MoneroTxWallet reserveTx = createTradeTx(tradeFee, sendAmount, securityDeposit, returnAddress, reserveExactAmount, preferredSubaddressIndex);
        log.info("Done creating reserve tx in {} ms", System.currentTimeMillis() - time);
        return reserveTx;
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

            // thaw reserved outputs
            if (trade.getSelf().getReserveTxKeyImages() != null) {
                thawOutputs(trade.getSelf().getReserveTxKeyImages());
            }

            // create deposit tx
            String multisigAddress = trade.getProcessModel().getMultisigAddress();
            BigInteger tradeFee = trade instanceof MakerTrade ? trade.getOffer().getMakerFee() : trade.getTakerFee();
            BigInteger sendAmount = trade instanceof BuyerTrade ? BigInteger.ZERO : trade.getAmount();
            BigInteger securityDeposit = trade instanceof BuyerTrade ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
            long time = System.currentTimeMillis();
            log.info("Creating deposit tx with multisig address={}", multisigAddress);
            MoneroTxWallet depositTx = createTradeTx(tradeFee, sendAmount, securityDeposit, multisigAddress, reserveExactAmount, preferredSubaddressIndex);
            log.info("Done creating deposit tx for trade {} {} in {} ms", trade.getClass().getSimpleName(), trade.getId(), System.currentTimeMillis() - time);
            return depositTx;
        }
    }

    private MoneroTxWallet createTradeTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, boolean reserveExactAmount, Integer preferredSubaddressIndex) {
        synchronized (walletLock) {
            MoneroWallet wallet = getWallet();

            // create a list of subaddresses to attempt spending from in preferred order
            List<Integer> subaddressIndices = new ArrayList<Integer>();
            if (reserveExactAmount) {
                BigInteger exactInputAmount = tradeFee.add(sendAmount).add(securityDeposit);
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
                    return createTradeTxFromSubaddress(tradeFee, sendAmount, securityDeposit, address, reserveExactAmount, subaddressIndices.get(i));
                } catch (Exception e) {
                    if (i == subaddressIndices.size() - 1 && reserveExactAmount) throw e; // throw if no subaddress with exact output
                }
            }

            // try any subaddress
            return createTradeTxFromSubaddress(tradeFee, sendAmount, securityDeposit, address, reserveExactAmount, null);
        }
    }

    private MoneroTxWallet createTradeTxFromSubaddress(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, boolean reserveExactAmount, Integer subaddressIndex) {

        // create tx
        MoneroTxWallet tradeTx = wallet.createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .setSubaddressIndices(subaddressIndex)
                .addDestination(HavenoUtils.getTradeFeeAddress(), tradeFee)
                .addDestination(address, sendAmount.add(securityDeposit))
                .setSubtractFeeFrom(1)
                .setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY)); // pay fee from security deposit

        // freeze inputs
        List<String> keyImages = new ArrayList<String>();
        for (MoneroOutput input : tradeTx.getInputs()) keyImages.add(input.getKeyImage().getHex());
        freezeOutputs(keyImages);
        saveMainWallet();
        return tradeTx;
    }

    /**
     * Verify a reserve or deposit transaction.
     * Checks double spends, trade fee, deposit amount and destination, and miner fee.
     * The transaction is submitted to the pool then flushed without relaying.
     *
     * @param offerId id of offer to verify trade tx
     * @param tradeFee trade fee
     * @param sendAmount amount to give peer
     * @param securityDeposit security deposit amount
     * @param address expected destination address for the deposit amount
     * @param txHash transaction hash
     * @param txHex transaction hex
     * @param txKey transaction key
     * @param keyImages expected key images of inputs, ignored if null
     * @return tuple with the verified tx and its actual security deposit
     */
    public Tuple2<MoneroTx, BigInteger> verifyTradeTx(String offerId, BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, String txHash, String txHex, String txKey, List<String> keyImages) {
        if (txHash == null) throw new IllegalArgumentException("Cannot verify trade tx with null id");
        MoneroDaemonRpc daemon = getDaemon();
        MoneroWallet wallet = getWallet();
        MoneroTx tx = null;
        BigInteger actualSecurityDeposit = null;
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
                BigInteger feeEstimate = getElevatedFeeEstimate(tx.getWeight());
                double feeDiff = tx.getFee().subtract(feeEstimate).abs().doubleValue() / feeEstimate.doubleValue();
                if (feeDiff > MINER_FEE_TOLERANCE) throw new Error("Miner fee is not within " + (MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + feeEstimate + " but was " + tx.getFee());
                log.info("Trade tx fee {} is within tolerance, diff%={}", tx.getFee(), feeDiff);

                // verify transfer proof to fee address
                MoneroCheckTx tradeFeeCheck = wallet.checkTxKey(txHash, txKey, HavenoUtils.getTradeFeeAddress());
                if (!tradeFeeCheck.isGood()) throw new RuntimeException("Invalid proof to trade fee address");

                // verify transfer proof to address
                MoneroCheckTx transferCheck = wallet.checkTxKey(txHash, txKey, address);
                if (!transferCheck.isGood()) throw new RuntimeException("Invalid proof to transfer address");

                // collect actual trade fee, send amount, and security deposit
                BigInteger actualTradeFee = tradeFeeCheck.getReceivedAmount();
                actualSecurityDeposit = transferCheck.getReceivedAmount().subtract(sendAmount);
                BigInteger actualSendAmount = transferCheck.getReceivedAmount().subtract(actualSecurityDeposit);

                // verify trade fee
                if (actualTradeFee.compareTo(tradeFee) < 0) {
                    throw new RuntimeException("Insufficient trade fee, expected=" + tradeFee + ", actual=" + actualTradeFee + ", transfer address check=" + JsonUtils.serialize(transferCheck) + ", trade fee address check=" + JsonUtils.serialize(tradeFeeCheck));
                }

                // verify send amount
                if (!actualSendAmount.equals(sendAmount)) {
                    throw new RuntimeException("Unexpected send amount, expected " + sendAmount + " but was " + actualSendAmount);
                }

                // verify security deposit
                BigInteger expectedSecurityDeposit = securityDeposit.subtract(tx.getFee()); // fee is paid from security deposit
                if (!actualSecurityDeposit.equals(expectedSecurityDeposit)) {
                    throw new RuntimeException("Unexpected security deposit amount, expected " + expectedSecurityDeposit + " but was " + actualSecurityDeposit);
                }
            } catch (Exception e) {
                log.warn("Error verifying trade tx with offer id=" + offerId + (tx == null ? "" : ", tx=" + tx) + ": " + e.getMessage());
                throw e;
            } finally {
                try {
                    daemon.flushTxPool(txHash); // flush tx from pool
                } catch (MoneroRpcError err) {
                    System.out.println(daemon.getRpcConnection());
                    throw err.getCode().equals(-32601) ? new RuntimeException("Failed to flush tx from pool. Arbitrator must use trusted, unrestricted daemon") : err;
                }
            }
            return new Tuple2<>(tx, actualSecurityDeposit);
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

    public MoneroTx getTx(String txHash) {
        List<MoneroTx> txs = getTxs(Arrays.asList(txHash));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public List<MoneroTx> getTxs(List<String> txHashes) {
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

    public MoneroTx getTxWithCache(String txHash) {
        List<MoneroTx> cachedTxs = getTxsWithCache(Arrays.asList(txHash));
        return cachedTxs.isEmpty() ? null : cachedTxs.get(0);
    }

    public List<MoneroTx> getTxsWithCache(List<String> txHashes) {
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
                return uncachedTxHashes.isEmpty() ? cachedTxs : getTxs(txHashes);
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

        // remove listeners which stops polling wallet
        // TODO monero-java: wallet.stopPolling()?
        synchronized (walletLock) {
            if (wallet != null) {
                for (MoneroWalletListenerI listener : new HashSet<>(wallet.getListeners())) {
                    wallet.removeListener(listener);
                }
            }
        }

        // shut down main wallet
        walletListeners.clear();
        closeMainWallet(true);
        log.info("Done shutting down main wallet");
    }

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void initialize() {

        // listen for connection changes
        xmrConnectionService.addConnectionListener(connection -> {
            ThreadUtils.execute(() -> onConnectionChanged(connection), THREAD_ID);
        });

        // initialize main wallet when daemon synced
        walletInitListener = (obs, oldVal, newVal) -> initMainWalletIfConnected();
        xmrConnectionService.downloadPercentageProperty().addListener(walletInitListener);
        initMainWalletIfConnected();
    }

    private void initMainWalletIfConnected() {
        ThreadUtils.execute(() -> {
            synchronized (walletLock) {
                if (xmrConnectionService.downloadPercentageProperty().get() == 1 && wallet == null && !isShutDownStarted) {
                    maybeInitMainWallet(true);
                    if (walletInitListener != null) xmrConnectionService.downloadPercentageProperty().removeListener(walletInitListener);
                }
            }
        }, THREAD_ID);
    }

    private void maybeInitMainWallet(boolean sync) {
        maybeInitMainWallet(sync, MAX_SYNC_ATTEMPTS);
    }

    private void maybeInitMainWallet(boolean sync, int numAttempts) {
        synchronized (walletLock) {
            if (isShutDownStarted) return;

            // open or create wallet main wallet
            if (wallet == null) {
                MoneroDaemonRpc daemon = xmrConnectionService.getDaemon();
                log.info("Initializing main wallet with monerod=" + (daemon == null ? "null" : daemon.getRpcConnection().getUri()));
                MoneroWalletConfig walletConfig = new MoneroWalletConfig().setPath(MONERO_WALLET_NAME).setPassword(getWalletPassword());
                if (MoneroUtils.walletExists(xmrWalletFile.getPath())) {
                    wallet = openWalletRpc(walletConfig, rpcBindPort, isProxyApplied(wasWalletSynced));
                } else if (xmrConnectionService.getConnection() != null && Boolean.TRUE.equals(xmrConnectionService.getConnection().isConnected())) {
                    wallet = createWalletRpc(walletConfig, rpcBindPort);

                    // set wallet creation date to yesterday to guarantee complete restore
                    LocalDateTime localDateTime = LocalDate.now().atStartOfDay().minusDays(1);
                    long date = localDateTime.toEpochSecond(ZoneOffset.UTC);
                    user.setWalletCreationDate(date);
                }
            }

            // sync wallet and register listener
            if (wallet != null && !isShutDownStarted) {
                log.info("Monero wallet uri={}, path={}", wallet.getRpcConnection().getUri(), wallet.getPath());

                // sync main wallet if applicable
                if (sync && numAttempts > 0) {
                    try {

                        // sync main wallet
                        log.info("Syncing main wallet");
                        long time = System.currentTimeMillis();
                        syncWalletWithProgress(); // blocking
                        log.info("Done syncing main wallet in " + (System.currentTimeMillis() - time) + " ms");
                        cacheWalletState();

                        // log wallet balances
                        if (getMoneroNetworkType() != MoneroNetworkType.MAINNET) {
                            BigInteger balance = getBalance();
                            BigInteger unlockedBalance = getAvailableBalance();
                            log.info("Monero wallet unlocked balance={}, pending balance={}, total balance={}", unlockedBalance, balance.subtract(unlockedBalance), balance);
                        }

                        // reapply connection after wallet synced
                        onConnectionChanged(xmrConnectionService.getConnection());

                        // signal that main wallet is synced
                        doneDownload();

                        // notify setup that main wallet is initialized
                        // TODO: app fully initializes after this is set to true, even though wallet might not be initialized if unconnected. wallet will be created when connection detected
                        // refactor startup to call this and sync off main thread? but the calls to e.g. getBalance() fail with 'wallet and network is not yet initialized'
                        HavenoUtils.havenoSetup.getWalletInitialized().set(true);

                        // save but skip backup on initialization
                        saveMainWallet(false);
                    } catch (Exception e) {
                        log.warn("Error syncing main wallet: {}", e.getMessage());
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

                // register internal listener to notify external listeners
                wallet.addListener(new XmrWalletListener());
            }
        }
    }

    private void syncWalletWithProgress() {
        updateSyncProgress();
        wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
        CountDownLatch latch = new CountDownLatch(1);
        syncLooper = new TaskLooper(() -> {
            if (wallet.getHeight() < xmrConnectionService.getTargetHeight()) updateSyncProgress();
            else {
                syncLooper.stop();
                try {
                    syncWallet(); // ensure finished syncing
                } catch (Exception e) {
                    e.printStackTrace();
                }
                wasWalletSynced = true;
                updateSyncProgress();
                latch.countDown();
            }
        });
        syncLooper.start(1000);
        HavenoUtils.awaitLatch(latch);
    }

    private void updateSyncProgress() {
        long height = wallet.getHeight();
        UserThread.await(() -> {
            walletHeight.set(height);

            // new wallet reports height 1 before synced
            if (height == 1) {
                downloadListener.progress(.0001, xmrConnectionService.getTargetHeight(), null); // >0% shows progress bar
                return;
            }

            // set progress
            long targetHeight = xmrConnectionService.getTargetHeight();
            long blocksLeft = targetHeight - walletHeight.get();
            if (syncStartHeight == null) syncStartHeight = walletHeight.get();
            double percent = targetHeight == syncStartHeight ? 1.0 : ((double) Math.max(1, walletHeight.get() - syncStartHeight) / (double) (targetHeight - syncStartHeight)) * 100d; // grant at least 1 block to show progress
            downloadListener.progress(percent, blocksLeft, null);
        });
    }

    private MoneroWalletRpc createWalletRpc(MoneroWalletConfig config, Integer port) {

        // must be connected to daemon
        MoneroRpcConnection connection = xmrConnectionService.getConnection();
        if (connection == null || !Boolean.TRUE.equals(connection.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, isProxyApplied(false));
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // create wallet
            log.info("Creating wallet " + config.getPath() + " connected to daemon " + connection.getUri());
            long time = System.currentTimeMillis();
            walletRpc.createWallet(config.setServer(connection));
            walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_STACK_TRACE);
            log.info("Done creating wallet " + config.getPath() + " in " + (System.currentTimeMillis() - time) + " ms");
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletRpc != null) stopWallet(walletRpc, config.getPath());
            throw new IllegalStateException("Could not create wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes, and restart Haveno.");
        }
    }

    private MoneroWalletRpc openWalletRpc(MoneroWalletConfig config, Integer port, boolean applyProxyUri) {
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port, applyProxyUri);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_STACK_TRACE);

            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();

            // configure connection
            MoneroRpcConnection connection = new MoneroRpcConnection(xmrConnectionService.getConnection());
            if (!applyProxyUri) connection.setProxyUri(null);

            // open wallet
            walletRpc.openWallet(config.setServer(connection));
            if (walletRpc.getDaemonConnection() != null) walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_STACK_TRACE);
            log.info("Done opening wallet " + config.getPath());
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletRpc != null) stopWallet(walletRpc, config.getPath());
            throw new IllegalStateException("Could not open wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes, and restart Haveno.");
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
        synchronized (walletLock) {
            if (isShutDownStarted) return;
            if (wallet != null && HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) return;
            String oldProxyUri = wallet == null || wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getProxyUri();
            String newProxyUri = connection == null ? null : connection.getProxyUri();
            log.info("Setting daemon connection for main wallet: uri={}, proxyUri={}", connection == null ? null : connection.getUri(), newProxyUri);
            if (wallet == null) maybeInitMainWallet(false);
            else if (wallet instanceof MoneroWalletRpc && !StringUtils.equals(oldProxyUri, newProxyUri)) {
                log.info("Restarting main wallet because proxy URI has changed, old={}, new={}", oldProxyUri, newProxyUri);
                closeMainWallet(true);
                maybeInitMainWallet(false);
            } else {
                wallet.setDaemonConnection(connection);
            }

            // sync wallet on new thread
            if (connection != null) {
                wallet.getDaemonConnection().setPrintStackTrace(PRINT_STACK_TRACE);
                ThreadUtils.submitToPool(() -> {
                    if (isShutDownStarted) return;
                    wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
                    try {
                        if (Boolean.TRUE.equals(connection.isConnected())) syncWallet();
                    } catch (Exception e) {
                        log.warn("Failed to sync main wallet after setting daemon connection: " + e.getMessage());
                    }
                });
            }

            log.info("Done setting main wallet daemon connection: " + (wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getUri()));
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
        synchronized (walletLock) {
            try {
                if (wallet != null) {
                    closeWallet(wallet, true);
                    wallet = null;
                }
            } catch (Exception e) {
                log.warn("Error closing main monero-wallet-rpc subprocess: {}. Was Haveno stopped manually with ctrl+c?", e.getMessage());
            }
        }
    }

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
        cacheWalletState();
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

    public synchronized XmrAddressEntry getArbitratorAddressEntry() {
        XmrAddressEntry.Context context = XmrAddressEntry.Context.ARBITRATOR;
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> context == e.getContext())
                .findAny();
        return addressEntry.isPresent() ? addressEntry.get() : getNewAddressEntryAux(null, context);
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

    public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
        synchronized (walletLock) {
            return getAvailableAddressEntries().stream().filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.ZERO) > 0).collect(Collectors.toList());
        }
    }

    public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
        synchronized (walletLock) {
            for (MoneroSubaddress subaddress : cachedSubaddresses) {
                boolean exists = xmrAddressEntryList.getAddressEntriesAsListImmutable().stream().filter(addressEntry -> addressEntry.getAddressString().equals(subaddress.getAddress())).findAny().isPresent();
                if (!exists) {
                    XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), subaddress.getIndex() == 0 ? XmrAddressEntry.Context.BASE_ADDRESS : XmrAddressEntry.Context.AVAILABLE, null, null);
                    xmrAddressEntryList.addAddressEntry(entry);
                }
            }
            return xmrAddressEntryList.getAddressEntriesAsListImmutable();
        }
    }

    public List<XmrAddressEntry> getUnusedAddressEntries() {
        synchronized (walletLock) {
            return getAvailableAddressEntries().stream()
                    .filter(e -> e.getContext() == XmrAddressEntry.Context.AVAILABLE && !subaddressHasIncomingTransfers(e.getSubaddressIndex()))
                    .collect(Collectors.toList());
        }
    }

    public boolean subaddressHasIncomingTransfers(int subaddressIndex) {
        return getNumOutputsForSubaddress(subaddressIndex) > 0;
    }

    public int getNumOutputsForSubaddress(int subaddressIndex) {
        int numUnspentOutputs = 0;
        for (MoneroTxWallet tx : cachedTxs) {
            //if (tx.getTransfers(new MoneroTransferQuery().setSubaddressIndex(subaddressIndex)).isEmpty()) continue; // TODO monero-project: transfers are occluded by transfers from/to same account, so this will return unused when used
            numUnspentOutputs += tx.isConfirmed() ? tx.getOutputsWallet(new MoneroOutputQuery().setAccountIndex(0).setSubaddressIndex(subaddressIndex)).size() : 1; // TODO: monero-project does not provide outputs for unconfirmed txs
        }
        boolean positiveBalance = getSubaddress(subaddressIndex).getBalance().compareTo(BigInteger.ZERO) > 0;
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
        return getSubaddress(subaddressIndex).getBalance();
    }

    public BigInteger getAvailableBalanceForSubaddress(int subaddressIndex) {
        return getSubaddress(subaddressIndex).getUnlockedBalance();
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

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        synchronized (walletLock) {
            Stream<XmrAddressEntry> available = getFundedAvailableAddressEntries().stream();
            available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
            available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream().filter(entry -> !tradeManager.getOpenOfferManager().getOpenOfferById(entry.getOfferId()).isPresent()));
            available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream().filter(entry -> tradeManager.getTrade(entry.getOfferId()) == null || tradeManager.getTrade(entry.getOfferId()).isPayoutUnlocked()));
            return available.filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.ZERO) > 0);
        }
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

    public List<MoneroTxWallet> getTransactions(boolean includeDead) {
        if (includeDead) return cachedTxs;
        return cachedTxs.stream().filter(tx -> !tx.isFailed()).collect(Collectors.toList());
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

    // -------------------------------- HELPERS -------------------------------

    private void cacheWalletState() {
        cachedBalance = wallet.getBalance(0);
        cachedAvailableBalance = wallet.getUnlockedBalance(0);
        cachedSubaddresses = wallet.getSubaddresses(0);
        cachedTxs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true));
    }

    /**
     * Relays wallet notifications to external listeners.
     */
    private class XmrWalletListener extends MoneroWalletListener {

        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
            for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onSyncProgress(height, startHeight, endHeight, percentDone, message));
        }

        @Override
        public void onNewBlock(long height) {
            cacheWalletState();
            UserThread.execute(() -> {
                walletHeight.set(height);
                for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onNewBlock(height));
            });
        }

        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
            cacheWalletState();
            updateBalanceListeners();
            for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onBalancesChanged(newBalance, newUnlockedBalance));
        }

        @Override
        public void onOutputReceived(MoneroOutputWallet output) {
            for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onOutputReceived(output));
        }

        @Override
        public void onOutputSpent(MoneroOutputWallet output) {
            for (MoneroWalletListenerI listener : walletListeners) ThreadUtils.submitToPool(() -> listener.onOutputSpent(output));
        }
    }
}
