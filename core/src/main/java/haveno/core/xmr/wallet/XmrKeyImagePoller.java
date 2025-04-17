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
 */
@Slf4j
public class XmrKeyImagePoller {

    private MoneroDaemon daemon;
    private long refreshPeriodMs;
    private Map<String, Set<String>> keyImageGroups = new HashMap<String, Set<String>>();
    private Set<XmrKeyImageListener> listeners = new HashSet<XmrKeyImageListener>();
    private TaskLooper looper;
    private Map<String, MoneroKeyImageSpentStatus> lastStatuses = new HashMap<String, MoneroKeyImageSpentStatus>();
    private boolean isPolling = false;
    private Long lastLogPollErrorTimestamp;

    /**
     * Construct the listener.
     */
    public XmrKeyImagePoller() {
        looper = new TaskLooper(() -> poll());
    }

    /**
     * Construct the listener.
     *
     * @param daemon - the Monero daemon to poll
     * @param refreshPeriodMs - refresh period in milliseconds
     */
    public XmrKeyImagePoller(MoneroDaemon daemon, long refreshPeriodMs) {
        looper = new TaskLooper(() -> poll());
        setDaemon(daemon);
        setRefreshPeriodMs(refreshPeriodMs);
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
     * Add a key image to listen to.
     *
     * @param keyImage - the key image to listen to
     */
    public void addKeyImage(String keyImage, String groupId) {
        addKeyImages(Arrays.asList(keyImage), groupId);
    }

    /**
     * Add key images to listen to.
     *
     * @param keyImages - key images to listen to
     */
    public void addKeyImages(Collection<String> keyImages, String groupId) {
        synchronized (this.keyImageGroups) {
            if (!keyImageGroups.containsKey(groupId)) keyImageGroups.put(groupId, new HashSet<String>());
            Set<String> keyImagesGroup = keyImageGroups.get(groupId);
            keyImagesGroup.addAll(keyImages);
            refreshPolling();
        }
    }

    /**
     * Remove key images to listen to.
     *
     * @param keyImages - key images to unlisten to
     */
    public void removeKeyImages(Collection<String> keyImages, String groupId) {
        synchronized (keyImageGroups) {
            Set<String> keyImagesGroup = keyImageGroups.get(groupId);
            if (keyImagesGroup == null) return;
            keyImagesGroup.removeAll(keyImages);
            if (keyImagesGroup.isEmpty()) keyImageGroups.remove(groupId);
            Set<String> allKeyImages = getKeyImages();
            synchronized (lastStatuses) {
                for (String keyImage : keyImages) {
                    if (lastStatuses.containsKey(keyImage) && !allKeyImages.contains(keyImage)) {
                        lastStatuses.remove(keyImage);
                    }
                }
            }
            refreshPolling();
        }
    }

    public void removeKeyImages(String groupId) {
        synchronized (keyImageGroups) {
            Set<String> keyImagesGroup = keyImageGroups.get(groupId);
            if (keyImagesGroup == null) return;
            keyImageGroups.remove(groupId);
            Set<String> allKeyImages = getKeyImages();
            synchronized (lastStatuses) {
                for (String keyImage : keyImagesGroup) {
                    if (lastStatuses.containsKey(keyImage) && !allKeyImages.contains(keyImage)) {
                        lastStatuses.remove(keyImage);
                    }
                }
            }
            refreshPolling();
        }
    }

    /**
     * Clear the key images which stops polling.
     */
    public void clearKeyImages() {
        synchronized (keyImageGroups) {
            keyImageGroups.clear();
            synchronized (lastStatuses) {
                lastStatuses.clear();
            }
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
        synchronized (lastStatuses) {
            if (!lastStatuses.containsKey(keyImage)) return null;
            return XmrKeyImagePoller.isSpent(lastStatuses.get(keyImage));
        }
    }

    /**
     * Indicates if the given key image spent status is spent.
     * 
     * @param status the key image spent status to check
     * @return true if the key image is spent, false if unspent
     */
    public static boolean isSpent(MoneroKeyImageSpentStatus status) {
        return status != MoneroKeyImageSpentStatus.NOT_SPENT;
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

        // fetch spent statuses
        List<MoneroKeyImageSpentStatus> spentStatuses = null;
        List<String> keyImages = new ArrayList<String>(getKeyImages());
        try {
            spentStatuses = keyImages.isEmpty() ? new ArrayList<MoneroKeyImageSpentStatus>() : daemon.getKeyImageSpentStatuses(keyImages); // TODO monero-java: if order of getKeyImageSpentStatuses is guaranteed, then it should take list parameter
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
        synchronized (keyImageGroups) {
            setIsPolling(!getKeyImages().isEmpty() && listeners.size() > 0);
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

    private Set<String> getKeyImages() {
        Set<String> allKeyImages = new HashSet<String>();
        synchronized (keyImageGroups) {
            for (Set<String> keyImagesGroup : keyImageGroups.values()) {
                allKeyImages.addAll(keyImagesGroup);
            }
        }
        return allKeyImages;
    }
}
