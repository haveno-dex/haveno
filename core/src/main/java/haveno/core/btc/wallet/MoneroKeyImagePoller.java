package haveno.core.btc.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroKeyImageSpentStatus;

/**
 * Poll for changes to the spent status of key images.
 * 
 * TODO: move to monero-java?
 */
@Slf4j
public class MoneroKeyImagePoller {

    private MoneroDaemon daemon;
    private long refreshPeriodMs;
    private List<String> keyImages = new ArrayList<String>();
    private Set<MoneroKeyImageListener> listeners = new HashSet<MoneroKeyImageListener>();
    private TaskLooper looper;
    private Map<String, MoneroKeyImageSpentStatus> lastStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();
    private boolean isPolling = false;

    /**
     * Construct the listener.
     * 
     * @param refreshPeriodMs - refresh period in milliseconds
     * @param keyImages - key images to listen to
     */
    public MoneroKeyImagePoller() {
        looper = new TaskLooper(() -> poll());
    }

    /**
     * Construct the listener.
     * 
     * @param refreshPeriodMs - refresh period in milliseconds
     * @param keyImages - key images to listen to
     */
    public MoneroKeyImagePoller(MoneroDaemon daemon, long refreshPeriodMs, String... keyImages) {
        looper = new TaskLooper(() -> poll());
        setDaemon(daemon);
        setRefreshPeriodMs(refreshPeriodMs);
        setKeyImages(keyImages);
    }

    /**
     * Add a listener to receive notifications.
     * 
     * @param listener - the listener to add
     */
    public void addListener(MoneroKeyImageListener listener) {
        listeners.add(listener);
        refreshPolling();
    }
    
    /**
     * Remove a listener to receive notifications.
     * 
     * @param listener - the listener to remove
     */
    public void removeListener(MoneroKeyImageListener listener) {
        if (!listeners.contains(listener)) throw new MoneroError("Listener is not registered");
        listeners.remove(listener);
        refreshPolling();
    }

    /**
     * Set the Monero daemon to fetch key images from.
     * 
     * @param daemon - the daemon to fetch key images from
     */
    public void setDaemon(MoneroDaemon daemon) {
        this.daemon = daemon;
    }

    /**
     * Get the Monero daemon to fetch key images from.
     * 
     * @return the daemon to fetch key images from
     */
    public MoneroDaemon getDaemon() {
        return daemon;
    }

    /**
     * Set the refresh period in milliseconds.
     * 
     * @param refreshPeriodMs - the refresh period in milliseconds
     */
    public void setRefreshPeriodMs(long refreshPeriodMs) {
        this.refreshPeriodMs = refreshPeriodMs;
    }

    /**
     * Get the refresh period in milliseconds
     * 
     * @return the refresh period in milliseconds
     */
    public long getRefreshPeriodMs() {
        return refreshPeriodMs;
    }

    /**
     * Get a copy of the key images being listened to.
     * 
     * @return the key images to listen to
     */
    public Collection<String> getKeyImages() {
        return new ArrayList<String>(keyImages);
    }

    /**
     * Set the key images to listen to.
     * 
     * @return the key images to listen to
     */
    public void setKeyImages(String... keyImages) {
        synchronized (this.keyImages) {
            this.keyImages.clear();
            addKeyImages(keyImages);
        }
    }

    /**
     * Add a key image to listen to.
     * 
     * @param keyImage - the key image to listen to
     */
    public void addKeyImage(String keyImage) {
        addKeyImages(keyImage);
    }

    /**
     * Add key images to listen to.
     * 
     * @param keyImages - key images to listen to
     */
    public void addKeyImages(String... keyImages) {
        addKeyImages(Arrays.asList(keyImages));
    }

    /**
     * Add key images to listen to.
     * 
     * @param keyImages - key images to listen to
     */
    public void addKeyImages(Collection<String> keyImages) {
        synchronized (this.keyImages) {
            for (String keyImage : keyImages) if (!this.keyImages.contains(keyImage)) this.keyImages.add(keyImage);
            refreshPolling();
        }
    }

    /**
     * Remove a key image to listen to.
     * 
     * @param keyImage - the key image to unlisten to
     */
    public void removeKeyImage(String keyImage) {
        removeKeyImages(keyImage);
    }

    /**
     * Remove key images to listen to.
     * 
     * @param keyImages - key images to unlisten to
     */
    public void removeKeyImages(String... keyImages) {
        removeKeyImages(Arrays.asList(keyImages));
    }

    /**
     * Remove key images to listen to.
     * 
     * @param keyImages - key images to unlisten to
     */
    public void removeKeyImages(Collection<String> keyImages) {
        synchronized (this.keyImages) {
            Set<String> containedKeyImages = new HashSet<String>(keyImages);
            containedKeyImages.retainAll(this.keyImages);
            this.keyImages.removeAll(containedKeyImages);
            for (String lastKeyImage : new HashSet<>(lastStatuses.keySet())) lastStatuses.remove(lastKeyImage);
            refreshPolling();
        }
    }

    /**
     * Indicates if the given key image is spent.
     * 
     * @param keyImage - the key image to check
     * @return true if the key is spent, false if unspent, null if unknown
     */
    public Boolean isSpent(String keyImage) {
        if (!lastStatuses.containsKey(keyImage)) return null;
        return lastStatuses.get(keyImage) != MoneroKeyImageSpentStatus.NOT_SPENT;
    }

    public void poll() {
        synchronized (keyImages) {
            if (daemon == null) {
                log.warn("Cannot poll key images because daemon is null");
                return;
            }
            try {
                
                // fetch spent statuses
                List<MoneroKeyImageSpentStatus> spentStatuses = keyImages.isEmpty() ? new ArrayList<MoneroKeyImageSpentStatus>() : daemon.getKeyImageSpentStatuses(keyImages);

                // collect changed statuses
                Map<String, MoneroKeyImageSpentStatus> changedStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();
                for (int i = 0; i < keyImages.size(); i++) {
                    if (lastStatuses.get(keyImages.get(i)) != spentStatuses.get(i)) {
                        lastStatuses.put(keyImages.get(i), spentStatuses.get(i));
                        changedStatuses.put(keyImages.get(i), spentStatuses.get(i));
                    }
                }

                // announce changes
                if (!changedStatuses.isEmpty()) {
                    for (MoneroKeyImageListener listener : new ArrayList<MoneroKeyImageListener>(listeners)) {
                        listener.onSpentStatusChanged(changedStatuses);
                    }
                }
            } catch (Exception e) {
                log.warn("Error polling key images: " + e.getMessage());
            }
        }
    }

    private void refreshPolling() {
        setIsPolling(keyImages.size() > 0 && listeners.size() > 0);
    }

    private synchronized void setIsPolling(boolean enabled) {
        if (enabled) {
            if (!isPolling) {
                isPolling = true; // TODO monero-java: looper.isPolling()
                looper.start(refreshPeriodMs);
            }
        } else {
            isPolling = false;
            looper.stop();
        }
    }
}
