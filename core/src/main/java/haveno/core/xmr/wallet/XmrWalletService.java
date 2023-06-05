package haveno.core.xmr.wallet;

import com.google.common.util.concurrent.Service.State;
import com.google.inject.name.Named;

import common.utils.GenUtils;
import common.utils.JsonUtils;
import haveno.common.UserThread;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreMoneroConnectionsService;
import haveno.core.offer.Offer;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import haveno.core.xmr.setup.WalletsSetup;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroRpcError;
import monero.common.MoneroUtils;
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
import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroWalletListenerI;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class XmrWalletService {
    private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

    // Monero configuration
    // TODO: don't hard code configuration, inject into classes?
    public static final int NUM_BLOCKS_UNLOCK = 10;
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = getMoneroNetworkType();
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    public static final String MONERO_WALLET_RPC_DIR = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? System.getProperty("user.dir") + File.separator + ".localnet" : Config.appDataDir().getAbsolutePath(); // .localnet contains monero-wallet-rpc and wallet files
    public static final String MONERO_WALLET_RPC_NAME = Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc";
    public static final String MONERO_WALLET_RPC_PATH = MONERO_WALLET_RPC_DIR + File.separator + MONERO_WALLET_RPC_NAME;
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    public static final double MINER_FEE_TOLERANCE = 0.25; // miner fee must be within percent of estimated fee
    private static final double SECURITY_DEPOSIT_TOLERANCE = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? 0.25 : 0.05; // security deposit can absorb miner fee up to percent
    private static final double DUST_TOLERANCE = 0.01; // max dust as percent of mining fee
    private static final int NUM_MAX_BACKUP_WALLETS = 10;
    private static final int MONERO_LOG_LEVEL = 0;
    private static final boolean PRINT_STACK_TRACE = false;

    private final CoreAccountService accountService;
    private final CoreMoneroConnectionsService connectionsService;
    private final XmrAddressEntryList xmrAddressEntryList;
    private final WalletsSetup walletsSetup;

    private final File walletDir;
    private final File xmrWalletFile;
    private final int rpcBindPort;
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private TradeManager tradeManager;
    private MoneroWalletRpc wallet;
    private final Map<String, Optional<MoneroTx>> txCache = new HashMap<String, Optional<MoneroTx>>();
    private boolean isShutDownStarted = false;
    private ExecutorService syncWalletThreadPool = Executors.newFixedThreadPool(10); // TODO: adjust based on connection type

    @Inject
    XmrWalletService(CoreAccountService accountService,
                     CoreMoneroConnectionsService connectionsService,
                     WalletsSetup walletsSetup,
                     XmrAddressEntryList xmrAddressEntryList,
                     @Named(Config.WALLET_DIR) File walletDir,
                     @Named(Config.WALLET_RPC_BIND_PORT) int rpcBindPort) {
        this.accountService = accountService;
        this.connectionsService = connectionsService;
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
                    log.info(getClass().getSimpleName() + ".accountService.onAccountCreated()");
                    initialize();
                }

                @Override
                public void onAccountOpened() {
                    log.info(getClass().getSimpleName() + ".accountService.onAccountOpened()");
                    initialize();
                }

                @Override
                public void onAccountClosed() {
                    log.info(getClass().getSimpleName() + ".accountService.onAccountClosed()");
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

    public void saveMainWallet() {
        saveMainWallet(true);
    }

    public void saveMainWallet(boolean backup) {
        saveWallet(getWallet(), backup);
    }

    public boolean isWalletReady() {
        try {
            return getWallet() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isWalletEncrypted() {
        return accountService.getPassword() != null;
    }

    public MoneroDaemonRpc getDaemon() {
        return connectionsService.getDaemon();
    }

    public CoreMoneroConnectionsService getConnectionsService() {
        return connectionsService;
    }

    public String getWalletPassword() {
        return accountService.getPassword() == null ? MONERO_WALLET_RPC_DEFAULT_PASSWORD : accountService.getPassword();
    }

    public boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + ".keys").exists();
    }

    public MoneroWalletRpc createWallet(String walletName) {
        log.info("{}.createWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot create wallet because shutting down");
        return createWalletRpc(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
                null);
    }

    public MoneroWalletRpc openWallet(String walletName) {
        log.info("{}.openWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDownStarted) throw new IllegalStateException("Cannot open wallet because shutting down");
        return openWalletRpc(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
            null);
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
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".keys").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".address.txt").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
    }

    public void backupWallet(String walletName) {
        FileUtil.rollingBackup(walletDir, walletName, NUM_MAX_BACKUP_WALLETS);
        FileUtil.rollingBackup(walletDir, walletName + ".keys", NUM_MAX_BACKUP_WALLETS);
        FileUtil.rollingBackup(walletDir, walletName + ".address.txt", NUM_MAX_BACKUP_WALLETS);
    }

    public void deleteWalletBackups(String walletName) {
        FileUtil.deleteRollingBackup(walletDir, walletName);
        FileUtil.deleteRollingBackup(walletDir, walletName + ".keys");
        FileUtil.deleteRollingBackup(walletDir, walletName + ".address.txt");
    }

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        try {
            synchronized (wallet) {
                MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
                //printTxs("XmrWalletService.createTx", tx);
                return tx;
            }
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * Thaw the given outputs with a lock on the wallet.
     *
     * @param keyImages the key images to thaw
     */
    public void thawOutputs(Collection<String> keyImages) {
        synchronized (getWallet()) {
            for (String keyImage : keyImages) wallet.thawOutput(keyImage);
        }
    }

    /**
     * Create the reserve tx and freeze its inputs. The full amount is returned
     * to the sender's payout address less the trade fee.
     *
     * @param returnAddress return address for reserved funds
     * @param tradeFee trade fee
     * @param sendAmount amount to give peer
     * @param securityDeposit security deposit amount
     * @return a transaction to reserve a trade
     */
    public MoneroTxWallet createReserveTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String returnAddress) {
        log.info("Creating reserve tx with return address={}", returnAddress);
        long time = System.currentTimeMillis();
        MoneroTxWallet reserveTx = createTradeTx(tradeFee, sendAmount, securityDeposit, returnAddress, true);
        log.info("Done creating reserve tx in {} ms", System.currentTimeMillis() - time);
        return reserveTx;
    }

    /**s
     * Create the multisig deposit tx and freeze its inputs.
     *
     * @param trade the trade to create a deposit tx from
     * @return MoneroTxWallet the multisig deposit tx
     */
    public MoneroTxWallet createDepositTx(Trade trade) {
        Offer offer = trade.getProcessModel().getOffer();
        String multisigAddress = trade.getProcessModel().getMultisigAddress();
        BigInteger tradeFee = trade instanceof MakerTrade ? trade.getOffer().getMakerFee() : trade.getTakerFee();
        BigInteger sendAmount = trade instanceof BuyerTrade ? BigInteger.valueOf(0) : offer.getAmount();
        BigInteger securityDeposit = trade instanceof BuyerTrade ? offer.getBuyerSecurityDeposit() : offer.getSellerSecurityDeposit();

        // thaw reserved outputs then create deposit tx
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // thaw reserved outputs
            if (trade.getSelf().getReserveTxKeyImages() != null) {
                thawOutputs(trade.getSelf().getReserveTxKeyImages());
            }

            log.info("Creating deposit tx for trade {} {} with multisig address={}", trade.getClass().getSimpleName(), trade.getId(), multisigAddress);
            long time = System.currentTimeMillis();
            MoneroTxWallet tradeTx = createTradeTx(tradeFee, sendAmount, securityDeposit, multisigAddress, false);
            log.info("Done creating deposit tx for trade {} {} in {} ms", trade.getClass().getSimpleName(), trade.getId(), System.currentTimeMillis() - time);
            return tradeTx;
        }
    }

    private MoneroTxWallet createTradeTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, boolean isReserveTx) {
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // binary search to maximize security deposit and minimize potential dust
            MoneroTxWallet tradeTx = null;
            double appliedTolerance = 0.0; // percent of tolerance to apply, thereby decreasing security deposit
            double searchDiff = 1.0; // difference for next binary search
            for (int i = 0; i < 10; i++) {
                try {
                    BigInteger appliedSecurityDeposit = new BigDecimal(securityDeposit).multiply(new BigDecimal(1.0 - SECURITY_DEPOSIT_TOLERANCE * appliedTolerance)).toBigInteger();
                    BigInteger amount = sendAmount.add(isReserveTx ? tradeFee : appliedSecurityDeposit);
                    tradeTx = wallet.createTx(new MoneroTxConfig()
                            .setAccountIndex(0)
                            .addDestination(HavenoUtils.getTradeFeeAddress(), isReserveTx ? appliedSecurityDeposit : tradeFee) // reserve tx charges security deposit if published
                            .addDestination(address, amount));
                    appliedTolerance -= searchDiff; // apply less tolerance to increase security deposit
                    if (appliedTolerance < 0.0) break; // can send full security deposit
                } catch (MoneroError e) {
                    appliedTolerance += searchDiff; // apply more tolerance to decrease security deposit
                    if (appliedTolerance > 1.0) throw e; // not enough money
                }
                searchDiff /= 2;
            }

            // freeze inputs
            for (MoneroOutput input : tradeTx.getInputs()) wallet.freezeOutput(input.getKeyImage().getHex());
            saveMainWallet();
            return tradeTx;
        }
    }

    /**
     * Verify a reserve or deposit transaction.
     * Checks double spends, trade fee, deposit amount and destination, and miner fee.
     * The transaction is submitted to the pool then flushed without relaying.
     *
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
    public Tuple2<MoneroTx, BigInteger> verifyTradeTx(String offerId, BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, String txHash, String txHex, String txKey, List<String> keyImages, boolean isReserveTx) {
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
                if (tx.getUnlockHeight() != 0) throw new RuntimeException("Unlock height must be 0");

                // verify miner fee
                BigInteger feeEstimate = getFeeEstimate(tx.getWeight());
                double feeDiff = tx.getFee().subtract(feeEstimate).abs().doubleValue() / feeEstimate.doubleValue();
                if (feeDiff > MINER_FEE_TOLERANCE) throw new Error("Miner fee is not within " + (MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + feeEstimate + " but was " + tx.getFee());
                log.info("Trade tx fee {} is within tolerance, diff%={}", tx.getFee(), feeDiff);

                // verify transfer proof to fee address
                String feeAddress = HavenoUtils.getTradeFeeAddress();
                MoneroCheckTx feeCheck = wallet.checkTxKey(txHash, txKey, feeAddress);
                if (!feeCheck.isGood()) throw new RuntimeException("Invalid proof of trade fee");

                // verify transfer proof to return address
                MoneroCheckTx returnCheck = wallet.checkTxKey(txHash, txKey, address);
                if (!returnCheck.isGood()) throw new RuntimeException("Invalid proof of return funds");

                // collect actual trade fee, send amount, and security deposit
                BigInteger actualTradeFee = isReserveTx ? returnCheck.getReceivedAmount().subtract(sendAmount) : feeCheck.getReceivedAmount();
                actualSecurityDeposit = isReserveTx ? feeCheck.getReceivedAmount() : returnCheck.getReceivedAmount().subtract(sendAmount);
                BigInteger actualSendAmount = returnCheck.getReceivedAmount().subtract(isReserveTx ? actualTradeFee : actualSecurityDeposit);

                // verify trade fee
                if (!tradeFee.equals(actualTradeFee)) {
                    throw new RuntimeException("Trade fee is incorrect amount, expected=" + tradeFee + ", actual=" + actualTradeFee + ", return address check=" + JsonUtils.serialize(returnCheck) + ", fee address check=" + JsonUtils.serialize(feeCheck));
                }

                // verify sufficient security deposit
                BigInteger minSecurityDeposit = new BigDecimal(securityDeposit).multiply(new BigDecimal(1.0 - SECURITY_DEPOSIT_TOLERANCE)).toBigInteger();
                if (actualSecurityDeposit.compareTo(minSecurityDeposit) < 0) throw new RuntimeException("Security deposit amount is not enough, needed " + minSecurityDeposit + " but was " + actualSecurityDeposit);

                // verify deposit amount + miner fee within dust tolerance
                BigInteger minDepositAndFee = sendAmount.add(securityDeposit).subtract(new BigDecimal(tx.getFee()).multiply(new BigDecimal(1.0 - DUST_TOLERANCE)).toBigInteger());
                BigInteger actualDepositAndFee = actualSendAmount.add(actualSecurityDeposit).add(tx.getFee());
                if (actualDepositAndFee.compareTo(minDepositAndFee) < 0) throw new RuntimeException("Deposit amount + fee is not enough, needed " + minDepositAndFee + " but was " + actualDepositAndFee);
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
    public BigInteger getFeeEstimate(long txWeight) {

        // get fee estimates per kB from daemon
        MoneroFeeEstimate feeEstimates = getDaemon().getFeeEstimate();
        BigInteger baseFeeEstimate = feeEstimates.getFee(); // get normal fee per kB
        BigInteger qmask = feeEstimates.getQuantizationMask();
        log.info("Monero base fee estimate={}, qmask={}: " + baseFeeEstimate, qmask);

        // get tx base fee
        BigInteger baseFee = baseFeeEstimate.multiply(BigInteger.valueOf(txWeight));

        // round up to multiple of quantization mask
        BigInteger[] quotientAndRemainder = baseFee.divideAndRemainder(qmask);
        BigInteger feeEstimate = qmask.multiply(quotientAndRemainder[0]);
        if (quotientAndRemainder[1].compareTo(BigInteger.valueOf(0)) > 0) feeEstimate = feeEstimate.add(qmask);
        return feeEstimate;
    }

    public MoneroTx getTx(String txHash) {
        List<MoneroTx> txs = getTxs(Arrays.asList(txHash));
        return txs.isEmpty() ? null : txs.get(0);
    }

    public List<MoneroTx> getTxs(List<String> txHashes) {
        synchronized (txCache) {

            // fetch txs
            if (getDaemon() == null) connectionsService.verifyConnection(); // will throw
            List<MoneroTx> txs = getDaemon().getTxs(txHashes, true);

            // store to cache
            for (MoneroTx tx : txs) txCache.put(tx.getHash(), Optional.of(tx));

            // schedule txs to be removed from cache
            UserThread.runAfter(() -> {
                synchronized (txCache) {
                    for (MoneroTx tx : txs) txCache.remove(tx.getHash());
                }
            }, connectionsService.getRefreshPeriodMs() / 1000);
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

        // remove listeners which stops polling wallet
        // TODO monero-java: wallet.stopPolling()?
        if (wallet != null) {
            for (MoneroWalletListenerI listener : new HashSet<>(wallet.getListeners())) {
                wallet.removeListener(listener);
            }
        }

        // prepare trades for shut down
        if (tradeManager != null) tradeManager.onShutDownStarted();
    }

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());

        // shut down trade and main wallets at same time
        walletListeners.clear();
        List<Runnable> tasks = new ArrayList<Runnable>();
        if (tradeManager != null) tasks.add(() -> tradeManager.shutDown());
        tasks.add(() -> closeMainWallet(true));
        HavenoUtils.executeTasks(tasks);
        log.info("Done shutting down all wallets");
    }

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void initialize() {

        // initialize main wallet if connected or previously created
        maybeInitMainWallet();

        // set and listen to daemon connection
        connectionsService.addConnectionListener(newConnection -> onConnectionChanged(newConnection));
    }

    private synchronized void maybeInitMainWallet() {

        // open or create wallet main wallet
        if (wallet == null) {
            MoneroDaemonRpc daemon = connectionsService.getDaemon();
            log.info("Initializing main wallet with monerod=" + (daemon == null ? "null" : daemon.getRpcConnection().getUri()));
            MoneroWalletConfig walletConfig = new MoneroWalletConfig().setPath(MONERO_WALLET_NAME).setPassword(getWalletPassword());
            if (MoneroUtils.walletExists(xmrWalletFile.getPath())) {
                wallet = openWalletRpc(walletConfig, rpcBindPort);
            } else if (connectionsService.getConnection() != null && Boolean.TRUE.equals(connectionsService.getConnection().isConnected())) {
                wallet = createWalletRpc(walletConfig, rpcBindPort);
            }
        }

        // sync wallet if open
        if (wallet != null) {
            log.info("Monero wallet uri={}, path={}", wallet.getRpcConnection().getUri(), wallet.getPath());
            int numAttempts = 0;
            int maxAttempts = 2;
            while (!HavenoUtils.havenoSetup.getWalletInitialized().get()) {
                try {
                
                    // sync main wallet
                    log.info("Syncing main wallet");
                    long time = System.currentTimeMillis();
                    wallet.sync(); // blocking
                    log.info("Done syncing main wallet in " + (System.currentTimeMillis() - time) + " ms");
                    wallet.startSyncing(connectionsService.getRefreshPeriodMs());
                    if (getMoneroNetworkType() != MoneroNetworkType.MAINNET) log.info("Monero wallet balance={}, unlocked balance={}", wallet.getBalance(0), wallet.getUnlockedBalance(0));

                    // TODO: using this to signify both daemon and wallet synced, use separate sync handlers
                    connectionsService.doneDownload();
    
                    // notify setup that main wallet is initialized
                    // TODO: app fully initializes after this is set to true, even though wallet might not be initialized if unconnected. wallet will be created when connection detected
                    // refactor startup to call this and sync off main thread? but the calls to e.g. getBalance() fail with 'wallet and network is not yet initialized'
                    HavenoUtils.havenoSetup.getWalletInitialized().set(true);
                    
                    // save but skip backup on initialization
                    saveMainWallet(false);
                } catch (Exception e) {
                    log.warn("Error syncing main wallet: {}", e.getMessage());
                    numAttempts++;
                    if (numAttempts < maxAttempts) {
                        log.warn("Trying again in {} seconds", connectionsService.getRefreshPeriodMs() / 1000);
                        GenUtils.waitFor(connectionsService.getRefreshPeriodMs());
                    } else {
                        log.warn("Failed to sync main wallet after {} attempts. Opening app without syncing", maxAttempts);
                        HavenoUtils.havenoSetup.getWalletInitialized().set(true);
                        saveMainWallet(false);
                        break;
                    }
                }
            }
            
            // register internal listener to notify external listeners
            wallet.addListener(new XmrWalletListener());
        }
    }

    private MoneroWalletRpc createWalletRpc(MoneroWalletConfig config, Integer port) {

        // must be connected to daemon
        MoneroRpcConnection connection = connectionsService.getConnection();
        if (connection == null || !Boolean.TRUE.equals(connection.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port);
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

    private MoneroWalletRpc openWalletRpc(MoneroWalletConfig config, Integer port) {
        MoneroWalletRpc walletRpc = null;
        try {

            // start monero-wallet-rpc instance
            walletRpc = startWalletRpcInstance(port);
            walletRpc.getRpcConnection().setPrintStackTrace(PRINT_STACK_TRACE);
            
            // prevent wallet rpc from syncing
            walletRpc.stopSyncing();
            
            // open wallet
            log.info("Opening wallet " + config.getPath());
            walletRpc.openWallet(config.setServer(connectionsService.getConnection()));
            if (walletRpc.getDaemonConnection() != null) walletRpc.getDaemonConnection().setPrintStackTrace(PRINT_STACK_TRACE);
            log.info("Done opening wallet " + config.getPath());
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            if (walletRpc != null) stopWallet(walletRpc, config.getPath());
            throw new IllegalStateException("Could not open wallet '" + config.getPath() + "'. Please close Haveno, stop all monero-wallet-rpc processes, and restart Haveno.");
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port) {

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

        MoneroRpcConnection connection = connectionsService.getConnection();
        if (connection != null) {
            cmd.add("--daemon-address");
            cmd.add(connection.getUri());
            if (connection.getProxyUri() != null) {
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
        if (isShutDownStarted) return;
        if (wallet != null && HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) return;

        log.info("Setting main wallet daemon connection: " + (connection == null ? null : connection.getUri()));
        String oldProxyUri = wallet == null || wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getProxyUri();
        String newProxyUri = connection == null ? null : connection.getProxyUri();
        if (wallet == null) maybeInitMainWallet();
        else if (wallet instanceof MoneroWalletRpc && !StringUtils.equals(oldProxyUri, newProxyUri)) {
            log.info("Restarting main wallet since proxy URI has changed");
            closeMainWallet(true);
            maybeInitMainWallet();
        } else {
            wallet.setDaemonConnection(connection);
            if (connection != null) wallet.getDaemonConnection().setPrintStackTrace(PRINT_STACK_TRACE);
            if (connection != null && !Boolean.FALSE.equals(connection.isConnected())) {
                new Thread(() -> {
                    try {
                        wallet.startSyncing(connectionsService.getRefreshPeriodMs());
                        wallet.sync();
                    } catch (Exception e) {
                        log.warn("Failed to sync main wallet after setting daemon connection: " + e.getMessage());
                    }
                }).start();
            }
        }

        log.info("Done setting main wallet daemon connection: " + (connection == null ? null : connection.getUri()));
    }

    private void notifyBalanceListeners() {
        for (XmrBalanceListener balanceListener : balanceListeners) {
            BigInteger balance;
            if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
            else balance = getAvailableBalance();
            UserThread.execute(new Runnable() { // TODO (woodser): don't execute on UserThread
                @Override
                public void run() {
                    balanceListener.onBalanceChanged(balance);
                }
            });
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
        HavenoUtils.executeTasks(tasks, Math.min(10, 1 + trades.size()));
    }

    private void closeMainWallet(boolean save) {
        try {
            if (wallet != null) {
                closeWallet(wallet, true);
                wallet = null;
            }
        } catch (Exception e) {
            log.warn("Error closing main monero-wallet-rpc subprocess: " + e.getMessage() + ". Was Haveno stopped manually with ctrl+c?");
        }
    }

    // ----------------------------- LEGACY APP -------------------------------

    public synchronized XmrAddressEntry getNewAddressEntry() {
        return getNewAddressEntryAux(null, XmrAddressEntry.Context.AVAILABLE);
    }

    public synchronized XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {

        // try to use available and not yet used entries
        try {
            List<MoneroTxWallet> incomingTxs = getTxsWithIncomingOutputs(); // prefetch all incoming txs to avoid query per subaddress
            Optional<XmrAddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> XmrAddressEntry.Context.AVAILABLE == e.getContext()).filter(e -> isSubaddressUnused(e.getSubaddressIndex(), incomingTxs)).findAny();
            if (emptyAvailableAddressEntry.isPresent()) return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(emptyAvailableAddressEntry.get(), context, offerId);
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

    public synchronized XmrAddressEntry getFreshAddressEntry(List<MoneroTxWallet> cachedTxs) {
        List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries(cachedTxs);
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

    public synchronized void swapTradeEntryToAvailableEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        addressEntryOptional.ifPresent(e -> {
            log.info("swap addressEntry with address {} and offerId {} from context {} to available", e.getAddressString(), e.getOfferId(), context);
            xmrAddressEntryList.swapToAvailable(e);
            saveAddressEntryList();
        });
    }

    public synchronized void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
    }

    public synchronized void resetAddressEntriesForPendingTrade(String offerId) {
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.MULTI_SIG);
        // We swap also TRADE_PAYOUT to be sure all is cleaned up. There might be cases
        // where a user cannot send the funds
        // to an external wallet directly in the last step of the trade, but the funds
        // are in the Haveno wallet anyway and
        // the dealing with the external wallet is pure UI thing. The user can move the
        // funds to the wallet and then
        // send out the funds to the external wallet. As this cleanup is a rare
        // situation and most users do not use
        // the feature to send out the funds we prefer that strategy (if we keep the
        // address entry it might cause
        // complications in some edge cases after a SPV resync).
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    private Optional<XmrAddressEntry> findAddressEntry(String address, XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(e -> address.equals(e.getAddressString())).filter(e -> context == e.getContext()).findAny();
    }

    public List<XmrAddressEntry> getAvailableAddressEntries() {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> XmrAddressEntry.Context.AVAILABLE == addressEntry.getContext()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntriesForOpenOffer() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> XmrAddressEntry.Context.OFFER_FUNDING == addressEntry.getContext() ||
                        XmrAddressEntry.Context.RESERVED_FOR_TRADE == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntriesForTrade() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> XmrAddressEntry.Context.MULTI_SIG == addressEntry.getContext() || XmrAddressEntry.Context.TRADE_PAYOUT == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntries(XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> context == addressEntry.getContext()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
        return getAvailableAddressEntries().stream().filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.valueOf(0)) > 0).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
        List<MoneroSubaddress> subaddresses = wallet.getSubaddresses(0);
        for (MoneroSubaddress subaddress : subaddresses) {
            boolean exists = xmrAddressEntryList.getAddressEntriesAsListImmutable().stream().filter(addressEntry -> addressEntry.getAddressString().equals(subaddress.getAddress())).findAny().isPresent();
            if (!exists) {
                XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), subaddress.getIndex() == 0 ? XmrAddressEntry.Context.BASE_ADDRESS : XmrAddressEntry.Context.AVAILABLE, null, null);
                log.info("Add XmrAddressEntry from existing subaddress: {}", entry);
                xmrAddressEntryList.addAddressEntry(entry);
            }
        }
        return xmrAddressEntryList.getAddressEntriesAsListImmutable();
    }

    public List<XmrAddressEntry> getUnusedAddressEntries() {
        return getUnusedAddressEntries(getTxsWithIncomingOutputs());
    }

    public List<XmrAddressEntry> getUnusedAddressEntries(List<MoneroTxWallet> cachedTxs) {
        return getAvailableAddressEntries().stream()
                .filter(e -> isSubaddressUnused(e.getSubaddressIndex(), cachedTxs))
                .collect(Collectors.toList());
    }

    public boolean isSubaddressUnused(int subaddressIndex) {
        return isSubaddressUnused(subaddressIndex, null);
    }

    private boolean isSubaddressUnused(int subaddressIndex, List<MoneroTxWallet> incomingTxs) {
        return getNumOutputsForSubaddress(subaddressIndex, incomingTxs) == 0;
    }

    public int getNumOutputsForSubaddress(int subaddressIndex) {
        return getNumOutputsForSubaddress(subaddressIndex, null);
    }

    public int getNumOutputsForSubaddress(int subaddressIndex, List<MoneroTxWallet> incomingTxs) {
        if (incomingTxs == null) incomingTxs = getTxsWithIncomingOutputs(subaddressIndex);
        int numUnspentOutputs = 0;
        for (MoneroTxWallet tx : incomingTxs) {
            if (tx.getTransfers(new MoneroTransferQuery().setSubaddressIndex(subaddressIndex)).isEmpty()) continue;
            numUnspentOutputs += tx.isConfirmed() ? tx.getOutputsWallet(new MoneroOutputQuery().setAccountIndex(0).setSubaddressIndex(subaddressIndex)).size() : 1; // TODO: monero-project does not provide outputs for unconfirmed txs
        }
        return numUnspentOutputs;
    }

    public List<MoneroTxWallet> getTxsWithIncomingOutputs() {
        return getTxsWithIncomingOutputs(null);
    }

    public List<MoneroTxWallet> getTxsWithIncomingOutputs(Integer subaddressIndex) {
        List<MoneroTxWallet> txs = wallet.getTxs(new MoneroTxQuery().setIncludeOutputs(true));
        return getTxsWithIncomingOutputs(subaddressIndex, txs);
    }

    public static List<MoneroTxWallet> getTxsWithIncomingOutputs(Integer subaddressIndex, List<MoneroTxWallet> txs) {
        List<MoneroTxWallet> incomingTxs = new ArrayList<>();
        for (MoneroTxWallet tx : txs) {
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
        return wallet.getBalance(0, subaddressIndex);
    }

    public BigInteger getAvailableBalanceForSubaddress(int subaddressIndex) {
        return wallet.getUnlockedBalance(0, subaddressIndex);
    }

    public BigInteger getBalance() {
        return wallet != null ? wallet.getBalance(0) : BigInteger.valueOf(0);
    }

    public BigInteger getAvailableBalance() {
        return wallet != null ? wallet.getUnlockedBalance(0) : BigInteger.valueOf(0);
    }

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> availableAndPayout = Stream.concat(getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream(), getFundedAvailableAddressEntries().stream());
        Stream<XmrAddressEntry> available = Stream.concat(availableAndPayout, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).compareTo(BigInteger.valueOf(0)) > 0);
    }

    public void addWalletListener(MoneroWalletListenerI listener) {
        walletListeners.add(listener);
    }

    public void removeWalletListener(MoneroWalletListenerI listener) {
        if (!walletListeners.contains(listener)) throw new RuntimeException("Listener is not registered with wallet");
        walletListeners.remove(listener);
    }

    // TODO (woodser): update balance and other listening
    public void addBalanceListener(XmrBalanceListener listener) {
        if (!balanceListeners.contains(listener)) balanceListeners.add(listener);
    }

    public void removeBalanceListener(XmrBalanceListener listener) {
        balanceListeners.remove(listener);
    }

    public void saveAddressEntryList() {
        xmrAddressEntryList.requestPersistence();
    }

    public List<MoneroTxWallet> getTransactions(boolean includeDead) {
        return wallet.getTxs(new MoneroTxQuery().setIsFailed(includeDead ? null : false));
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

    /**
     * Relays wallet notifications to external listeners.
     */
    private class XmrWalletListener extends MoneroWalletListener {

        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    for (MoneroWalletListenerI listener : walletListeners) listener.onSyncProgress(height, startHeight, endHeight, percentDone, message);
                }
            });
        }

        @Override
        public void onNewBlock(long height) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    for (MoneroWalletListenerI listener : walletListeners) listener.onNewBlock(height);
                }
            });
        }

        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    for (MoneroWalletListenerI listener : walletListeners) listener.onBalancesChanged(newBalance, newUnlockedBalance);
                    notifyBalanceListeners();
                }
            });
        }

        @Override
        public void onOutputReceived(MoneroOutputWallet output) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    for (MoneroWalletListenerI listener : walletListeners) listener.onOutputReceived(output);
                }
            });
        }

        @Override
        public void onOutputSpent(MoneroOutputWallet output) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    for (MoneroWalletListenerI listener : walletListeners) listener.onOutputSpent(output);
                }
            });
        }
    }
}
