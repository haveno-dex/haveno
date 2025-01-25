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

import haveno.core.locale.Res;
import monero.common.MoneroRpcConnection;

public class MoneroNetworkListItem {
    private final MoneroRpcConnection connection;
    private final boolean connected;
    
    public MoneroNetworkListItem(MoneroRpcConnection connection, boolean connected) {
        this.connection = connection;
        this.connected = connected;
    }

    public String getAddress() {
        return connection.getUri();
    }

    public String getConnected() {
        return connected ? Res.get("settings.net.connected") : "";
    }
}
