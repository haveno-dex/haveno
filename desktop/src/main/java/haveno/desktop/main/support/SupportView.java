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

package haveno.desktop.main.support;

import com.google.inject.Inject;

import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.mediation.mediator.Mediator;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgent;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.trade.Trade;
import haveno.desktop.Navigation;
import haveno.desktop.util.Accessibility;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.common.view.ViewPath;
import haveno.desktop.main.MainView;
import haveno.desktop.main.offer.signedoffer.SignedOfferView;
import haveno.desktop.main.overlays.windows.SupportInfoWindow;
import haveno.desktop.main.support.dispute.DisputeView;
import haveno.desktop.main.support.dispute.agent.arbitration.ArbitratorView;
import haveno.desktop.main.support.dispute.agent.mediation.MediatorView;
import haveno.desktop.main.support.dispute.agent.refund.RefundAgentView;
import haveno.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import haveno.desktop.main.support.dispute.client.mediation.MediationClientView;
import haveno.desktop.main.support.dispute.client.refund.RefundClientView;
import haveno.network.p2p.NodeAddress;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableNumberValue;
import javafx.collections.MapChangeListener;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javax.annotation.Nullable;

@FxmlView
public class SupportView extends ActivatableView<VBox, Void> {

    @FXML
    TabPane tabPane;
    @FXML
    StackPane headerControls, content;
    @FXML
    Region navigationSpacer;
    @FXML
    Separator headerSeparator;

    private Tab tradersMediationDisputesTab, tradersRefundDisputesTab;
    @Nullable
    private Tab tradersArbitrationDisputesTab;
    private Tab mediatorTab, refundAgentTab;
    @Nullable
    private Tab arbitratorTab;
    @Nullable
    private Tab signedOfferTab;
    private final Navigation navigation;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final RefundManager refundManager;

    private final KeyRing keyRing;

    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;
    private Tab currentTab;
    private final ViewLoader viewLoader;
    private MapChangeListener<NodeAddress, Arbitrator> arbitratorMapChangeListener;
    private MapChangeListener<NodeAddress, Mediator> mediatorMapChangeListener;
    private MapChangeListener<NodeAddress, RefundAgent> refundAgentMapChangeListener;

    @Inject
    public SupportView(CachingViewLoader viewLoader,
                       Navigation navigation,
                       ArbitratorManager arbitratorManager,
                       MediatorManager mediatorManager,
                       RefundAgentManager refundAgentManager,
                       ArbitrationManager arbitrationManager,
                       MediationManager mediationManager,
                       RefundManager refundManager,
                       KeyRing keyRing) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.refundManager = refundManager;
        this.keyRing = keyRing;
    }

    @Override
    public void initialize() {
        Accessibility.fixTabs(tabPane);
        tradersMediationDisputesTab = new Tab();
        tradersMediationDisputesTab.setClosable(false);
        //tabPane.getTabs().add(tradersMediationDisputesTab); // hidden since mediation and refunds are not used in haveno

        tradersRefundDisputesTab = new Tab();
        tradersRefundDisputesTab.setClosable(false);
        //tabPane.getTabs().add(tradersRefundDisputesTab);

        tradersArbitrationDisputesTab = new Tab();
        tradersArbitrationDisputesTab.setClosable(false);
        tabPane.getTabs().add(tradersArbitrationDisputesTab);

        // Has to be called before loadView
        updateAgentTabs();

        tradersMediationDisputesTab.setText(Res.get("support.tab.mediation.support"));
        tradersRefundDisputesTab.setText(Res.get("support.tab.refund.support"));
        tradersArbitrationDisputesTab.setText(Res.get("support.tab.arbitration.support"));

        // preserve the tab content's clipping when a view exceeds the available space
        Rectangle contentClip = new Rectangle();
        contentClip.widthProperty().bind(content.widthProperty());
        contentClip.heightProperty().bind(content.heightProperty());
        content.setClip(contentClip);
        // retain tab shortcuts from controls hosted outside the tab pane
        EventHandler<KeyEvent> tabNavigationHandler = event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown() &&
                    (event.getCode() == KeyCode.TAB || (!event.isShiftDown() &&
                            (event.getCode() == KeyCode.PAGE_UP || event.getCode() == KeyCode.PAGE_DOWN)))) {
                tabPane.fireEvent(event.copyFor(tabPane, tabPane));
                event.consume();
            }
        };
        content.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.setVisible(false);
        headerControls.managedProperty().bind(headerControls.visibleProperty());
        headerSeparator.visibleProperty().bind(headerControls.visibleProperty());
        headerSeparator.managedProperty().bind(headerControls.visibleProperty());

        navigationListener = this::onNavigationRequested;

        tabChangeListener = (ov, oldValue, newValue) -> {
            if (newValue == currentTab) return;
            if (newValue == tradersArbitrationDisputesTab)
                navigation.navigateTo(MainView.class, SupportView.class, ArbitrationClientView.class);
            else if (newValue == tradersMediationDisputesTab)
                navigation.navigateTo(MainView.class, SupportView.class, MediationClientView.class);
            else if (newValue == tradersRefundDisputesTab)
                navigation.navigateTo(MainView.class, SupportView.class, RefundClientView.class);
            else if (newValue == arbitratorTab)
                navigation.navigateTo(MainView.class, SupportView.class, ArbitratorView.class);
            else if (newValue == signedOfferTab)
                navigation.navigateTo(MainView.class, SupportView.class, SignedOfferView.class);
            else if (newValue == mediatorTab)
                navigation.navigateTo(MainView.class, SupportView.class, MediatorView.class);
            else if (newValue == refundAgentTab)
                navigation.navigateTo(MainView.class, SupportView.class, RefundAgentView.class);
        };

        arbitratorMapChangeListener = change -> updateAgentTabs();
        mediatorMapChangeListener = change -> updateAgentTabs();
        refundAgentMapChangeListener = change -> updateAgentTabs();
    }

    public void reserveNavigationWidth(ObservableNumberValue width) {
        navigationSpacer.prefWidthProperty().bind(width);
    }

    private void updateAgentTabs() {
        PubKeyRing myPubKeyRing = keyRing.getPubKeyRing();

        boolean isActiveArbitrator = arbitratorManager.getObservableMap().values().stream()
                .anyMatch(e -> e.getPubKeyRing() != null && e.getPubKeyRing().equals(myPubKeyRing));

        // In case a arbitrator has become inactive he still might get disputes from pending trades
        boolean hasDisputesAsArbitrator = arbitrationManager.getDisputesAsObservableList().stream()
                .anyMatch(d -> d.getAgentPubKeyRing().equals(myPubKeyRing));

        if (isActiveArbitrator || hasDisputesAsArbitrator) {
            if (arbitratorTab == null) {
                arbitratorTab = new Tab();
                arbitratorTab.setClosable(false);
                tabPane.getTabs().add(arbitratorTab);
            }
            if (signedOfferTab == null) {
                signedOfferTab = new Tab();
                signedOfferTab.setClosable(false);
                tabPane.getTabs().add(signedOfferTab);
            }
        }

        boolean isActiveMediator = mediatorManager.getObservableMap().values().stream()
                .anyMatch(e -> e.getPubKeyRing() != null && e.getPubKeyRing().equals(myPubKeyRing));
        if (mediatorTab == null) {
            // In case a mediator has become inactive he still might get disputes from pending trades
            boolean hasDisputesAsMediator = mediationManager.getDisputesAsObservableList().stream()
                    .anyMatch(d -> d.getAgentPubKeyRing().equals(myPubKeyRing));
            if (isActiveMediator || hasDisputesAsMediator) {
                mediatorTab = new Tab();
                mediatorTab.setClosable(false);
                tabPane.getTabs().add(mediatorTab);
            }
        }

        boolean isActiveRefundAgent = refundAgentManager.getObservableMap().values().stream()
                .anyMatch(e -> e.getPubKeyRing() != null && e.getPubKeyRing().equals(myPubKeyRing));
        if (refundAgentTab == null) {
            // In case a refundAgent has become inactive he still might get disputes from pending trades
            boolean hasDisputesAsRefundAgent = refundManager.getDisputesAsObservableList().stream()
                    .anyMatch(d -> d.getAgentPubKeyRing().equals(myPubKeyRing));
            if (isActiveRefundAgent || hasDisputesAsRefundAgent) {
                refundAgentTab = new Tab();
                refundAgentTab.setClosable(false);
                tabPane.getTabs().add(refundAgentTab);
            }
        }

        // We might get that method called before we have the map is filled in the arbitratorManager
        if (arbitratorTab != null) {
            arbitratorTab.setText(Res.get("support.tab.ArbitratorsSupportTickets", Res.get("shared.arbitrator")));
        }
        if (signedOfferTab != null) {
            signedOfferTab.setText(Res.get("support.tab.SignedOffers"));
        }
        if (mediatorTab != null) {
            mediatorTab.setText(Res.get("support.tab.ArbitratorsSupportTickets", Res.get("shared.mediator")));
        }
        if (refundAgentTab != null) {
            refundAgentTab.setText(Res.get("support.tab.ArbitratorsSupportTickets", Res.get("shared.refundAgentForSupportStaff")));
        }
    }

    @Override
    protected void activate() {
        arbitratorManager.updateMap();
        arbitratorManager.getObservableMap().addListener(arbitratorMapChangeListener);

        mediatorManager.updateMap();
        mediatorManager.getObservableMap().addListener(mediatorMapChangeListener);

        refundAgentManager.updateMap();
        refundAgentManager.getObservableMap().addListener(refundAgentMapChangeListener);

        updateAgentTabs();

        tabPane.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        navigation.addListener(navigationListener);

        if (tabPane.getSelectionModel().getSelectedItem() == tradersMediationDisputesTab) {
            navigation.navigateTo(MainView.class, SupportView.class, MediationClientView.class);
        } else if (tabPane.getSelectionModel().getSelectedItem() == tradersArbitrationDisputesTab) {
            navigation.navigateTo(MainView.class, SupportView.class, ArbitrationClientView.class);
        } else if (tabPane.getSelectionModel().getSelectedItem() == tradersRefundDisputesTab) {
            navigation.navigateTo(MainView.class, SupportView.class, RefundClientView.class);
        } else if (arbitratorTab != null) {
            navigation.navigateTo(MainView.class, SupportView.class, ArbitratorView.class);
        } else if (signedOfferTab != null) {
            navigation.navigateTo(MainView.class, SupportView.class, SignedOfferView.class);
        } else if (mediatorTab != null) {
            navigation.navigateTo(MainView.class, SupportView.class, MediatorView.class);
        } else if (refundAgentTab != null) {
            navigation.navigateTo(MainView.class, SupportView.class, RefundAgentView.class);
        }

        String key = "supportInfo";
        if (!DevEnv.isDevMode()) {
            new SupportInfoWindow()
                    .dontShowAgainId(key)
                    .show();
        }
    }

    @Override
    protected void deactivate() {
        arbitratorManager.getObservableMap().removeListener(arbitratorMapChangeListener);
        mediatorManager.getObservableMap().removeListener(mediatorMapChangeListener);
        refundAgentManager.getObservableMap().removeListener(refundAgentMapChangeListener);
        tabPane.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        navigation.removeListener(navigationListener);
        if (currentTab != null) content.getChildren().clear();
        currentTab = null;
    }

    private void onNavigationRequested(ViewPath viewPath, @Nullable Object data) {
        if (viewPath.size() == 3 && viewPath.indexOf(SupportView.class) == 1) {
            Runnable load = () -> {
                if (root.getScene() != null && viewPath.equals(navigation.getCurrentPath()))
                    loadView(viewPath.tip(), data);
            };
            // attach the content during activation so an empty support page cannot render first
            if (Platform.isFxApplicationThread()) load.run();
            else UserThread.execute(load);
        }
    }

    private void loadView(Class<? extends View> viewClass, @Nullable Object data) {
        // we want to get activate/deactivate called, so we remove the old view on tab change
        if (currentTab != null)
            content.getChildren().clear();

        View view = viewLoader.load(viewClass);

        if (view instanceof MediationClientView) {
            currentTab = tradersMediationDisputesTab;
        } else if (view instanceof ArbitrationClientView) {
            currentTab = tradersArbitrationDisputesTab;
        } else if (view instanceof RefundClientView) {
            currentTab = tradersRefundDisputesTab;
        } else if (view instanceof ArbitratorView) {
            currentTab = arbitratorTab;
        } else if (view instanceof SignedOfferView) {
            currentTab = signedOfferTab;
        } else if (view instanceof MediatorView) {
            currentTab = mediatorTab;
        } else if (view instanceof RefundAgentView) {
            currentTab = refundAgentTab;
        } else {
            currentTab = null;
        }

        if (currentTab != null) {
            // set the target before attaching the view triggers activation
            if (view instanceof DisputeView disputeView) {
                if (data instanceof Dispute dispute) disputeView.setDisputeToSelect(dispute);
                else disputeView.setTradeIdToSelect(data instanceof Trade ? ((Trade) data).getId() : null);
            }
            Region controls = view instanceof DisputeView disputeView ? disputeView.getHeaderControls() : null;
            if (controls == null) headerControls.getChildren().clear();
            else if (!headerControls.getChildren().contains(controls)) headerControls.getChildren().setAll(controls);
            headerControls.setVisible(controls != null);

            content.getChildren().setAll(view.getRoot());
            tabPane.getSelectionModel().select(currentTab);
        }
    }
}
