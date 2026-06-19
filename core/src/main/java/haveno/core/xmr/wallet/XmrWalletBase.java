package haveno.core.xmr.wallet;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.api.XmrConnectionService;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.setup.DownloadListener;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.SimpleLongProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletFull;
import monero.wallet.model.MoneroSyncResult;
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public abstract class XmrWalletBase {

    // constants
    protected static final int MAX_SYNC_ATTEMPTS = 5;
    protected static final long SYNC_TIMEOUT_MS = 180000;
    private static final String SYNC_TIMEOUT_MSG = "Sync timeout called";
    private static final String RECEIVED_ERROR_RESPONSE_MSG = "Received error response from RPC request";
    private static final long SAVE_AFTER_ELAPSED_SECONDS = 300;
    private static final long SAVE_PROGRESS_CHECK_PERIOD_MS = 10000;

    // inherited
    protected MoneroWallet wallet;
    @Getter
    protected final Object walletLock = new Object();
    private final Object resetSyncProgressTimeoutLock = new Object();
    protected Timer saveWalletDelayTimer;
    @Getter
    protected XmrConnectionService xmrConnectionService;
    protected boolean wasWalletSynced;
    protected long lastSaveTimeMs = 0;
    protected boolean isSyncingWithoutProgress;
    protected boolean isSyncingWithProgress;
    private final Object syncWithProgressLock = new Object();
    protected Long syncStartHeight;
    protected TaskLooper syncProgressLooper;
    protected CountDownLatch syncProgressLatch;
    protected Exception syncProgressError;
    protected Timer syncProgressTimeout;
    protected long syncProgressTargetHeight;
    @Getter
    protected final DownloadListener syncProgressListener = new DownloadListener();
    protected final LongProperty walletHeight = new SimpleLongProperty(0);
    @Getter
    protected boolean isShutDownStarted;
    @Getter
    protected boolean isShutDown;

    // private
    private boolean testReconnectOnStartup = false; // test reconnecting on startup while syncing so the wallet is blocked
    private String testReconnectMonerod1 = "http://xmr-node.cakewallet.com:18081";
    private String testReconnectMonerod2 = "http://nodex.monerujo.io:18081";

    public XmrWalletBase() {
        this.xmrConnectionService = HavenoUtils.xmrConnectionService;
    }

    public MoneroSyncResult sync() {
        return syncWithTimeout(SYNC_TIMEOUT_MS);
    }

    public MoneroSyncResult syncWithTimeout(Long syncTimeoutMs) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getDaemonLock()) {
                ExecutorService executor = Executors.newSingleThreadExecutor();

                Callable<MoneroSyncResult> task = () -> {
                    if (isSyncing()) log.warn("Syncing without progress while already syncing. That should never happen.");
                    isSyncingWithoutProgress = true;
                    walletHeight.set(wallet.getHeight());
                    setUnknownSyncProgress();
                    try {
                        MoneroSyncResult result = wallet.sync();
                        walletHeight.set(wallet.getHeight());
                        wasWalletSynced = true;
                        return result;
                    } finally { 
                        clearSyncProgress();
                    }
                };

                Future<MoneroSyncResult> future = executor.submit(task);

                try {
                    return future.get(syncTimeoutMs == null ? SYNC_TIMEOUT_MS : syncTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new RuntimeException(SYNC_TIMEOUT_MSG, e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Sync failed: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status
                    throw new RuntimeException("Sync was interrupted: " + e.getMessage(), e);
                } finally {
                    isSyncingWithoutProgress = false;
                    saveWalletIfElapsedTime();
                    executor.shutdownNow();
                }
            }
        }
    }

    protected void setUnknownSyncProgress() {
        UserThread.execute(() -> syncProgressListener.progress(0, -1));
    }

    protected void clearSyncProgress() {
        UserThread.execute(() -> syncProgressListener.progress(-1, -1));
    }

    public void syncWithProgress() {
        syncWithProgress(null);
    }

    public void syncWithProgress(Long initialSyncTimeoutMs) {
        synchronized (syncWithProgressLock) {
            MoneroWallet sourceWallet = wallet;
            try {

                // set initial state
                if (isSyncing()) log.warn("Syncing with progress while already syncing. That should never happen.");
                resetSyncProgressTimeout(initialSyncTimeoutMs);
                isSyncingWithProgress = true;
                syncStartHeight = null;
                syncProgressError = null;
                syncProgressTargetHeight = xmrConnectionService.getTargetHeight();
                updateSyncProgress(wallet.getHeight(), syncProgressTargetHeight);

                // done if already synced
                if (wallet.getHeight() >= syncProgressTargetHeight - 1) {
                    onDoneSyncWithProgress();
                    return;
                }

                // test connection changing on startup before wallet synced
                if (testReconnectOnStartup) {
                    UserThread.runAfter(() -> {
                        log.warn("Testing connection change on startup before wallet synced");
                        if (xmrConnectionService.getConnection().getUri().equals(testReconnectMonerod1)) xmrConnectionService.setConnection(testReconnectMonerod2);
                        else xmrConnectionService.setConnection(testReconnectMonerod1);
                    }, 1);
                    testReconnectOnStartup = false; // only run once
                }

                // native wallet provides sync notifications
                if (wallet instanceof MoneroWalletFull) {
                    if (testReconnectOnStartup) HavenoUtils.waitFor(1000); // delay sync to test
                    wallet.sync(new MoneroWalletListener() {
                        @Override
                        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
                            updateSyncProgress(height, endHeight);
                        }
                    });
                    onDoneSyncWithProgress();
                    return;
                }

                // start polling wallet for progress
                syncProgressLatch = new CountDownLatch(1);
                syncProgressLooper = new TaskLooper(() -> {

                    // stop if wallet has changed
                    if (wallet == null || wallet != sourceWallet) {
                        syncProgressError = new RuntimeException("Wallet is null or has changed while syncing with progress");
                        syncProgressLatch.countDown();
                        return;
                    }

                    // get height
                    long height;
                    try {
                        height = wallet.getHeight(); // can get read timeout while syncing
                    } catch (Exception e) {
                        if (syncProgressError == null && wallet != null && !isShutDownStarted) {
                            log.warn("Error getting wallet height while syncing with progress: " + e.getMessage());
                        }
                        if (wallet == null || wallet != sourceWallet) {
                            syncProgressError = new RuntimeException("Wallet is null or has changed while syncing with progress");
                            syncProgressLatch.countDown();
                        }
                        return;
                    }

                    // update sync progress
                    updateSyncProgress(height, syncProgressTargetHeight);
                    if (height >= syncProgressTargetHeight - 1) {
                        syncProgressLatch.countDown();
                        return;
                    }

                    // update target height after each update to prevent stalling on new blocks
                    syncProgressTargetHeight = xmrConnectionService.getTargetHeight();
                });
                wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
                syncProgressLooper.start(1000);

                // save wallet periodically
                TaskLooper saveProgressLooper = new TaskLooper(() -> {
                    if (syncProgressError != null) return; // skip if sync errored
                    try {
                        saveWalletIfElapsedTime(false);
                    } catch (Exception e) {
                        if (syncProgressError == null) log.warn("Error periodically saving wallet during sync with progress: {}", e.getMessage());
                    }
                });
                saveProgressLooper.start(SAVE_PROGRESS_CHECK_PERIOD_MS);

                // wait for sync to complete
                try {
                    HavenoUtils.awaitLatch(syncProgressLatch);
                } finally {
                    saveProgressLooper.stop();
                }
                syncProgressLooper.stop();

                // finish processing
                onDoneSyncWithProgress();
            } catch (Exception e) {
                throw e;
            } finally {
                isSyncingWithProgress = false;
                if (syncProgressTimeout != null) syncProgressTimeout.stop();
                clearSyncProgress();
            }
        }
    }

    public boolean wasWalletSynced() {
        return wasWalletSynced;
    }

    public void saveWallet() {
        synchronized (walletLock) {
            saveWalletNoSync();
        }
    }

    public void saveWalletIfElapsedTime() {
        saveWalletIfElapsedTime(true);
    }

    public void saveWalletIfElapsedTime(boolean acquireLock) {
        if (!isTimeElapsedForSave()) return; // skip if possible
        if (!acquireLock) {
            saveWalletNoSync(); // skip walletLock; only safe for rpc wallets, whose requests the rpc serializes
            return;
        }
        synchronized (walletLock) {
            if (isTimeElapsedForSave()) saveWalletNoSync();
        }
    }

    protected boolean isTimeElapsedForSave() {
        return System.currentTimeMillis() - lastSaveTimeMs >= SAVE_AFTER_ELAPSED_SECONDS * 1000;
    }

    public void requestSaveWalletIfElapsedTime() {
        ThreadUtils.submitToPool(() -> saveWalletIfElapsedTime());
    }

    public boolean isSyncing() {
        return isSyncingWithProgress || isSyncingWithoutProgress;
    }

    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return syncProgressListener.percentageProperty();
    }

    public ReadOnlyLongProperty blocksRemainingProperty() {
        return syncProgressListener.blocksRemainingProperty();
    }

    public static boolean isSyncWithProgressTimeout(Throwable e) {
        return e.getMessage() != null && e.getMessage().contains(SYNC_TIMEOUT_MSG);
    }

    public boolean isProxyApplied() {
        MoneroRpcConnection connection = xmrConnectionService.getConnection();
        if (connection != null && connection.isOnion()) return true; // must use proxy if connected to onion
        return xmrConnectionService.isProxyApplied() && HavenoUtils.preferences.isProxyApplied(wasWalletSynced);
    }

    public long getRefreshPeriodMs() {
        return xmrConnectionService.getRefreshPeriodMs(isProxyApplied());
    }

    public long getInitialSyncTimeoutMs() {
        return getRefreshPeriodMs() + 5000; // add padding to guarantee a sync cycle with monero-wallet-rpc (200 ms refresh evaluation period + sync time)
    }

    // --------------------------------- ABSTRACT -----------------------------

    protected abstract void saveWalletNoSync();

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void updateSyncProgress(Long height, long targetHeight) {

        // use last height if no update
        long appliedHeight = height == null ? walletHeight.get() : height;

        // reset progress timeout if height advanced
        if (appliedHeight != walletHeight.get()) {
            resetSyncProgressTimeout(SYNC_TIMEOUT_MS); // revert to default timeout after any change
        }

        // set wallet height
        walletHeight.set(appliedHeight);

        // calculate progress
        long blocksRemaining = appliedHeight <= 1 ? -1 : targetHeight - 1 - appliedHeight; // unknown blocks left if height <= 1
        if (syncStartHeight == null && appliedHeight > 1) syncStartHeight = appliedHeight;
        double percent = syncStartHeight == null || appliedHeight <= 1 ? 0.0 : Math.min(1.0, syncStartHeight >= targetHeight - 1  ? 1.0 : ((double) appliedHeight - syncStartHeight) / (double) (targetHeight - 1 - syncStartHeight));
        if (percent >= 1.0) wasWalletSynced = true; // set synced state before announcing progress

        // notify progress listener on user thread
        UserThread.execute(() -> {
            syncProgressListener.progress(percent, blocksRemaining);
        });
    }

    private void resetSyncProgressTimeout(Long syncTimeoutMs) {
        synchronized (resetSyncProgressTimeoutLock) {
            if (syncProgressTimeout != null) syncProgressTimeout.stop();
            syncProgressTimeout = UserThread.runAfter(() -> {
                if (isShutDownStarted) return;
                syncProgressError = new RuntimeException(SYNC_TIMEOUT_MSG);
                syncProgressLatch.countDown();
            }, syncTimeoutMs == null ? SYNC_TIMEOUT_MS : syncTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // TODO: this is a race condition with syncProgressTimeout
    private void onDoneSyncWithProgress() {
        if (syncProgressError == null) wasWalletSynced = true; // this is redundant but conservative to set again

        // stop syncing and save wallet if elapsed time
        if (wallet != null) { // can become null if interrupted by force close

            // TODO: skipping stop sync if unresponsive because wallet will hang. if unresponsive, wallet is assumed to be force restarted by caller, but that should be done internally here instead of externally?
            if (syncProgressError == null || !HavenoUtils.isUnresponsive(syncProgressError)) {
                wallet.stopSyncing();
                saveWalletIfElapsedTime();
            }
        }

        if (syncProgressError != null) throw new RuntimeException(syncProgressError);
    }

    protected boolean isExpectedWalletError(Exception e) {
        return e.getMessage() != null && (HavenoUtils.isUnresponsive(e) || e.getMessage().contains(RECEIVED_ERROR_RESPONSE_MSG)); // TODO: why does this error happen "normally"?
    }
}
