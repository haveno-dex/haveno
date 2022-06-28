package bisq.core.btc.wallet;

import static com.google.common.base.Preconditions.checkState;

import bisq.common.UserThread;
import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.core.api.AccountServiceListener;
import bisq.core.api.CoreAccountService;
import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.setup.MoneroWalletRpcManager;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.offer.Offer;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.SellerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.TradeUtils;
import bisq.core.util.ParsingUtils;
import com.google.common.util.concurrent.Service.State;
import com.google.inject.name.Named;
import common.utils.JsonUtils;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroUtils;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroNetworkType;
import monero.daemon.model.MoneroOutput;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroCheckTx;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSubaddress;
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
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = MoneroNetworkType.STAGENET;
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_DIR = System.getProperty("user.dir") + File.separator + ".localnet"; // .localnet contains monero-wallet-rpc and wallet files
    private static final String MONERO_WALLET_RPC_PATH = MONERO_WALLET_RPC_DIR + File.separator + "monero-wallet-rpc";
    private static final String MONERO_WALLET_RPC_USERNAME = "haveno_user";
    private static final String MONERO_WALLET_RPC_DEFAULT_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_NAME = "haveno_XMR";
    private static final String MONERO_MULTISIG_WALLET_PREFIX = "xmr_multisig_trade_";
    private static final long MONERO_WALLET_SYNC_PERIOD = 5000l;

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
    private Map<String, MoneroWallet> multisigWallets;

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
        this.multisigWallets = new HashMap<String, MoneroWallet>();
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
                    log.info(getClass() + ".accountService.onAccountCreated()");
                    initialize();
                }

                @Override
                public void onAccountOpened() {
                    log.info(getClass() + ".accountService.onAccountOpened()");
                    initialize();
                }

                @Override
                public void onAccountClosed() {
                    log.info(getClass() + ".accountService.onAccountClosed()");
                    closeAllWallets();
                }

                @Override
                public void onPasswordChanged(String oldPassword, String newPassword) {
                    log.info(getClass() + "accountservice.onPasswordChanged()");
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
        checkState(state == State.STARTING || state == State.RUNNING, "Cannot call until startup is complete");
        return wallet;
    }
    
    public MoneroDaemon getDaemon() {
        return connectionsService.getDaemon();
    }
    
    public CoreMoneroConnectionsService getConnectionsService() {
        return connectionsService;
    }
    
    public String getWalletPassword() {
        return accountService.getPassword() == null ? MONERO_WALLET_RPC_DEFAULT_PASSWORD : accountService.getPassword();
    }

    public boolean multisigWalletExists(String tradeId) {
        return walletExists(MONERO_MULTISIG_WALLET_PREFIX + tradeId);
    }

    // TODO (woodser): test retaking failed trade. create new multisig wallet or replace? cannot reuse
    public MoneroWallet createMultisigWallet(String tradeId) {
        log.info("{}.createMultisigWallet({})", getClass().getSimpleName(), tradeId);
        Trade trade = tradeManager.getOpenTrade(tradeId).get();
        synchronized (trade) {
            if (multisigWallets.containsKey(trade.getId())) return multisigWallets.get(trade.getId());
            String path = MONERO_MULTISIG_WALLET_PREFIX + trade.getId();
            MoneroWallet multisigWallet = createWallet(new MoneroWalletConfig().setPath(path).setPassword(getWalletPassword()), null); // auto-assign port
            multisigWallets.put(trade.getId(), multisigWallet);
            return multisigWallet;
        }
    }

    // TODO (woodser): provide progress notifications during open?
    public MoneroWallet getMultisigWallet(String tradeId) {
        log.info("{}.getMultisigWallet({})", getClass().getSimpleName(), tradeId);
        Trade trade = tradeManager.getTrade(tradeId);
        synchronized (trade) {
            if (multisigWallets.containsKey(trade.getId())) return multisigWallets.get(trade.getId());
            String path = MONERO_MULTISIG_WALLET_PREFIX + trade.getId();
            if (!walletExists(path)) throw new RuntimeException("Multisig wallet does not exist for trade " + trade.getId());
            MoneroWallet multisigWallet = openWallet(new MoneroWalletConfig().setPath(path).setPassword(getWalletPassword()), null);
            multisigWallets.put(trade.getId(), multisigWallet);
            return multisigWallet;
        }
    }

    public void saveWallet(MoneroWallet wallet) {
        wallet.save();
        backupWallet(wallet.getPath());
    }

    public void closeMultisigWallet(String tradeId) {
        log.info("{}.closeMultisigWallet({})", getClass().getSimpleName(), tradeId);
        Trade trade = tradeManager.getTrade(tradeId);
        synchronized (trade) {
            if (!multisigWallets.containsKey(trade.getId())) throw new RuntimeException("Multisig wallet to close was not previously opened for trade " + trade.getId());
            MoneroWallet wallet = multisigWallets.remove(trade.getId());
            closeWallet(wallet, true);
        }
    }

    public boolean deleteMultisigWallet(String tradeId) {
        log.info("{}.deleteMultisigWallet({})", getClass().getSimpleName(), tradeId);
        Trade trade = tradeManager.getTrade(tradeId);
        synchronized (trade) {
            String walletName = MONERO_MULTISIG_WALLET_PREFIX + tradeId;
            if (!walletExists(walletName)) return false;
            if (multisigWallets.containsKey(trade.getId())) closeMultisigWallet(tradeId);
            deleteWallet(walletName);
            return true;
        }
    }

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        try {
            MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
            printTxs("XmrWalletService.createTx", tx);
            return tx;
        } catch (Exception e) {
            throw e;
        }
    }

    
    /**
     * Create the reserve tx and freeze its inputs. The deposit amount is returned
     * to the sender's payout address. Additional funds are reserved to allow
     * fluctuations in the mining fee.
     * 
     * @param tradeFee is the trade fee
     * @param depositAmount the amount needed for the trade minus the trade fee
     * @return a transaction to reserve a trade
     */
    public MoneroTxWallet createReserveTx(BigInteger tradeFee, String returnAddress, BigInteger depositAmount) {
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // get expected mining fee
            MoneroTxWallet miningFeeTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                    .addDestination(returnAddress, depositAmount));
            BigInteger miningFee = miningFeeTx.getFee();

            // create reserve tx
            MoneroTxWallet reserveTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                    .addDestination(returnAddress, depositAmount.add(miningFee.multiply(BigInteger.valueOf(3l))))); // add thrice the mining fee // TODO (woodser): really require more funds on top of security deposit?

            // freeze inputs
            for (MoneroOutput input : reserveTx.getInputs()) {
                wallet.freezeOutput(input.getKeyImage().getHex());
            }

            return reserveTx;
        }
    }
    
    /**
     * Create the multisig deposit tx and freeze its inputs.
     * 
     * @return MoneroTxWallet the multisig deposit tx
     */
    public MoneroTxWallet createDepositTx(Trade trade) {
        BigInteger tradeFee = ParsingUtils.coinToAtomicUnits(trade instanceof MakerTrade ? trade.getOffer().getMakerFee() : trade.getTakerFee());
        Offer offer = trade.getProcessModel().getOffer();
        BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(trade instanceof SellerTrade ? offer.getAmount().add(offer.getSellerSecurityDeposit()) : offer.getBuyerSecurityDeposit());
        String multisigAddress = trade.getProcessModel().getMultisigAddress();
        MoneroWallet wallet = getWallet();
        synchronized (wallet) {

            // create deposit tx
            MoneroTxWallet depositTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                    .addDestination(multisigAddress, depositAmount));

            // freeze deposit inputs
            for (MoneroOutput input : depositTx.getInputs()) {
                wallet.freezeOutput(input.getKeyImage().getHex());
            }

            return depositTx;
        }
    }

    /**
     * Verify a reserve or deposit transaction used during trading.
     * Checks double spends, deposit amount and destination, trade fee, and mining fee.
     * The transaction is submitted but not relayed to the pool then flushed.
     * 
     * @param depositAddress is the expected destination address for the deposit amount
     * @param depositAmount is the expected amount deposited to multisig
     * @param tradeFee is the expected fee for trading
     * @param txHash is the transaction hash
     * @param txHex is the transaction hex
     * @param txKey is the transaction key
     * @param keyImages are expected key images of inputs, ignored if null
     * @param miningFeePadding verifies depositAmount has additional funds to cover mining fee increase
     */
    public void verifyTradeTx(String depositAddress, BigInteger depositAmount, BigInteger tradeFee, String txHash, String txHex, String txKey, List<String> keyImages, boolean miningFeePadding) {
        boolean submittedToPool = false;
        MoneroDaemon daemon = getDaemon();
        MoneroWallet wallet = getWallet();
        try {
            
            // get tx from daemon
            MoneroTx tx = daemon.getTx(txHash);
            
            // if tx is not submitted, submit but do not relay
            if (tx == null) {
                MoneroSubmitTxResult result = daemon.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
                if (!result.isGood()) throw new RuntimeException("Failed to submit tx to daemon: " + JsonUtils.serialize(result));
                submittedToPool = true;
                tx = daemon.getTx(txHash);
            } else if (tx.isRelayed()) {
                throw new RuntimeException("Trade tx must not be relayed");
            }
            
            // verify reserved key images
            if (keyImages != null) {
                Set<String> txKeyImages = new HashSet<String>();
                for (MoneroOutput input : tx.getInputs()) txKeyImages.add(input.getKeyImage().getHex());
                if (!txKeyImages.equals(new HashSet<String>(keyImages))) throw new Error("Reserve tx's inputs do not match claimed key images");
            }
            
            // verify the unlock height
            if (tx.getUnlockHeight() != 0) throw new RuntimeException("Unlock height must be 0");

            // verify trade fee
            String feeAddress = TradeUtils.FEE_ADDRESS;
            MoneroCheckTx check = wallet.checkTxKey(txHash, txKey, feeAddress);
            if (!check.isGood()) throw new RuntimeException("Invalid proof of trade fee");
            if (!check.getReceivedAmount().equals(tradeFee)) throw new RuntimeException("Trade fee is incorrect amount, expected " + tradeFee + " but was " + check.getReceivedAmount());

            // verify mining fee
            BigInteger feeEstimate = daemon.getFeeEstimate().multiply(BigInteger.valueOf(txHex.length())); // TODO (woodser): fee estimates are too high, use more accurate estimate
            BigInteger feeThreshold = feeEstimate.multiply(BigInteger.valueOf(1l)).divide(BigInteger.valueOf(2l)); // must be at least 50% of estimated fee
            tx = daemon.getTx(txHash);
            if (tx.getFee().compareTo(feeThreshold) < 0) {
                throw new RuntimeException("Mining fee is not enough, needed " + feeThreshold + " but was " + tx.getFee());
            }

            // verify deposit amount
            check = wallet.checkTxKey(txHash, txKey, depositAddress);
            if (!check.isGood()) throw new RuntimeException("Invalid proof of deposit amount");
            BigInteger depositThreshold = depositAmount;
            if (miningFeePadding) depositThreshold  = depositThreshold.add(feeThreshold.multiply(BigInteger.valueOf(3l))); // prove reserve of at least deposit amount + (3 * min mining fee)
            if (check.getReceivedAmount().compareTo(depositThreshold) < 0) throw new RuntimeException("Deposit amount is not enough, needed " + depositThreshold + " but was " + check.getReceivedAmount());
        } finally {

            // flush tx from pool if we added it
            if (submittedToPool) daemon.flushTxPool(txHash);
        }
    }

    public void shutDown() {
        closeAllWallets();
    }

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void initialize() {

        // initialize main wallet if connected or previously created
        tryInitMainWallet();

        // update wallet connections on change
        connectionsService.addListener(newConnection -> {
            setWalletDaemonConnections(newConnection);
        });
    }
    
    private boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + ".keys").exists();
    }

    private void tryInitMainWallet() {
        MoneroWalletConfig walletConfig = new MoneroWalletConfig().setPath(MONERO_WALLET_NAME).setPassword(getWalletPassword());
        if (MoneroUtils.walletExists(xmrWalletFile.getPath())) {
            wallet = openWallet(walletConfig, rpcBindPort);
        } else if (connectionsService.getConnection() != null && Boolean.TRUE.equals(connectionsService.getConnection().isConnected())) {
            wallet = createWallet(walletConfig, rpcBindPort); // wallet requires connection to daemon to correctly set height
        }

        // wallet is not initialized until connected to a daemon
        if (wallet != null) {
            try {
                wallet.sync(); // blocking
                connectionsService.doneDownload(); // TODO: using this to signify both daemon and wallet synced, refactor sync handling of both
                saveWallet(wallet);
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("Monero wallet path: " + wallet.getPath());
            System.out.println("Monero wallet address: " + wallet.getPrimaryAddress());
            System.out.println("Monero wallet uri: " + wallet.getRpcConnection().getUri());
            System.out.println("Monero wallet height: " + wallet.getHeight());
            System.out.println("Monero wallet balance: " + wallet.getBalance(0));
            System.out.println("Monero wallet unlocked balance: " + wallet.getUnlockedBalance(0));

            // register internal listener to notify external listeners
            wallet.addListener(new XmrWalletListener());
        }
    }

    private MoneroWalletRpc createWallet(MoneroWalletConfig config, Integer port) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port);

        // must be connected to daemon
        MoneroRpcConnection connection = connectionsService.getConnection();
        if (connection == null || !Boolean.TRUE.equals(connection.isConnected())) throw new RuntimeException("Must be connected to daemon before creating wallet");

        // create wallet
        try {
            walletRpc.createWallet(config);
            walletRpc.startSyncing(MONERO_WALLET_SYNC_PERIOD);
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc);
            throw e;
        }
    }

    private MoneroWalletRpc openWallet(MoneroWalletConfig config, Integer port) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port);

        // open wallet
        try {
            walletRpc.openWallet(config);
            walletRpc.startSyncing(MONERO_WALLET_SYNC_PERIOD);
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc);
            throw e;
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port) {

        // check if monero-wallet-rpc exists
        if (!new File(MONERO_WALLET_RPC_PATH).exists()) throw new Error("monero-wallet-rpc executable doesn't exist at path " + MONERO_WALLET_RPC_PATH
                + "; copy monero-wallet-rpc to the project root or set WalletConfig.java MONERO_WALLET_RPC_PATH for your system");

        // build command to start monero-wallet-rpc
        List<String> cmd = new ArrayList<>(Arrays.asList( // modifiable list
                MONERO_WALLET_RPC_PATH, "--" + MONERO_NETWORK_TYPE.toString().toLowerCase(), "--rpc-login",
                MONERO_WALLET_RPC_USERNAME + ":" + getWalletPassword(), "--wallet-dir", walletDir.toString()));
        MoneroRpcConnection connection = connectionsService.getConnection();
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

    private void setWalletDaemonConnections(MoneroRpcConnection connection) {
        log.info("Setting wallet daemon connections: " + (connection == null ? null : connection.getUri()));
        if (wallet == null) tryInitMainWallet();
        if (wallet != null) wallet.setDaemonConnection(connection);
        for (MoneroWallet multisigWallet : multisigWallets.values()) multisigWallet.setDaemonConnection(connection);
    }

    private void notifyBalanceListeners() {
        for (XmrBalanceListener balanceListener : balanceListeners) {
            Coin balance;
            if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
            else balance = getAvailableConfirmedBalance();
            UserThread.execute(new Runnable() { // TODO (woodser): don't execute on UserThread
                @Override
                public void run() {
                    balanceListener.onBalanceChanged(BigInteger.valueOf(balance.value));
                }
            });
        }
    }

    private void changeWalletPasswords(String oldPassword, String newPassword) {
        List<String> tradeIds = tradeManager.getOpenTrades().stream().map(Trade::getId).collect(Collectors.toList());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(10, 1 + tradeIds.size()));
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    wallet.changePassword(oldPassword, newPassword);
                    saveWallet(wallet);
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                }
            }
        });
        for (String tradeId : tradeIds) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    MoneroWallet multisigWallet = getMultisigWallet(tradeId); // TODO (woodser): this unnecessarily connects and syncs unopen wallets and leaves open
                    if (multisigWallet == null) return;
                    multisigWallet.changePassword(oldPassword, newPassword);
                    saveWallet(multisigWallet);
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60000, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            try { pool.shutdownNow(); }
            catch (Exception e2) { }
            throw new RuntimeException(e);
        }
    }

    private void closeWallet(MoneroWallet walletRpc, boolean save) {
        log.info("{}.closeWallet({}, {})", getClass().getSimpleName(), walletRpc.getPath(), save);
        MoneroError err = null;
        try {
            if (save) saveWallet(walletRpc);
            walletRpc.close();
        } catch (MoneroError e) {
            err = e;
        }
        MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) walletRpc);
        if (err != null) throw err;
    }

    private void deleteWallet(String walletName) {
        log.info("{}.deleteWallet({})", getClass().getSimpleName(), walletName);
        if (!walletExists(walletName)) throw new Error("Wallet does not exist at path: " + walletName);
        String path = walletDir.toString() + File.separator + walletName;
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".keys").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".address.txt").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        deleteBackupWallets(walletName);
    }

    private void closeAllWallets() {

        // collect wallets to shutdown
        List<MoneroWallet> openWallets = new ArrayList<MoneroWallet>();
        if (wallet != null) openWallets.add(wallet);
        for (String multisigWalletKey : multisigWallets.keySet()) {
            openWallets.add(multisigWallets.get(multisigWalletKey));
        }

        // done if no open wallets
        if (openWallets.isEmpty()) return;

        // close all wallets in parallel
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(10, openWallets.size()));
        for (MoneroWallet openWallet : openWallets) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        closeWallet(openWallet, true);
                    } catch (Exception e) {
                        log.warn("Error closing monero-wallet-rpc subprocess. Was Haveno stopped manually with ctrl+c?");
                    }
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60000, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new RuntimeException(e);
        }

        // clear wallets
        wallet = null;
        multisigWallets.clear();
    }
    
    private void backupWallet(String walletName) {
        FileUtil.rollingBackup(walletDir, walletName, 20);
        FileUtil.rollingBackup(walletDir, walletName + ".keys", 20);
        FileUtil.rollingBackup(walletDir, walletName + ".address.txt", 20);
    }

    private void deleteBackupWallets(String walletName) {
        FileUtil.deleteRollingBackup(walletDir, walletName);
        FileUtil.deleteRollingBackup(walletDir, walletName + ".keys");
        FileUtil.deleteRollingBackup(walletDir, walletName + ".address.txt");
    }
    
    // ----------------------------- LEGACY APP -------------------------------
    
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
            Optional<XmrAddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> XmrAddressEntry.Context.AVAILABLE == e.getContext())
                    .filter(e -> isSubaddressUnused(e.getSubaddressIndex())).findAny();
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

    public boolean isSubaddressUnused(int subaddressIndex) {
        return subaddressIndex != 0 && getBalanceForSubaddress(subaddressIndex).value == 0;
        // return !wallet.getSubaddress(accountIndex, 0).isUsed(); // TODO: isUsed()
        // does not include unconfirmed funds
    }

    public Coin getBalanceForSubaddress(int subaddressIndex) {

        // get subaddress balance
        BigInteger balance = wallet.getBalance(0, subaddressIndex);

//    // balance from xmr wallet does not include unconfirmed funds, so add them  // TODO: support lower in stack?
//    for (MoneroTxWallet unconfirmedTx : wallet.getTxs(new MoneroTxQuery().setIsConfirmed(false))) {
//      for (MoneroTransfer transfer : unconfirmedTx.getTransfers()) {
//        if (transfer.getAccountIndex() == subaddressIndex) {
//          balance = transfer.isIncoming() ? balance.add(transfer.getAmount()) : balance.subtract(transfer.getAmount());
//        }
//      }
//    }

        System.out.println("Returning balance for subaddress " + subaddressIndex + ": " + balance.longValueExact());

        return Coin.valueOf(balance.longValueExact());
    }

    public Coin getAvailableConfirmedBalance() {
        return wallet != null ? Coin.valueOf(wallet.getUnlockedBalance(0).longValueExact()) : Coin.ZERO;
    }

    public Coin getSavingWalletBalance() {
        return wallet != null ? Coin.valueOf(wallet.getBalance(0).longValueExact()) : Coin.ZERO;
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
        balanceListeners.add(listener);
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
    
    
    public static void printTxs(String tracePrefix, MoneroTxWallet... txs) {
        StringBuilder sb = new StringBuilder();
        for (MoneroTxWallet tx : txs) sb.append('\n' + tx.toString());
        log.info("\n" + tracePrefix + ":" + sb.toString());
    }
    
    // -------------------------------- HELPERS -------------------------------
    
    /**
     * Processes internally before notifying external listeners.
     * 
     * TODO: no longer neccessary to execute on user thread?
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
