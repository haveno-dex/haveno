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

import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.util.FormattingUtils;
import haveno.desktop.util.DisplayUtils;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionState;
import haveno.network.p2p.network.OutboundConnection;
import haveno.network.p2p.network.PeerType;
import haveno.network.p2p.network.Statistic;
import haveno.network.p2p.peers.PeerManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

public class P2pNetworkListItem {
    private final Statistic statistic;
    private final Connection connection;
    private final PeerManager peerManager;
    private final Subscription sentBytesSubscription, receivedBytesSubscription, onionAddressSubscription, roundTripTimeSubscription;
    private final ClockWatcher clockWatcher;

    private final StringProperty since = new SimpleStringProperty();
    private final StringProperty sentBytes = new SimpleStringProperty();
    private final StringProperty receivedBytes = new SimpleStringProperty();
    private final StringProperty peerType = new SimpleStringProperty();
    private final StringProperty roundTripTime = new SimpleStringProperty();
    private final StringProperty onionAddress = new SimpleStringProperty();
    private final ClockWatcher.Listener listener;
    private boolean cleanedUp;

    P2pNetworkListItem(Connection connection, PeerManager peerManager, ClockWatcher clockWatcher) {
        this.connection = connection;
        this.peerManager = peerManager;
        this.clockWatcher = clockWatcher;
        this.statistic = connection.getStatistic();

        sentBytesSubscription = EasyBind.subscribe(statistic.sentBytesProperty(),
                e -> updateProperty(sentBytes, FormattingUtils.formatBytes((long) e)));
        receivedBytesSubscription = EasyBind.subscribe(statistic.receivedBytesProperty(),
                e -> updateProperty(receivedBytes, FormattingUtils.formatBytes((long) e)));
        onionAddressSubscription = EasyBind.subscribe(connection.getPeersNodeAddressProperty(),
                nodeAddress -> updateProperty(onionAddress, nodeAddress != null ? nodeAddress.getFullAddress() : Res.get("settings.net.notKnownYet")));
        roundTripTimeSubscription = EasyBind.subscribe(statistic.roundTripTimeProperty(),
                roundTripTime -> updateProperty(this.roundTripTime, NetworkSettingsView.formatResponseTime(roundTripTime.intValue() <= 0 ? null : roundTripTime.longValue())));

        listener = new ClockWatcher.Listener() {
            @Override
            public void onSecondTick() {
                updateSince();
                updatePeerType();
            }

            @Override
            public void onMinuteTick() {
            }
        };
        clockWatcher.addListener(listener);
        updateSince();
        updatePeerType();
    }

    private void updateProperty(StringProperty property, String value) {
        Runnable update = () -> {
            if (!cleanedUp) property.set(value);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else UserThread.execute(update);
    }

    private void updateSince() {
        long minutes = Math.max(0, (System.currentTimeMillis() - getCreationTimestamp()) / 60000);
        since.set(NetworkSettingsView.text(minutes == 0 ? "justNow" : "minutesAgo", minutes));
    }

    public StringProperty sinceProperty() {
        return since;
    }

    public void cleanup() {
        cleanedUp = true;
        sentBytesSubscription.unsubscribe();
        receivedBytesSubscription.unsubscribe();
        onionAddressSubscription.unsubscribe();
        roundTripTimeSubscription.unsubscribe();
        clockWatcher.removeListener(listener);
    }

    public void updatePeerType() {
        ConnectionState connectionState = connection.getConnectionState();
        if (connectionState.getPeerType() == PeerType.DIRECT_MSG_PEER) {
            peerType.set(Res.get("settings.net.peer") + " · " + NetworkSettingsView.text("directPeerSuffix"));
        } else {
            // check the seed list directly since the state flag is set before an inbound peer announces its address
            String peerOrSeed = peerManager.isSeedNode(connection) ? Res.get("settings.net.seedNode") : Res.get("settings.net.peer");
            if (connectionState.getPeerType() == PeerType.INITIAL_DATA_EXCHANGE) {
                peerType.set(Res.get("settings.net.initialDataExchange", peerOrSeed));
            } else {
                peerType.set(peerOrSeed);
            }
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isInbound() {
        return !(connection instanceof OutboundConnection);
    }

    public long getCreationTimestamp() {
        return statistic.getCreationDate().getTime();
    }

    public Long getResponseTime() {
        int time = statistic.roundTripTimeProperty().get();
        return time <= 0 ? null : (long) time;
    }

    public long getSentBytesValue() {
        return statistic.sentBytesProperty().get();
    }

    public long getReceivedBytesValue() {
        return statistic.receivedBytesProperty().get();
    }

    public String getCreationDate() {
        return DisplayUtils.formatDateTime(statistic.getCreationDate());
    }

    public String getOnionAddress() {
        return onionAddress.get();
    }

    public StringProperty onionAddressProperty() {
        return onionAddress;
    }

    public String getPeerType() {
        return peerType.get();
    }

    public StringProperty peerTypeProperty() {
        return peerType;
    }

    public StringProperty sentBytesProperty() {
        return sentBytes;
    }

    public StringProperty receivedBytesProperty() {
        return receivedBytes;
    }

    public StringProperty roundTripTimeProperty() {
        return roundTripTime;
    }
}
