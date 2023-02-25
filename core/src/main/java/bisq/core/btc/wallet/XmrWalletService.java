package bisq.core.btc.wallet;

import static com.google.common.base.Preconditions.checkState;

import bisq.common.UserThread;
import bisq.common.config.BaseCurrencyNetwork;
import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.common.util.Utilities;
import bisq.core.api.AccountServiceListener;
import bisq.core.api.CoreAccountService;
import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.app.HavenoSetup;
import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.setup.MoneroWalletRpcManager;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.offer.Offer;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.BuyerTrade;
import bisq.core.trade.HavenoUtils;

import com.google.common.util.concurrent.Service.State;
import com.google.inject.name.Named;
import common.utils.JsonUtils;
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
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
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
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTransferQuery;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroWalletListenerI;
import org.bitcoinj.core.Coin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private boolean isShutDown = false;

    private HavenoSetup havenoSetup;

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
        if (isShutDown) throw new IllegalStateException("Cannot create wallet because shutting down");
        return createWallet(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
                null,
                true);
    }

    public MoneroWalletRpc openWallet(String walletName) {
        log.info("{}.openWallet({})", getClass().getSimpleName(), walletName);
        if (isShutDown) throw new IllegalStateException("Cannot open wallet because shutting down");
        return openWallet(new MoneroWalletConfig()
                .setPath(walletName)
                .setPassword(getWalletPassword()),
                null);
    }

    public void saveWallet(MoneroWallet wallet, boolean backup) {
        wallet.save();
        if (backup) backupWallet(wallet.getPath());
    }

    public void closeWallet(MoneroWallet wallet, boolean save) {
        log.info("{}.closeWallet({}, {})", getClass().getSimpleName(), wallet.getPath(), save);
        MoneroError err = null;
        try {
            String path = wallet.getPath();
            wallet.close(save);
            if (save) backupWallet(path);
        } catch (MoneroError e) {
            err = e;
        }
        MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) wallet);
        if (err != null) throw err;
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
        log.info("Creating reserve tx with fee={}, sendAmount={}, securityDeposit={}", tradeFee, sendAmount, securityDeposit);
        return createTradeTx(tradeFee, sendAmount, securityDeposit, returnAddress);
    }

    /**
     * Create the multisig deposit tx and freeze its inputs.
     *
     * @param trade the trade to create a deposit tx from
     * @return MoneroTxWallet the multisig deposit tx
     */
    public MoneroTxWallet createDepositTx(Trade trade) {
        Offer offer = trade.getProcessModel().getOffer();
        String multisigAddress = trade.getProcessModel().getMultisigAddress();
        BigInteger tradeFee = HavenoUtils.coinToAtomicUnits(trade instanceof MakerTrade ? trade.getOffer().getMakerFee() : trade.getTakerFee());
        BigInteger sendAmount = HavenoUtils.coinToAtomicUnits(trade instanceof BuyerTrade ? Coin.ZERO : offer.getAmount());
        BigInteger securityDeposit = HavenoUtils.coinToAtomicUnits(trade instanceof BuyerTrade ? offer.getBuyerSecurityDeposit() : offer.getSellerSecurityDeposit());

        // thaw reserved outputs then create deposit tx
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // thaw reserved outputs
            if (trade.getSelf().getReserveTxKeyImages() != null) {
                thawOutputs(trade.getSelf().getReserveTxKeyImages());
            }

            log.info("Creating deposit tx with fee={}, sendAmount={}, securityDeposit={}", tradeFee, sendAmount, securityDeposit);
            return createTradeTx(tradeFee, sendAmount, securityDeposit, multisigAddress);
        }
    }

    private MoneroTxWallet createTradeTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address) {
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // binary search to maximize security deposit and minimize potential dust
            MoneroTxWallet tradeTx = null;
            double appliedTolerance = 0.0; // percent of tolerance to apply, thereby decreasing security deposit
            double searchDiff = 1.0; // difference for next binary search
            for (int i = 0; i < 10; i++) {
                try {
                    BigInteger appliedSecurityDeposit = new BigDecimal(securityDeposit).multiply(new BigDecimal(1.0 - SECURITY_DEPOSIT_TOLERANCE * appliedTolerance)).toBigInteger();
                    BigInteger amount = sendAmount.add(appliedSecurityDeposit);
                    tradeTx = wallet.createTx(new MoneroTxConfig()
                            .setAccountIndex(0)
                            .addDestination(HavenoUtils.getTradeFeeAddress(), tradeFee)
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
     */
    public void verifyTradeTx(BigInteger tradeFee, BigInteger sendAmount, BigInteger securityDeposit, String address, String txHash, String txHex, String txKey, List<String> keyImages) {
        MoneroDaemonRpc daemon = getDaemon();
        MoneroWallet wallet = getWallet();
        synchronized (daemon) {
            try {

                // verify tx not submitted to pool
                MoneroTx tx = daemon.getTx(txHash);
                if (tx != null) throw new RuntimeException("Tx is already submitted");

                // submit tx to pool
                MoneroSubmitTxResult result = daemon.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
                if (!result.isGood()) throw new RuntimeException("Failed to submit tx to daemon: " + JsonUtils.serialize(result));
                tx = getTx(txHash);

                // verify key images
                if (keyImages != null) {
                    Set<String> txKeyImages = new HashSet<String>();
                    for (MoneroOutput input : tx.getInputs()) txKeyImages.add(input.getKeyImage().getHex());
                    if (!txKeyImages.equals(new HashSet<String>(keyImages))) throw new Error("Tx inputs do not match claimed key images");
                }

                // verify unlock height
                if (tx.getUnlockHeight() != 0) throw new RuntimeException("Unlock height must be 0");

                // verify trade fee
                String feeAddress = HavenoUtils.getTradeFeeAddress();
                MoneroCheckTx check = wallet.checkTxKey(txHash, txKey, feeAddress);
                if (!check.isGood()) throw new RuntimeException("Invalid proof of trade fee");
                if (!check.getReceivedAmount().equals(tradeFee)) throw new RuntimeException("Trade fee is incorrect amount, expected " + tradeFee + " but was " + check.getReceivedAmount());

                // verify miner fee
                BigInteger feeEstimate = getFeeEstimate(tx.getWeight());
                double feeDiff = tx.getFee().subtract(feeEstimate).abs().doubleValue() / feeEstimate.doubleValue(); // TODO: use BigDecimal?
                if (feeDiff > MINER_FEE_TOLERANCE) throw new Error("Miner fee is not within " + (MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + feeEstimate + " but was " + tx.getFee());
                log.info("Trade tx fee {} is within tolerance, diff%={}", tx.getFee(), feeDiff);

                // verify sufficient security deposit
                check = wallet.checkTxKey(txHash, txKey, address);
                if (!check.isGood()) throw new RuntimeException("Invalid proof of deposit amount");
                BigInteger minSecurityDeposit = new BigDecimal(securityDeposit).multiply(new BigDecimal(1.0 - SECURITY_DEPOSIT_TOLERANCE)).toBigInteger();
                BigInteger actualSecurityDeposit = check.getReceivedAmount().subtract(sendAmount);
                if (actualSecurityDeposit.compareTo(minSecurityDeposit) < 0) throw new RuntimeException("Security deposit amount is not enough, needed " + minSecurityDeposit + " but was " + actualSecurityDeposit);

                // verify deposit amount + miner fee within dust tolerance
                BigInteger minDepositAndFee = sendAmount.add(securityDeposit).subtract(new BigDecimal(tx.getFee()).multiply(new BigDecimal(1.0 - DUST_TOLERANCE)).toBigInteger());
                BigInteger actualDepositAndFee = check.getReceivedAmount().add(tx.getFee());
                if (actualDepositAndFee.compareTo(minDepositAndFee) < 0) throw new RuntimeException("Deposit amount + fee is not enough, needed " + minDepositAndFee + " but was " + actualDepositAndFee);
            } finally {
                try {
                    daemon.flushTxPool(txHash); // flush tx from pool
                } catch (MoneroRpcError err) {
                    System.out.println(daemon.getRpcConnection());
                    throw err.getCode() == -32601 ? new RuntimeException("Failed to flush tx from pool. Arbitrator must use trusted, unrestricted daemon") : err;
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
            }, connectionsService.getDefaultRefreshPeriodMs() / 1000);
            return txs;
        }
    }

    public MoneroTx getTxWithCache(String txHash) {
        List<MoneroTx> cachedTxs = getTxsWithCache(Arrays.asList(txHash));
        return cachedTxs.isEmpty() ? null : cachedTxs.get(0);
    }

    public List<MoneroTx> getTxsWithCache(List<String> txHashes) {
        synchronized (txCache) {

            // get cached txs
            List<MoneroTx> cachedTxs = new ArrayList<MoneroTx>();
            List<String> uncachedTxHashes = new ArrayList<String>();
            for (int i = 0; i < txHashes.size(); i++) {
                if (txCache.containsKey(txHashes.get(i))) cachedTxs.add(txCache.get(txHashes.get(i)).orElse(null));
                else uncachedTxHashes.add(txHashes.get(i));
            }

            // return txs from cache if available, otherwise fetch
            return uncachedTxHashes.isEmpty() ? cachedTxs : getTxs(txHashes);
        }
    }

    private void closeMainWallet(boolean save) {
        try {
            closeWallet(wallet, true);
            wallet = null;
            walletListeners.clear();
        } catch (Exception e) {
            log.warn("Error closing monero-wallet-rpc subprocess. Was Haveno stopped manually with ctrl+c?");
        }
    }

    public void shutDown(boolean save) {
        this.isShutDown = true;
        closeMainWallet(save);
    }

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void initialize() {

        // initialize main wallet if connected or previously created
        maybeInitMainWallet();

        // set and listen to daemon connection
        connectionsService.addListener(newConnection -> setDaemonConnection(newConnection));
    }

    private void maybeInitMainWallet() {
        if (wallet != null) throw new RuntimeException("Main wallet is already initialized");

        // open or create wallet
        MoneroWalletConfig walletConfig = new MoneroWalletConfig().setPath(MONERO_WALLET_NAME).setPassword(getWalletPassword());
        if (MoneroUtils.walletExists(xmrWalletFile.getPath())) {
            wallet = openWallet(walletConfig, rpcBindPort);
        } else if (connectionsService.getConnection() != null && Boolean.TRUE.equals(connectionsService.getConnection().isConnected())) {
            wallet = createWallet(walletConfig, rpcBindPort, true);
        }

        // wallet is not initialized until connected to a daemon
        if (wallet != null) {

            // sync wallet which updates app startup state
            trySyncMainWallet();

            if (connectionsService.getDaemon() == null) System.out.println("Daemon: null");
            else {
                System.out.println("Daemon uri: " + connectionsService.getDaemon().getRpcConnection().getUri());
                System.out.println("Daemon height: " + connectionsService.getDaemon().getInfo().getHeight());
            }
            System.out.println("Monero wallet uri: " + wallet.getRpcConnection().getUri());
            System.out.println("Monero wallet path: " + wallet.getPath());
            System.out.println("Monero wallet primary address: " + wallet.getPrimaryAddress());
            System.out.println("Monero wallet height: " + wallet.getHeight());
            System.out.println("Monero wallet balance: " + wallet.getBalance(0));
            System.out.println("Monero wallet unlocked balance: " + wallet.getUnlockedBalance(0));

            // notify setup that main wallet is initialized
            havenoSetup.getWalletInitialized().set(true); // TODO: change to listener pattern?

            // register internal listener to notify external listeners
            wallet.addListener(new XmrWalletListener());
        }
    }

    private MoneroWalletRpc createWallet(MoneroWalletConfig config, Integer port, boolean sync) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port, sync);

        // must be connected to daemon
        MoneroRpcConnection connection = connectionsService.getConnection();
        if (connection == null || !Boolean.TRUE.equals(connection.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");
        config.setServer(connection);

        // create wallet
        try {
            log.info("Creating wallet " + config.getPath());
            if (!sync) config.setServer(null);
            walletRpc.createWallet(config);
            if (sync) {
                log.info("Syncing wallet " + config.getPath() + " in background");
                walletRpc.startSyncing(connectionsService.getDefaultRefreshPeriodMs());
                log.info("Done starting background sync for wallet " + config.getPath());
            } else {
                walletRpc.setDaemonConnection(connection);
            }
            log.info("Done creating wallet " + walletRpc.getPath());
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc);
            throw e;
        }
    }

    private MoneroWalletRpc openWallet(MoneroWalletConfig config, Integer port) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port, true);

        // open wallet
        try {
            log.info("Opening wallet " + config.getPath());
            walletRpc.openWallet(config);
            walletRpc.setDaemonConnection(connectionsService.getConnection());
            log.info("Done opening wallet " + walletRpc.getPath());
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc);
            throw e;
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port, boolean withConnection) {

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

        MoneroRpcConnection connection = withConnection ? connectionsService.getConnection() : null;
        if (connection != null) {
            cmd.add("--daemon-address");
            cmd.add(connection.getUri());
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

    private void trySyncMainWallet() {
        try {
            log.info("Syncing main wallet");
            wallet.startSyncing(connectionsService.getDefaultRefreshPeriodMs()); // start syncing wallet in background
            wallet.sync(); // blocking
            connectionsService.doneDownload(); // TODO: using this to signify both daemon and wallet synced, refactor sync handling of both
            log.info("Done syncing main wallet");
            saveMainWallet(false); // skip backup on open
        } catch (Exception e) {
            log.warn("Error syncing main wallet: {}", e.getMessage());
        }
    }

    private void setDaemonConnection(MoneroRpcConnection connection) {
        log.info("Setting wallet daemon connection: " + (connection == null ? null : connection.getUri()));
        if (wallet == null) maybeInitMainWallet();
        else {
            wallet.setDaemonConnection(connection);
            if (connection != null) new Thread(() -> trySyncMainWallet()).start();
        }
    }

    private void notifyBalanceListeners() {
        for (XmrBalanceListener balanceListener : balanceListeners) {
            Coin balance;
            if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
            else balance = getAvailableBalance();
            UserThread.execute(new Runnable() { // TODO (woodser): don't execute on UserThread
                @Override
                public void run() {
                    balanceListener.onBalanceChanged(BigInteger.valueOf(balance.value));
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

    // ----------------------------- LEGACY APP -------------------------------

    public XmrAddressEntry getNewAddressEntry() {
        return getOrCreateAddressEntry(XmrAddressEntry.Context.AVAILABLE, Optional.empty());
    }

    public XmrAddressEntry getFreshAddressEntry() {
        List<XmrAddressEntry> unusedAddressEntries = getUnusedAddressEntries();
        if (unusedAddressEntries.isEmpty()) return getNewAddressEntry();
        else return unusedAddressEntries.get(0);
    }

    public XmrAddressEntry recoverAddressEntry(String offerId, String address, XmrAddressEntry.Context context) {
        var available = findAddressEntry(address, XmrAddressEntry.Context.AVAILABLE);
        if (!available.isPresent()) return null;
        return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(available.get(), context, offerId);
    }

    public XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {
        MoneroSubaddress subaddress = wallet.createSubaddress(0);
        XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), context, offerId, null);
        xmrAddressEntryList.addAddressEntry(entry);
        return entry;
    }

    public XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        if (addressEntry.isPresent()) {
            return addressEntry.get();
        } else {
            // We try to use available and not yet used entries
            List<MoneroTxWallet> incomingTxs = getIncomingTxs(null); // pre-fetch all incoming txs to avoid query per subaddress
            Optional<XmrAddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> XmrAddressEntry.Context.AVAILABLE == e.getContext())
                    .filter(e -> isSubaddressUnused(e.getSubaddressIndex(), incomingTxs)).findAny();
            if (emptyAvailableAddressEntry.isPresent()) {
                return xmrAddressEntryList.swapAvailableToAddressEntryWithOfferId(emptyAvailableAddressEntry.get(), context, offerId);
            } else {
                return getNewAddressEntry(offerId, context);
            }
        }
    }

    public XmrAddressEntry getArbitratorAddressEntry() {
        XmrAddressEntry.Context context = XmrAddressEntry.Context.ARBITRATOR;
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> context == e.getContext())
                .findAny();
        return getOrCreateAddressEntry(context, addressEntry);
    }

    public Optional<XmrAddressEntry> getAddressEntry(String offerId, XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
    }

    public void swapTradeEntryToAvailableEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        addressEntryOptional.ifPresent(e -> {
            log.info("swap addressEntry with address {} and offerId {} from context {} to available", e.getAddressString(), e.getOfferId(), context);
            xmrAddressEntryList.swapToAvailable(e);
            saveAddressEntryList();
        });
    }

    public void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
    }

    public void resetAddressEntriesForPendingTrade(String offerId) {
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.MULTI_SIG);
        // We swap also TRADE_PAYOUT to be sure all is cleaned up. There might be cases
        // where a user cannot send the funds
        // to an external wallet directly in the last step of the trade, but the funds
        // are in the Bisq wallet anyway and
        // the dealing with the external wallet is pure UI thing. The user can move the
        // funds to the wallet and then
        // send out the funds to the external wallet. As this cleanup is a rare
        // situation and most users do not use
        // the feature to send out the funds we prefer that strategy (if we keep the
        // address entry it might cause
        // complications in some edge cases after a SPV resync).
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    private XmrAddressEntry getOrCreateAddressEntry(XmrAddressEntry.Context context,
                                                    Optional<XmrAddressEntry> addressEntry) {
        if (addressEntry.isPresent()) {
            return addressEntry.get();
        } else {
            MoneroSubaddress subaddress = wallet.createSubaddress(0);
            XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), context, null, null);
            log.info("getOrCreateAddressEntry: add new XmrAddressEntry {}", entry);
            xmrAddressEntryList.addAddressEntry(entry);
            return entry;
        }
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
        return getAvailableAddressEntries().stream().filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).isPositive()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
        return xmrAddressEntryList.getAddressEntriesAsListImmutable();
    }

    public List<XmrAddressEntry> getUnusedAddressEntries() {
        return getAvailableAddressEntries().stream()
                .filter(e -> isSubaddressUnused(e.getSubaddressIndex()))
                .collect(Collectors.toList());
    }

    public boolean isSubaddressUnused(int subaddressIndex) {
        return isSubaddressUnused(subaddressIndex, null);
    }

    private boolean isSubaddressUnused(int subaddressIndex, List<MoneroTxWallet> incomingTxs) {
        return getNumTxOutputsForSubaddress(subaddressIndex, incomingTxs) == 0;
    }

    public int getNumTxOutputsForSubaddress(int subaddressIndex) {
        return getNumTxOutputsForSubaddress(subaddressIndex, null);
    }

    public int getNumTxOutputsForSubaddress(int subaddressIndex, List<MoneroTxWallet> incomingTxs) {
        if (incomingTxs == null) incomingTxs = getIncomingTxs(subaddressIndex);
        int numUnspentOutputs = 0;
        for (MoneroTxWallet tx : incomingTxs) {
            if (tx.getTransfers(new MoneroTransferQuery().setSubaddressIndex(subaddressIndex)).isEmpty()) continue;
            numUnspentOutputs += tx.isConfirmed() ? tx.getOutputsWallet(new MoneroOutputQuery().setSubaddressIndex(subaddressIndex)).size() : 1; // TODO: monero-project does not provide outputs for unconfirmed txs
        }
        return numUnspentOutputs;
    }

    public List<MoneroTxWallet> getIncomingTxs() {
        return getIncomingTxs(null);
    }

    public List<MoneroTxWallet> getIncomingTxs(Integer subaddressIndex) {
        return wallet.getTxs(new MoneroTxQuery()
                .setTransferQuery((new MoneroTransferQuery()
                        .setAccountIndex(0)
                        .setSubaddressIndex(subaddressIndex)
                        .setIsIncoming(true)))
                .setIncludeOutputs(true));
    }

    public Coin getBalanceForAddress(String address) {
        return getBalanceForSubaddress(wallet.getAddressIndex(address).getIndex());
    }

    public Coin getBalanceForSubaddress(int subaddressIndex) {
        return HavenoUtils.atomicUnitsToCoin(wallet.getBalance(0, subaddressIndex));
    }

    public Coin getAvailableBalanceForSubaddress(int subaddressIndex) {
        return HavenoUtils.atomicUnitsToCoin(wallet.getUnlockedBalance(0, subaddressIndex));
    }

    public Coin getBalance() {
        return wallet != null ? HavenoUtils.atomicUnitsToCoin(wallet.getBalance(0)) : Coin.ZERO;
    }

    public Coin getAvailableBalance() {
        return wallet != null ? HavenoUtils.atomicUnitsToCoin(wallet.getUnlockedBalance(0)) : Coin.ZERO;
    }

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> availableAndPayout = Stream.concat(getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream(), getFundedAvailableAddressEntries().stream());
        Stream<XmrAddressEntry> available = Stream.concat(availableAndPayout, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).isPositive());
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

    public void setHavenoSetup(HavenoSetup havenoSetup) {
        this.havenoSetup = havenoSetup;
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
