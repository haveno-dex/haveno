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

import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroKeyImageSpentStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import haveno.core.trade.HavenoUtils;

/**
 * Poll for changes to the spent status of key images.
 *
 * TODO: move to monero-java?
 */
@Slf4j
public class XmrKeyImagePoller {

    private MoneroDaemon daemon;
    private long refreshPeriodMs;
    private List<String> keyImages = new ArrayList<String>();
    private Set<XmrKeyImageListener> listeners = new HashSet<XmrKeyImageListener>();
    private TaskLooper looper;
    private Map<String, MoneroKeyImageSpentStatus> lastStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();
    private boolean isPolling = false;
    private Long lastLogPollErrorTimestamp;

    /**
     * Construct the listener.
     *
     * @param refreshPeriodMs - refresh period in milliseconds
     * @param keyImages - key images to listen to
     */
    public XmrKeyImagePoller() {
        looper = new TaskLooper(() -> poll());
    }

    /**
     * Construct the listener.
     *
     * @param refreshPeriodMs - refresh period in milliseconds
     * @param keyImages - key images to listen to
     */
    public XmrKeyImagePoller(MoneroDaemon daemon, long refreshPeriodMs, String... keyImages) {
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
    public void addListener(XmrKeyImageListener listener) {
        listeners.add(listener);
        refreshPolling();
    }

    /**
     * Remove a listener to receive notifications.
     *
     * @param listener - the listener to remove
     */
    public void removeListener(XmrKeyImageListener listener) {
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
        synchronized (keyImages) {
            return new ArrayList<String>(keyImages);
        }
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
            synchronized (lastStatuses) {
                for (String lastKeyImage : new HashSet<>(lastStatuses.keySet())) lastStatuses.remove(lastKeyImage);
            }
            refreshPolling();
        }
    }

    /**
     * Clear the key images which stops polling.
     */
    public void clearKeyImages() {
        setKeyImages();
    }

    /**
     * Indicates if the given key image is spent.
     *
     * @param keyImage - the key image to check
     * @return true if the key is spent, false if unspent, null if unknown
     */
    public Boolean isSpent(String keyImage) {
        synchronized (lastStatuses) {
            if (!lastStatuses.containsKey(keyImage)) return null;
            return lastStatuses.get(keyImage) != MoneroKeyImageSpentStatus.NOT_SPENT;
        }
    }

    /**
     * Get the last known spent status for the given key image.
     * 
     * @param keyImage the key image to get the spent status for
     * @return the last known spent status of the key image
     */
    public MoneroKeyImageSpentStatus getLastSpentStatus(String keyImage) {
        synchronized (lastStatuses) {
            return lastStatuses.get(keyImage);
        }
    }

    public void poll() {
        if (daemon == null) {
            log.warn("Cannot poll key images because daemon is null");
            return;
        }

        // get copy of key images to fetch
        List<String> keyImages = new ArrayList<String>(getKeyImages());

        // fetch spent statuses
        List<MoneroKeyImageSpentStatus> spentStatuses = null;
        try {
            if (keyImages.isEmpty()) spentStatuses = new ArrayList<MoneroKeyImageSpentStatus>();
            else {
                spentStatuses = daemon.getKeyImageSpentStatuses(keyImages); // TODO monero-java: if order of getKeyImageSpentStatuses is guaranteed, then it should take list parameter
            }
        } catch (Exception e) {

            // limit error logging
            if (lastLogPollErrorTimestamp == null || System.currentTimeMillis() - lastLogPollErrorTimestamp > HavenoUtils.LOG_POLL_ERROR_PERIOD_MS) {
                log.warn("Error polling spent status of key images: " + e.getMessage());
                lastLogPollErrorTimestamp = System.currentTimeMillis();
            }
            return;
        }

        // collect changed statuses
        Map<String, MoneroKeyImageSpentStatus> changedStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();
        synchronized (lastStatuses) {
            for (int i = 0; i < spentStatuses.size(); i++) {
                if (spentStatuses.get(i) != lastStatuses.get(keyImages.get(i))) {
                    lastStatuses.put(keyImages.get(i), spentStatuses.get(i));
                    changedStatuses.put(keyImages.get(i), spentStatuses.get(i));
                }
            }
        }

        // announce changes
        if (!changedStatuses.isEmpty()) {
            for (XmrKeyImageListener listener : new ArrayList<XmrKeyImageListener>(listeners)) {
                listener.onSpentStatusChanged(changedStatuses);
            }
        }
    }

    private void refreshPolling() {
        synchronized (keyImages) {
            setIsPolling(keyImages.size() > 0 && listeners.size() > 0);
        }
    }

    private synchronized void setIsPolling(boolean enabled) {
        if (enabled) {
            if (!isPolling) {
                isPolling = true; // TODO: use looper.isStarted(), synchronize
                looper.start(refreshPeriodMs);
            }
        } else {
            isPolling = false;
            looper.stop();
        }
    }
}
