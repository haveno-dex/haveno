package bisq.core.btc.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import monero.common.MoneroError;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroKeyImageSpentStatus;

/**
 * Poll for changes to the spent status of key images.
 */
public class MoneroKeyImagePoller {

    private MoneroDaemon daemon;
    private long refreshPeriodMs;
    private List<String> keyImages = new ArrayList<String>();
    private Set<MoneroKeyImageListener> listeners = new HashSet<MoneroKeyImageListener>();
    private TaskLooper looper;
    private Map<String, MoneroKeyImageSpentStatus> lastStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();

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
        synchronized (keyImages) {
            this.keyImages.clear();
            this.keyImages.addAll(Arrays.asList(keyImages));
            refreshPolling();
        }
    }

    /**
     * Add a key image to listen to.
     * 
     * @param keyImage - the key image to listen to
     */
    public void addKeyImage(String keyImage) {
        synchronized (keyImages) {
            addKeyImages(keyImage);
            refreshPolling();
        }
    }

    /**
     * Add key images to listen to.
     * 
     * @param keyImages - key images to listen to
     */
    public void addKeyImages(String... keyImages) {
        synchronized (keyImages) {
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
        synchronized (keyImages) {
            removeKeyImages(keyImage);
            refreshPolling();
        }
    }

    /**
     * Remove key images to listen to.
     * 
     * @param keyImages - key images to unlisten to
     */
    public void removeKeyImages(String... keyImages) {
        synchronized (keyImages) {
            for (String keyImage : keyImages) if (!this.keyImages.contains(keyImage)) throw new MoneroError("Key image not registered with poller: " + keyImage);
            this.keyImages.removeAll(Arrays.asList(keyImages));
        }
    }

    public void poll() {
        synchronized (keyImages) {

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
            for (MoneroKeyImageListener listener : new ArrayList<MoneroKeyImageListener>(listeners)) listener.onSpentStatusChanged(changedStatuses);
        }
    }

    private void refreshPolling() {
        setIsPolling(listeners.size() > 0);
    }

    private void setIsPolling(boolean isPolling) {
        if (isPolling) looper.start(refreshPeriodMs);
        else looper.stop();
    }
}
