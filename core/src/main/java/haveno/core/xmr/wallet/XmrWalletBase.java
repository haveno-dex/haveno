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

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

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
    private static final String RECEIVED_ERROR_RESPONSE_MSG = "Received error response from RPC request";
    private static final long SAVE_AFTER_ELAPSED_SECONDS = 300;
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
    protected boolean isSyncingWithoutProgress;
    protected boolean isSyncingWithProgress;
    protected Long syncStartHeight;
    protected TaskLooper syncProgressLooper;
    protected CountDownLatch syncProgressLatch;
    protected Exception syncProgressError;
    protected Timer syncProgressTimeout;
    @Getter
    protected final DownloadListener syncProgressListener = new DownloadListener();
    protected final LongProperty walletHeight = new SimpleLongProperty(0);
    @Getter
    protected boolean isShutDownStarted;
    @Getter
    protected boolean isShutDown;
    protected Subscription connectionUpdateSubscription;

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
                    if (isSyncing()) log.warn("Syncing without progress while already syncing. That should never happen.");
                    if (isShutDownStarted) throw new RuntimeException("Cannot sync wallet because shut down is started");
                    isSyncingWithoutProgress = true;
                    walletHeight.set(wallet.getHeight());
                    MoneroSyncResult result = wallet.sync();
                    walletHeight.set(wallet.getHeight());
                    wasWalletSynced = true;
                    return result;
                };

                Future<MoneroSyncResult> future = executor.submit(task);

                try {
                    return future.get(timeoutSec, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new RuntimeException(SYNC_TIMEOUT_MSG, e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Sync failed", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupt status
                    throw new RuntimeException("Sync was interrupted", e);
                } finally {
                    isSyncingWithoutProgress = false;
                    saveWalletIfElapsedTime();
                    executor.shutdownNow();
                }
            }
        }
    }

    public void syncWithProgress() {
        MoneroWallet sourceWallet = wallet;
        synchronized (walletLock) {

            // check that shut down is not started
            if (isShutDownStarted) throw new RuntimeException("Cannot sync wallet with progress because shut down is started");

            // subscribe to height updates for latest sync progress
            UserThread.execute(() -> {
                connectionUpdateSubscription = EasyBind.subscribe(xmrConnectionService.numUpdatesProperty(), newValue -> {
                    UserThread.execute(() -> {
                        if (newValue != null && connectionUpdateSubscription != null && isSyncingWithProgress) updateSyncProgress(null);
                    });
                });
            });

            try {

                // set initial state
                if (isSyncing()) log.warn("Syncing with progress while already syncing. That should never happen.");
                resetSyncProgressTimeout();
                isSyncingWithProgress = true;
                syncStartHeight = null;
                syncProgressError = null;
                updateSyncProgress(wallet.getHeight());

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
                            updateSyncProgress(height);
                        }
                    });
                    onDoneSyncWithProgress();
                    return;
                }

                // start polling wallet for progress
                syncProgressLatch = new CountDownLatch(1);
                syncProgressLooper = new TaskLooper(() -> {

                    // stop if shutdown or null wallet
                    if (isShutDownStarted || wallet == null || wallet != sourceWallet) {
                        syncProgressError = new RuntimeException("Wallet is shutting down or has changed while syncing with progress");
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
                        if (isShutDownStarted || wallet == null || wallet != sourceWallet) {
                            syncProgressError = new RuntimeException("Wallet is shutting down or has changed while getting height with progress");
                            syncProgressLatch.countDown();
                        }
                        return;
                    }

                    // update sync progress
                    long targetHeight = xmrConnectionService.getTargetHeight();
                    updateSyncProgress(height);
                    if (height >= targetHeight) {
                        syncProgressLatch.countDown();
                    }
                });
                wallet.startSyncing(xmrConnectionService.getRefreshPeriodMs());
                syncProgressLooper.start(1000);

                // wait for sync to complete
                HavenoUtils.awaitLatch(syncProgressLatch);
                syncProgressLooper.stop();

                // set synced or throw error
                if (syncProgressError == null) onDoneSyncWithProgress();
                else throw new RuntimeException(syncProgressError);
            } catch (Exception e) {
                throw e;
            } finally {
                isSyncingWithProgress = false;
                if (syncProgressTimeout != null) syncProgressTimeout.stop();
                UserThread.execute(() -> {
                    connectionUpdateSubscription.unsubscribe();
                    connectionUpdateSubscription = null;
                });
            }
        }
    }

    public boolean wasWalletSynced() {
        return wasWalletSynced;
    }

    public boolean requestSwitchToNextBestConnection(MoneroRpcConnection sourceConnection) {
        if (xmrConnectionService.requestSwitchToNextBestConnection(sourceConnection)) {
            onConnectionChanged(xmrConnectionService.getConnection()); // change connection on same thread
            return true;
        }
        return false;
    }

    public void saveWalletIfElapsedTime() {
        synchronized (walletLock) {
            if (System.currentTimeMillis() - lastSaveTimeMs >= SAVE_AFTER_ELAPSED_SECONDS * 1000) {
                saveWallet();
                lastSaveTimeMs = System.currentTimeMillis();
            }
        }
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

    // --------------------------------- ABSTRACT -----------------------------

    public abstract void saveWallet();

    protected abstract void onConnectionChanged(MoneroRpcConnection connection);

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void updateSyncProgress(Long height) {

        // use last height if no update
        long appliedHeight = height == null ? walletHeight.get() : height;

        // reset progress timeout if height advanced
        if (appliedHeight != walletHeight.get()) {
            resetSyncProgressTimeout();
        }

        // set wallet height
        walletHeight.set(appliedHeight);

        // calculate progress
        long targetHeight = xmrConnectionService.getTargetHeight();
        long blocksRemaining = appliedHeight <= 1 ? -1 : targetHeight - appliedHeight; // unknown blocks left if height <= 1
        if (syncStartHeight == null) syncStartHeight = appliedHeight;
        double percent = Math.min(1.0, targetHeight == syncStartHeight ? 1.0 : ((double) appliedHeight - syncStartHeight) / (double) (targetHeight - syncStartHeight));
        if (percent >= 1.0) wasWalletSynced = true; // set synced state before announcing progress

        // notify progress listener on user thread
        UserThread.execute(() -> {
            if (connectionUpdateSubscription == null) return; // unsubscribed
            syncProgressListener.progress(percent, blocksRemaining);
        });
    }

    private synchronized void resetSyncProgressTimeout() {
        if (syncProgressTimeout != null) syncProgressTimeout.stop();
        syncProgressTimeout = UserThread.runAfter(() -> {
            if (isShutDownStarted) return;
            syncProgressError = new RuntimeException(SYNC_TIMEOUT_MSG);
            syncProgressLatch.countDown();
        }, SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void onDoneSyncWithProgress() {

        // stop syncing and save wallet if elapsed time
        if (wallet != null) { // can become null if interrupted by force close
            if (syncProgressError == null || !HavenoUtils.isUnresponsive(syncProgressError)) { // TODO: skipping stop sync if unresponsive because wallet will hang. if unresponsive, wallet is assumed to be force restarted by caller, but that should be done internally here instead of externally?
                wallet.stopSyncing();
                saveWalletIfElapsedTime();
            }
        }
    }

    protected boolean isExpectedWalletError(Exception e) {
        return e.getMessage() != null && e.getMessage().contains(RECEIVED_ERROR_RESPONSE_MSG); // TODO: why does this error happen "normally"?
    }
}
