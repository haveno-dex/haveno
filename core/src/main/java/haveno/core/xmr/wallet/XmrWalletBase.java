package haveno.core.xmr.wallet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
import javafx.beans.property.SimpleLongProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletFull;
import monero.wallet.model.MoneroSyncResult;
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public abstract class XmrWalletBase {

    // constants
    private static final int SYNC_TIMEOUT_SECONDS = 180;
    private static final String SYNC_TIMEOUT_MSG = "Sync timeout called";
    private static final long SAVE_AFTER_ELAPSED_SECONDS = 300;
    private Object saveIntervalLock = new Object();
    protected long lastSaveTimeMs = 0;

    // inherited
    protected MoneroWallet wallet;
    @Getter
    protected final Object walletLock = new Object();
    protected Timer saveWalletDelayTimer;
    @Getter
    protected XmrConnectionService xmrConnectionService;
    protected boolean wasWalletSynced;
    protected final Map<String, Optional<MoneroTx>> txCache = new HashMap<String, Optional<MoneroTx>>();
    protected boolean isClosingWallet;
    protected boolean isSyncingWithProgress;
    protected Long syncStartHeight;
    protected TaskLooper syncProgressLooper;
    protected CountDownLatch syncProgressLatch;
    protected Exception syncProgressError;
    protected Timer syncProgressTimeout;
    protected final DownloadListener downloadListener = new DownloadListener();
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
        return syncWithTimeout(SYNC_TIMEOUT_SECONDS);
    }

    public MoneroSyncResult syncWithTimeout(long timeoutSec) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getDaemonLock()) {
                ExecutorService executor = Executors.newSingleThreadExecutor();

                Callable<MoneroSyncResult> task = () -> {
                    MoneroSyncResult result = wallet.sync();
                    saveWalletIfElapsedTime();
                    walletHeight.set(wallet.getHeight());
                    return result;
                };

                Future<MoneroSyncResult> future = executor.submit(task);

                try {
                    return future.get(timeoutSec, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new RuntimeException(SYNC_TIMEOUT_MSG, e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Sync failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status
                    throw new RuntimeException("Sync was interrupted", e);
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    public void syncWithProgress() {
        syncWithProgress(false);
    }

    public void syncWithProgress(boolean repeatSyncToLatestHeight) {
        synchronized (walletLock) {
            try {

                // set initial state
                if (isSyncingWithProgress) log.warn("Syncing with progress while already syncing with progress. That should never happen");
                resetSyncProgressTimeout();
                isSyncingWithProgress = true;
                syncProgressError = null;
                long targetHeightAtStart = xmrConnectionService.getTargetHeight();
                syncStartHeight = walletHeight.get();
                updateSyncProgress(syncStartHeight, targetHeightAtStart);

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
                            long appliedTargetHeight = repeatSyncToLatestHeight ? xmrConnectionService.getTargetHeight() : targetHeightAtStart;
                            updateSyncProgress(height, appliedTargetHeight);
                        }
                    });
                    setWalletSyncedWithProgress();
                    return;
                }

                // start polling wallet for progress
                syncProgressLatch = new CountDownLatch(1);
                syncProgressLooper = new TaskLooper(() -> {

                    // stop if shutdown or null wallet
                    if (isShutDownStarted || wallet == null) {
                        syncProgressError = new RuntimeException("Shut down or wallet has become null while syncing with progress");
                        syncProgressLatch.countDown();
                        return;
                    }

                    // get height
                    long height;
                    try {
                        height = wallet.getHeight(); // can get read timeout while syncing
                    } catch (Exception e) {
                        if (wallet != null && !isShutDownStarted) {
                            log.warn("Error getting wallet height while syncing with progress: " + e.getMessage());
                        }
                        if (wallet == null) {
                            syncProgressError = new RuntimeException("Wallet has become null while syncing with progress");
                            syncProgressLatch.countDown();
                        }
                        return;
                    }

                    // update sync progress
                    long appliedTargetHeight = repeatSyncToLatestHeight ? xmrConnectionService.getTargetHeight() : targetHeightAtStart;
                    updateSyncProgress(height, appliedTargetHeight);
                    if (height >= appliedTargetHeight) {
                        setWalletSyncedWithProgress();
                        syncProgressLatch.countDown();
                    }
                });
                wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
                syncProgressLooper.start(1000);

                // wait for sync to complete
                HavenoUtils.awaitLatch(syncProgressLatch);

                // stop polling
                syncProgressLooper.stop();
                syncProgressTimeout.stop();
                if (wallet != null) { // can become null if interrupted by force close
                    if (syncProgressError == null || !HavenoUtils.isUnresponsive(syncProgressError)) { // TODO: skipping stop sync if unresponsive because wallet will hang. if unresponsive, wallet is assumed to be force restarted by caller, but that should be done internally here instead of externally?
                        wallet.stopSyncing();
                        saveWalletIfElapsedTime();
                    }
                }
                if (syncProgressError != null) throw new RuntimeException(syncProgressError);
            } catch (Exception e) {
                throw e;
            } finally {
                isSyncingWithProgress = false;
            }
        }
    }

    public boolean requestSwitchToNextBestConnection(MoneroRpcConnection sourceConnection) {
        if (xmrConnectionService.requestSwitchToNextBestConnection(sourceConnection)) {
            onConnectionChanged(xmrConnectionService.getConnection()); // change connection on same thread
            return true;
        }
        return false;
    }

    public void saveWalletIfElapsedTime() {
        synchronized (saveIntervalLock) {
            if (System.currentTimeMillis() - lastSaveTimeMs >= SAVE_AFTER_ELAPSED_SECONDS * 1000) {
                saveWallet();
                lastSaveTimeMs = System.currentTimeMillis();
            }
        }
    }

    public void requestSaveWalletIfElapsedTime() {
        ThreadUtils.submitToPool(() -> saveWalletIfElapsedTime());
    }

    public static boolean isSyncWithProgressTimeout(Throwable e) {
        return e.getMessage().contains(SYNC_TIMEOUT_MSG);
    }

    // --------------------------------- ABSTRACT -----------------------------

    public abstract void saveWallet();

    protected abstract void onConnectionChanged(MoneroRpcConnection connection);

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void updateSyncProgress(long height, long targetHeight) {

        // reset progress timeout if height advanced
        if (height != walletHeight.get()) {
            resetSyncProgressTimeout();
        }

        // set wallet height
        walletHeight.set(height);

        // new wallet reports height 1 before synced
        if (height == 1) {
            downloadListener.progress(0, targetHeight - height, null);
            return;
        }

        // set progress
        long blocksLeft = targetHeight - height;
        if (syncStartHeight == null) syncStartHeight = height;
        double percent = Math.min(1.0, targetHeight == syncStartHeight ? 1.0 : ((double) height - syncStartHeight) / (double) (targetHeight - syncStartHeight));
        downloadListener.progress(percent, blocksLeft, null);
    }

    private synchronized void resetSyncProgressTimeout() {
        if (syncProgressTimeout != null) syncProgressTimeout.stop();
        syncProgressTimeout = UserThread.runAfter(() -> {
            if (isShutDownStarted) return;
            syncProgressError = new RuntimeException(SYNC_TIMEOUT_MSG);
            syncProgressLatch.countDown();
        }, SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void setWalletSyncedWithProgress() {
        wasWalletSynced = true;
        isSyncingWithProgress = false;
        if (syncProgressTimeout != null) syncProgressTimeout.stop();
    }
}
