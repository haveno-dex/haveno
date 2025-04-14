package haveno.core.xmr.wallet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.exception.ExceptionUtils;

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
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public abstract class XmrWalletBase {

    // constants
    public static final int SYNC_PROGRESS_TIMEOUT_SECONDS = 120;
    public static final int DIRECT_SYNC_WITHIN_BLOCKS = 100;
    public static final int SAVE_WALLET_DELAY_SECONDS = 300;

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

    public void syncWithProgress() {
        syncWithProgress(false);
    }

    public void syncWithProgress(boolean repeatSyncToLatestHeight) {
        synchronized (walletLock) {

            // set initial state
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
                if (wallet == null) return;
                long height;
                try {
                    height = wallet.getHeight(); // can get read timeout while syncing
                } catch (Exception e) {
                    if (wallet != null && !isShutDownStarted) {
                        log.warn("Error getting wallet height while syncing with progress: " + e.getMessage());
                        log.warn(ExceptionUtils.getStackTrace(e));
                    }

                    // stop polling and release latch
                    syncProgressError = e;
                    syncProgressLatch.countDown();
                    return;
                }
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
            if (wallet != null) wallet.stopSyncing(); // can become null if interrupted by force close
            isSyncingWithProgress = false;
            if (syncProgressError != null) throw new RuntimeException(syncProgressError);
        }
    }

    public boolean requestSwitchToNextBestConnection(MoneroRpcConnection sourceConnection) {
        if (xmrConnectionService.requestSwitchToNextBestConnection(sourceConnection)) {
            onConnectionChanged(xmrConnectionService.getConnection()); // change connection on same thread
            return true;
        }
        return false;
    }

    public void saveWalletWithDelay() {
        // delay writing to disk to avoid frequent write operations
        if (saveWalletDelayTimer == null) {
            saveWalletDelayTimer = UserThread.runAfter(() -> {
                requestSaveWallet();
                UserThread.execute(() -> saveWalletDelayTimer = null);
            }, SAVE_WALLET_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    // --------------------------------- ABSTRACT -----------------------------

    public abstract void saveWallet();

    public abstract void requestSaveWallet();

    protected abstract void onConnectionChanged(MoneroRpcConnection connection);

    // ------------------------------ PRIVATE HELPERS -------------------------

    private void updateSyncProgress(long height, long targetHeight) {
        resetSyncProgressTimeout();
        UserThread.execute(() -> {

            // set wallet height
            walletHeight.set(height);

            // new wallet reports height 1 before synced
            if (height == 1) {
                downloadListener.progress(0, targetHeight - height, null);
                return;
            }

            // set progress
            long blocksLeft = targetHeight - walletHeight.get();
            if (syncStartHeight == null) syncStartHeight = walletHeight.get();
            double percent = Math.min(1.0, targetHeight == syncStartHeight ? 1.0 : ((double) walletHeight.get() - syncStartHeight) / (double) (targetHeight - syncStartHeight));
            downloadListener.progress(percent, blocksLeft, null);
        });
    }

    private synchronized void resetSyncProgressTimeout() {
        if (syncProgressTimeout != null) syncProgressTimeout.stop();
        syncProgressTimeout = UserThread.runAfter(() -> {
            if (isShutDownStarted) return;
            syncProgressError = new RuntimeException("Sync progress timeout called");
            syncProgressLatch.countDown();
        }, SYNC_PROGRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void setWalletSyncedWithProgress() {
        wasWalletSynced = true;
        isSyncingWithProgress = false;
        syncProgressTimeout.stop();
    }
}
