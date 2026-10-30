/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.settings.network;

import haveno.core.api.XmrConnectionService;
import monero.common.MoneroRpcConnection;
import monero.common.NetworkUtils;

public class MoneroNetworkListItem {
    private static final long STALE_FAILURE_MS = 600000; // remote failures older than this are shown as unchecked
    private final MoneroRpcConnection connection;
    private final boolean current, checking, unchecked;
    private final Boolean connected;
    private final String owner, status;
    private final Long responseTime;
    private final int rank;
    private final DisplayState displayState;

    private record DisplayState(String address, String owner, boolean current, Boolean connected, boolean checking,
                                String status, Long responseTime, Long lastChecked) {}

    // the connection state is captured once so the sort keys stay fixed while the item is listed
    public MoneroNetworkListItem(MoneroRpcConnection connection, boolean current, Boolean connected, boolean checking, String owner) {
        this.connection = connection;
        this.current = current;
        this.connected = current ? connected : Boolean.FALSE;
        this.checking = checking;
        this.owner = owner;
        boolean online = Boolean.TRUE.equals(connection.isConnected());
        unchecked = !current && !checking && (connection.isOnline() == null || isStaleFailure());
        responseTime = online && (!current || isConnected()) ? connection.getResponseTime() : null;
        rank = current ? 0 : checking ? 1 : online ? 2 : unchecked ? 3 : 4;
        status = NetworkSettingsView.text(statusKey(online));
        displayState = new DisplayState(getAddress(), owner, current, this.connected, checking, status, responseTime,
                XmrConnectionService.getLastCheckedTimestamp(connection));
    }

    private String statusKey(boolean online) {
        return checking ? "checking" : isConnected() ? "connected" : current && connected == null ? "connecting" : current ? "notResponding" :
                XmrConnectionService.getCachedDaemonInfo(connection) != null && !Boolean.TRUE.equals(connection.isOnline()) ? "noNodePeers" :
                Boolean.FALSE.equals(connection.isAuthenticated()) ? "requiresLogin" : online ? "online" :
                isLocalNotRunning() ? "notRunning" : unchecked ? "notChecked" : "notReachable";
    }

    // other nodes are only checked when switching, so an old unreachable result no longer says anything about the node
    private boolean isStaleFailure() {
        Long checked = XmrConnectionService.getLastCheckedTimestamp(connection);
        return Boolean.FALSE.equals(connection.isOnline()) && XmrConnectionService.getCachedDaemonInfo(connection) == null &&
                !NetworkUtils.isLocalHost(connection.getUri()) && checked != null && System.currentTimeMillis() - checked > STALE_FAILURE_MS;
    }

    private boolean isLocalNotRunning() {
        return NetworkUtils.isLocalHost(connection.getUri()) && Boolean.FALSE.equals(connection.isOnline()) &&
                XmrConnectionService.getCachedDaemonInfo(connection) == null;
    }

    public boolean hasSameDisplay(MoneroNetworkListItem other) {
        return displayState.equals(other.displayState);
    }

    public MoneroRpcConnection getConnection() {
        return connection;
    }

    public String getAddress() {
        return NetworkSettingsView.displayAddress(connection.getUri());
    }

    public String getOwner() {
        return owner;
    }

    public boolean isCurrent() {
        return current;
    }

    public boolean isConnected() {
        return Boolean.TRUE.equals(connected);
    }

    public boolean isUnavailable() {
        return current && Boolean.FALSE.equals(connected);
    }

    public boolean isChecking() {
        return checking;
    }

    public boolean isUnchecked() {
        return unchecked;
    }

    public Long getResponseTime() {
        return responseTime;
    }

    public int getRank() {
        return rank;
    }

    public String getStatus() {
        return status;
    }
}
