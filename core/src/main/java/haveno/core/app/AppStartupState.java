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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.core.api.CoreMoneroConnectionsService;
import haveno.core.api.CoreNotificationService;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

/**
 * We often need to wait until network and wallet is ready or other combination of startup states.
 * To avoid those repeated checks for the state or setting of listeners on different domains we provide here a
 * collection of useful states.
 */
@Slf4j
@Singleton
public class AppStartupState {
    // Do not convert to local field as there have been issues observed that the object got GC'ed.
    private final MonadicBinding<Boolean> p2pNetworkAndWalletInitialized;

    private final BooleanProperty walletAndNetworkReady = new SimpleBooleanProperty();
    private final BooleanProperty allDomainServicesInitialized = new SimpleBooleanProperty();
    private final BooleanProperty applicationFullyInitialized = new SimpleBooleanProperty();
    private final BooleanProperty updatedDataReceived = new SimpleBooleanProperty();
    private final BooleanProperty isBlockDownloadComplete = new SimpleBooleanProperty();
    private final BooleanProperty hasSufficientPeersForBroadcast = new SimpleBooleanProperty();

    @Inject
    public AppStartupState(CoreNotificationService notificationService,
                           CoreMoneroConnectionsService connectionsService,
                           P2PService p2PService) {

        p2PService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onUpdatedDataReceived() {
                updatedDataReceived.set(true);
            }
        });

        connectionsService.downloadPercentageProperty().addListener((observable, oldValue, newValue) -> {
            if (connectionsService.isDownloadComplete())
                isBlockDownloadComplete.set(true);
        });

        connectionsService.numPeersProperty().addListener((observable, oldValue, newValue) -> {
            if (connectionsService.hasSufficientPeersForBroadcast())
                hasSufficientPeersForBroadcast.set(true);
        });

        p2pNetworkAndWalletInitialized = EasyBind.combine(updatedDataReceived,
                isBlockDownloadComplete,
                hasSufficientPeersForBroadcast, // TODO: consider sufficient number of peers?
                allDomainServicesInitialized,
                (a, b, c, d) -> {
                    log.info("Combined initialized state = {} = updatedDataReceived={} && isBlockDownloadComplete={} && hasSufficientPeersForBroadcast={} && allDomainServicesInitialized={}", (a && b && c && d), updatedDataReceived.get(), isBlockDownloadComplete.get(), hasSufficientPeersForBroadcast.get(), allDomainServicesInitialized.get());
                    if (a && b) {
                        walletAndNetworkReady.set(true);
                    }
                    return a && d; // app fully initialized before daemon connection and wallet by default // TODO: rename variable
                });
        p2pNetworkAndWalletInitialized.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                applicationFullyInitialized.set(true);
                notificationService.sendAppInitializedNotification();
                log.info("Application fully initialized");
            }
        });
    }

    public void onDomainServicesInitialized() {
        allDomainServicesInitialized.set(true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isWalletAndNetworkReady() {
        return walletAndNetworkReady.get();
    }

    public ReadOnlyBooleanProperty walletAndNetworkReadyProperty() {
        return walletAndNetworkReady;
    }

    public boolean isAllDomainServicesInitialized() {
        return allDomainServicesInitialized.get();
    }

    public ReadOnlyBooleanProperty allDomainServicesInitializedProperty() {
        return allDomainServicesInitialized;
    }

    public boolean isApplicationFullyInitialized() {
        return applicationFullyInitialized.get();
    }

    public ReadOnlyBooleanProperty applicationFullyInitializedProperty() {
        return applicationFullyInitialized;
    }

    public boolean isUpdatedDataReceived() {
        return updatedDataReceived.get();
    }

    public ReadOnlyBooleanProperty updatedDataReceivedProperty() {
        return updatedDataReceived;
    }

    public boolean isBlockDownloadComplete() {
        return isBlockDownloadComplete.get();
    }

    public ReadOnlyBooleanProperty isBlockDownloadCompleteProperty() {
        return isBlockDownloadComplete;
    }

    public boolean isHasSufficientPeersForBroadcast() {
        return hasSufficientPeersForBroadcast.get();
    }

    public ReadOnlyBooleanProperty hasSufficientPeersForBroadcastProperty() {
        return hasSufficientPeersForBroadcast;
    }

}
