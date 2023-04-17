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

package haveno.core.app;

import haveno.common.UserThread;
import haveno.core.api.CoreMoneroConnectionsService;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Consumer;

@Singleton
@Slf4j
public class P2PNetworkSetup {
    private final PriceFeedService priceFeedService;
    private final P2PService p2PService;
    private final CoreMoneroConnectionsService connectionService;
    private final Preferences preferences;

    @SuppressWarnings("FieldCanBeLocal")
    private MonadicBinding<String> p2PNetworkInfoBinding;

    @Getter
    final StringProperty p2PNetworkInfo = new SimpleStringProperty();
    @Getter
    final StringProperty p2PNetworkIconId = new SimpleStringProperty();
    @Getter
    final StringProperty p2PNetworkStatusIconId = new SimpleStringProperty();
    @Getter
    final BooleanProperty splashP2PNetworkAnimationVisible = new SimpleBooleanProperty(true);
    @Getter
    final StringProperty p2pNetworkLabelId = new SimpleStringProperty("footer-pane");
    @Getter
    final StringProperty p2pNetworkWarnMsg = new SimpleStringProperty();
    @Getter
    final BooleanProperty updatedDataReceived = new SimpleBooleanProperty();
    @Getter
    final BooleanProperty p2pNetworkFailed = new SimpleBooleanProperty();

    @Inject
    public P2PNetworkSetup(PriceFeedService priceFeedService,
                           P2PService p2PService,
                           CoreMoneroConnectionsService connectionService,
                           Preferences preferences) {

        this.priceFeedService = priceFeedService;
        this.p2PService = p2PService;
        this.connectionService = connectionService;
        this.preferences = preferences;
    }

    BooleanProperty init(Runnable onReadyHandler, @Nullable Consumer<Boolean> displayTorNetworkSettingsHandler) {
        StringProperty bootstrapState = new SimpleStringProperty();
        StringProperty bootstrapWarning = new SimpleStringProperty();
        BooleanProperty hiddenServicePublished = new SimpleBooleanProperty();
        BooleanProperty initialP2PNetworkDataReceived = new SimpleBooleanProperty();

        p2PNetworkInfoBinding = EasyBind.combine(bootstrapState, bootstrapWarning, p2PService.getNumConnectedPeers(),
                connectionService.numPeersProperty(), hiddenServicePublished, initialP2PNetworkDataReceived,
                (state, warning, numP2pPeers, numBtcPeers, hiddenService, dataReceived) -> {
                    String result;
                    int p2pPeers = (int) numP2pPeers;
                    if (warning != null && p2pPeers == 0) {
                        result = warning;
                    } else {
                        String p2pInfo = Res.get("mainView.footer.p2pInfo", numBtcPeers, numP2pPeers);
                        if (dataReceived && hiddenService) {
                            result = p2pInfo;
                        } else if (p2pPeers == 0)
                            result = state;
                        else
                            result = state + " / " + p2pInfo;
                    }
                    return result;
                });
        p2PNetworkInfoBinding.subscribe((observable, oldValue, newValue) -> {
            UserThread.execute(() -> p2PNetworkInfo.set(newValue));
        });

        bootstrapState.set(Res.get("mainView.bootstrapState.connectionToTorNetwork"));

        p2PService.getNetworkNode().addConnectionListener(new ConnectionListener() {
            @Override
            public void onConnection(Connection connection) {
                updateNetworkStatusIndicator();
            }

            @Override
            public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
                updateNetworkStatusIndicator();
                // We only check at seed nodes as they are running the latest version
                // Other disconnects might be caused by peers running an older version
                if (connection.getConnectionState().isSeedNode() &&
                        closeConnectionReason == CloseConnectionReason.RULE_VIOLATION) {
                    log.warn("RULE_VIOLATION onDisconnect closeConnectionReason={}, connection={}",
                            closeConnectionReason, connection);
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }
        });

        final BooleanProperty p2pNetworkInitialized = new SimpleBooleanProperty();
        p2PService.start(new P2PServiceListener() {
            @Override
            public void onTorNodeReady() {
                log.debug("onTorNodeReady");
                bootstrapState.set(Res.get("mainView.bootstrapState.torNodeCreated"));
                p2PNetworkIconId.set("image-connection-tor");

                // We want to get early connected to the price relay so we call it already now
                priceFeedService.setCurrencyCodeOnInit();
                priceFeedService.requestPrices();

                // invoke handler when network ready
                onReadyHandler.run();
            }

            @Override
            public void onHiddenServicePublished() {
                log.debug("onHiddenServicePublished");
                hiddenServicePublished.set(true);
                bootstrapState.set(Res.get("mainView.bootstrapState.hiddenServicePublished"));
            }

            @Override
            public void onDataReceived() {
                log.debug("onRequestingDataCompleted");
                initialP2PNetworkDataReceived.set(true);
                bootstrapState.set(Res.get("mainView.bootstrapState.initialDataReceived"));
                splashP2PNetworkAnimationVisible.set(false);
                p2pNetworkInitialized.set(true);
            }

            @Override
            public void onNoSeedNodeAvailable() {
                log.warn("onNoSeedNodeAvailable");
                if (p2PService.getNumConnectedPeers().get() == 0)
                    bootstrapWarning.set(Res.get("mainView.bootstrapWarning.noSeedNodesAvailable"));
                else
                    bootstrapWarning.set(null);

                splashP2PNetworkAnimationVisible.set(false);
                p2pNetworkInitialized.set(true);
            }

            @Override
            public void onNoPeersAvailable() {
                log.warn("onNoPeersAvailable");
                if (p2PService.getNumConnectedPeers().get() == 0) {
                    p2pNetworkWarnMsg.set(Res.get("mainView.p2pNetworkWarnMsg.noNodesAvailable"));
                    bootstrapWarning.set(Res.get("mainView.bootstrapWarning.noNodesAvailable"));
                    p2pNetworkLabelId.set("splash-error-state-msg");
                } else {
                    bootstrapWarning.set(null);
                    p2pNetworkLabelId.set("footer-pane");
                }
                splashP2PNetworkAnimationVisible.set(false);
                p2pNetworkInitialized.set(true);
            }

            @Override
            public void onUpdatedDataReceived() {
                log.debug("onUpdatedDataReceived");
                splashP2PNetworkAnimationVisible.set(false);
                updatedDataReceived.set(true);
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                log.error("onSetupFailed");
                p2pNetworkWarnMsg.set(Res.get("mainView.p2pNetworkWarnMsg.connectionToP2PFailed", throwable.getMessage()));
                splashP2PNetworkAnimationVisible.set(false);
                bootstrapWarning.set(Res.get("mainView.bootstrapWarning.bootstrappingToP2PFailed"));
                p2pNetworkLabelId.set("splash-error-state-msg");
                p2pNetworkFailed.set(true);
            }

            @Override
            public void onRequestCustomBridges() {
                if (displayTorNetworkSettingsHandler != null)
                    displayTorNetworkSettingsHandler.accept(true);
            }
        });

        return p2pNetworkInitialized;
    }

    public void setSplashP2PNetworkAnimationVisible(boolean value) {
        splashP2PNetworkAnimationVisible.set(value);
    }

    private void updateNetworkStatusIndicator() {
        if (p2PService.getNetworkNode().getInboundConnectionCount() > 0) {
            p2PNetworkStatusIconId.set("image-green_circle");
        } else if (p2PService.getNetworkNode().getOutboundConnectionCount() > 0) {
            p2PNetworkStatusIconId.set("image-yellow_circle");
        } else {
            p2PNetworkStatusIconId.set("image-alert-round");
        }
    }
}
