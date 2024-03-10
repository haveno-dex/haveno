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

import monero.daemon.model.MoneroPeer;

public class MoneroNetworkListItem {
    private final MoneroPeer peer;

    public MoneroNetworkListItem(MoneroPeer peer) {
        this.peer = peer;
    }

    public String getOnionAddress() {
        return peer.getHost() + ":" + peer.getPort();
    }

    public String getVersion() {
        return "";
    }

    public String getSubVersion() {
        return "";
    }

    public String getHeight() {
        return String.valueOf(peer.getHeight());
    }
}
