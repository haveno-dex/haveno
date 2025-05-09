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

import com.google.inject.Inject;
import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrLocalNode;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.validation.RegexValidator;
import haveno.core.util.validation.RegexValidatorFactory;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.Statistic;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static javafx.beans.binding.Bindings.createStringBinding;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@FxmlView
public class NetworkSettingsView extends ActivatableView<GridPane, Void> {

    @FXML
    TitledGroupBg p2pHeader, btcHeader;
    @FXML
    Label useTorForXmrLabel, xmrNodesLabel, moneroNodesLabel, localhostXmrNodeInfoLabel;
    @FXML
    InputTextField xmrNodesInputTextField;
    @FXML
    TextField onionAddress, sentDataTextField, receivedDataTextField, chainHeightTextField, minVersionForTrading;
    @FXML
    Label p2PPeersLabel, moneroConnectionsLabel;
    @FXML
    RadioButton useTorForXmrAfterSyncRadio, useTorForXmrOffRadio, useTorForXmrOnRadio;
    @FXML
    RadioButton useProvidedNodesRadio, useCustomNodesRadio, usePublicNodesRadio;
    @FXML
    TableView<P2pNetworkListItem> p2pPeersTableView;
    @FXML
    TableView<MoneroNetworkListItem> moneroConnectionsTableView;
    @FXML
    TableColumn<P2pNetworkListItem, String> onionAddressColumn, connectionTypeColumn, creationDateColumn,
            roundTripTimeColumn, sentBytesColumn, receivedBytesColumn, peerTypeColumn;
    @FXML
    TableColumn<MoneroNetworkListItem, String> moneroConnectionAddressColumn, moneroConnectionConnectedColumn;
    @FXML
    Label rescanOutputsLabel;
    @FXML
    AutoTooltipButton rescanOutputsButton, openTorSettingsButton;

    private final Preferences preferences;
    private final XmrNodes xmrNodes;
    private final FilterManager filterManager;
    private final XmrLocalNode xmrLocalNode;
    private final TorNetworkSettingsWindow torNetworkSettingsWindow;
    private final ClockWatcher clockWatcher;
    private final WalletsSetup walletsSetup;
    private final P2PService p2PService;
    private final XmrConnectionService connectionService;

    private final ObservableList<P2pNetworkListItem> p2pNetworkListItems = FXCollections.observableArrayList();
    private final SortedList<P2pNetworkListItem> p2pSortedList = new SortedList<>(p2pNetworkListItems);

    private final ObservableList<MoneroNetworkListItem> moneroNetworkListItems = FXCollections.observableArrayList();
    private final SortedList<MoneroNetworkListItem> moneroSortedList = new SortedList<>(moneroNetworkListItems);

    private Subscription numP2PPeersSubscription;
    private Subscription moneroConnectionsSubscription;
    private Subscription moneroBlockHeightSubscription;
    private Subscription nodeAddressSubscription;
    private ChangeListener<Boolean> xmrNodesInputTextFieldFocusListener;
    private ToggleGroup useTorForXmrToggleGroup;
    private ToggleGroup moneroPeersToggleGroup;
    private Preferences.UseTorForXmr selectedUseTorForXmr;
    private XmrNodes.MoneroNodesOption selectedMoneroNodesOption;
    private ChangeListener<Toggle> useTorForXmrToggleGroupListener;
    private ChangeListener<Toggle> moneroPeersToggleGroupListener;
    private ChangeListener<Filter> filterPropertyListener;

    @Inject
    public NetworkSettingsView(WalletsSetup walletsSetup,
                               P2PService p2PService,
                               XmrConnectionService connectionService,
                               Preferences preferences,
                               XmrNodes xmrNodes,
                               FilterManager filterManager,
                               XmrLocalNode xmrLocalNode,
                               TorNetworkSettingsWindow torNetworkSettingsWindow,
                               ClockWatcher clockWatcher) {
        super();
        this.walletsSetup = walletsSetup;
        this.p2PService = p2PService;
        this.connectionService = connectionService;
        this.preferences = preferences;
        this.xmrNodes = xmrNodes;
        this.filterManager = filterManager;
        this.xmrLocalNode = xmrLocalNode;
        this.torNetworkSettingsWindow = torNetworkSettingsWindow;
        this.clockWatcher = clockWatcher;
    }

    @Override
    public void initialize() {
        GUIUtil.applyTableStyle(p2pPeersTableView);
        GUIUtil.applyTableStyle(moneroConnectionsTableView);

        onionAddress.getStyleClass().add("label-float");
        sentDataTextField.getStyleClass().add("label-float");
        receivedDataTextField.getStyleClass().add("label-float");
        chainHeightTextField.getStyleClass().add("label-float");
        minVersionForTrading.getStyleClass().add("label-float");

        btcHeader.setText(Res.get("settings.net.xmrHeader"));
        p2pHeader.setText(Res.get("settings.net.p2pHeader"));
        onionAddress.setPromptText(Res.get("settings.net.onionAddressLabel"));
        xmrNodesLabel.setText(Res.get("settings.net.xmrNodesLabel"));
        moneroConnectionsLabel.setText(Res.get("settings.net.moneroPeersLabel"));
        useTorForXmrLabel.setText(Res.get("settings.net.useTorForXmrJLabel"));
        useTorForXmrAfterSyncRadio.setText(Res.get("settings.net.useTorForXmrAfterSyncRadio"));
        useTorForXmrOffRadio.setText(Res.get("settings.net.useTorForXmrOffRadio"));
        useTorForXmrOnRadio.setText(Res.get("settings.net.useTorForXmrOnRadio"));
        moneroNodesLabel.setText(Res.get("settings.net.moneroNodesLabel"));
        moneroConnectionAddressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        moneroConnectionConnectedColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.connection")));
        localhostXmrNodeInfoLabel.setText(Res.get("settings.net.localhostXmrNodeInfo"));
        useProvidedNodesRadio.setText(Res.get("settings.net.useProvidedNodesRadio"));
        useCustomNodesRadio.setText(Res.get("settings.net.useCustomNodesRadio"));
        usePublicNodesRadio.setText(Res.get("settings.net.usePublicNodesRadio"));
        rescanOutputsLabel.setText(Res.get("settings.net.rescanOutputsLabel"));
        rescanOutputsButton.updateText(Res.get("settings.net.rescanOutputsButton"));
        p2PPeersLabel.setText(Res.get("settings.net.p2PPeersLabel"));
        onionAddressColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.onionAddressColumn")));
        creationDateColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.creationDateColumn")));
        connectionTypeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.connectionTypeColumn")));
        sentDataTextField.setPromptText(Res.get("settings.net.sentDataLabel"));
        receivedDataTextField.setPromptText(Res.get("settings.net.receivedDataLabel"));
        chainHeightTextField.setPromptText(Res.get("settings.net.chainHeightLabel"));
        minVersionForTrading.setPromptText(Res.get("filterWindow.disableTradeBelowVersion"));
        roundTripTimeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.roundTripTimeColumn")));
        sentBytesColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.sentBytesColumn")));
        receivedBytesColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.receivedBytesColumn")));
        peerTypeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.peerTypeColumn")));
        openTorSettingsButton.updateText(Res.get("settings.net.openTorSettingsButton"));

        // TODO: hiding button to rescan outputs until supported
        rescanOutputsLabel.setVisible(false);
        rescanOutputsButton.setVisible(false);

        GridPane.setMargin(moneroConnectionsLabel, new Insets(4, 0, 0, 0));
        GridPane.setValignment(moneroConnectionsLabel, VPos.TOP);

        GridPane.setMargin(p2PPeersLabel, new Insets(4, 0, 0, 0));
        GridPane.setValignment(p2PPeersLabel, VPos.TOP);

        moneroConnectionAddressColumn.setSortType(TableColumn.SortType.ASCENDING);
        moneroConnectionConnectedColumn.setSortType(TableColumn.SortType.DESCENDING);
        moneroConnectionsTableView.setMinHeight(180);
        moneroConnectionsTableView.setPrefHeight(180);
        moneroConnectionsTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        moneroConnectionsTableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noData")));
        moneroConnectionsTableView.getSortOrder().add(moneroConnectionConnectedColumn);

        p2pPeersTableView.setMinHeight(180);
        p2pPeersTableView.setPrefHeight(180);
        p2pPeersTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p2pPeersTableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noData")));
        p2pPeersTableView.getSortOrder().add(creationDateColumn);
        creationDateColumn.setSortType(TableColumn.SortType.ASCENDING);

        // use tor for xmr radio buttons

        useTorForXmrToggleGroup = new ToggleGroup();
        useTorForXmrAfterSyncRadio.setToggleGroup(useTorForXmrToggleGroup);
        useTorForXmrOffRadio.setToggleGroup(useTorForXmrToggleGroup);
        useTorForXmrOnRadio.setToggleGroup(useTorForXmrToggleGroup);

        useTorForXmrAfterSyncRadio.setUserData(Preferences.UseTorForXmr.AFTER_SYNC);
        useTorForXmrOffRadio.setUserData(Preferences.UseTorForXmr.OFF);
        useTorForXmrOnRadio.setUserData(Preferences.UseTorForXmr.ON);

        selectedUseTorForXmr = Preferences.UseTorForXmr.values()[preferences.getUseTorForXmrOrdinal()];

        selectUseTorForXmrToggle();
        onUseTorForXmrToggleSelected(false);

        useTorForXmrToggleGroupListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                selectedUseTorForXmr = (Preferences.UseTorForXmr) newValue.getUserData();
                onUseTorForXmrToggleSelected(true);
            }
        };

        // monero nodes radio buttons

        moneroPeersToggleGroup = new ToggleGroup();
        useProvidedNodesRadio.setToggleGroup(moneroPeersToggleGroup);
        useCustomNodesRadio.setToggleGroup(moneroPeersToggleGroup);
        usePublicNodesRadio.setToggleGroup(moneroPeersToggleGroup);

        useProvidedNodesRadio.setUserData(XmrNodes.MoneroNodesOption.PROVIDED);
        useCustomNodesRadio.setUserData(XmrNodes.MoneroNodesOption.CUSTOM);
        usePublicNodesRadio.setUserData(XmrNodes.MoneroNodesOption.PUBLIC);

        selectedMoneroNodesOption = XmrNodes.MoneroNodesOption.values()[preferences.getMoneroNodesOptionOrdinal()];
        // In case CUSTOM is selected but no custom nodes are set or
        // in case PUBLIC is selected but we blocked it (B2X risk) we revert to provided nodes
        if ((selectedMoneroNodesOption == XmrNodes.MoneroNodesOption.CUSTOM &&
                (preferences.getMoneroNodes() == null || preferences.getMoneroNodes().isEmpty())) ||
                (selectedMoneroNodesOption == XmrNodes.MoneroNodesOption.PUBLIC && isPreventPublicXmrNetwork())) {
            selectedMoneroNodesOption = XmrNodes.MoneroNodesOption.PROVIDED;
            preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
        }

        selectMoneroPeersToggle();
        onMoneroPeersToggleSelected(false);

        moneroPeersToggleGroupListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                selectedMoneroNodesOption = (XmrNodes.MoneroNodesOption) newValue.getUserData();
                onMoneroPeersToggleSelected(true);
            }
        };

        xmrNodesInputTextField.setPromptText(Res.get("settings.net.ips", "" + HavenoUtils.getDefaultMoneroPort()));
        RegexValidator regexValidator = RegexValidatorFactory.addressRegexValidator();
        xmrNodesInputTextField.setValidator(regexValidator);
        xmrNodesInputTextField.setErrorMessage(Res.get("validation.invalidAddressList"));
        xmrNodesInputTextFieldFocusListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue
                    && !xmrNodesInputTextField.getText().equals(preferences.getMoneroNodes())
                    && xmrNodesInputTextField.validate()) {
                preferences.setMoneroNodes(xmrNodesInputTextField.getText());
                preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                showShutDownPopup();
            }
        };
        filterPropertyListener = (observable, oldValue, newValue) -> applyFilter();

        // disable radio buttons if no nodes available
        if (xmrNodes.getProvidedXmrNodes().isEmpty()) {
            useProvidedNodesRadio.setDisable(true);
        }
        usePublicNodesRadio.setDisable(isPublicNodesDisabled());

        //TODO sorting needs other NetworkStatisticListItem as columns type
       /* creationDateColumn.setComparator((o1, o2) ->
                o1.statistic.getCreationDate().compareTo(o2.statistic.getCreationDate()));
        sentBytesColumn.setComparator((o1, o2) ->
                ((Integer) o1.statistic.getSentBytes()).compareTo(((Integer) o2.statistic.getSentBytes())));
        receivedBytesColumn.setComparator((o1, o2) ->
                ((Integer) o1.statistic.getReceivedBytes()).compareTo(((Integer) o2.statistic.getReceivedBytes())));*/
    }

    @Override
    public void activate() {
        useTorForXmrToggleGroup.selectedToggleProperty().addListener(useTorForXmrToggleGroupListener);
        moneroPeersToggleGroup.selectedToggleProperty().addListener(moneroPeersToggleGroupListener);

        if (filterManager.getFilter() != null)
            applyFilter();

        filterManager.filterProperty().addListener(filterPropertyListener);

        rescanOutputsButton.setOnAction(event -> GUIUtil.rescanOutputs(preferences));

        moneroConnectionsSubscription = EasyBind.subscribe(connectionService.connectionsProperty(),
                connections -> updateMoneroConnectionsTable());

        moneroBlockHeightSubscription = EasyBind.subscribe(connectionService.chainHeightProperty(),
                height -> updateMoneroConnectionsTable());

        nodeAddressSubscription = EasyBind.subscribe(p2PService.getNetworkNode().nodeAddressProperty(),
                nodeAddress -> onionAddress.setText(nodeAddress == null ?
                        Res.get("settings.net.notKnownYet") :
                        nodeAddress.getFullAddress()));
        numP2PPeersSubscription = EasyBind.subscribe(p2PService.getNumConnectedPeers(), numPeers -> updateP2PTable());

        sentDataTextField.textProperty().bind(createStringBinding(() -> Res.get("settings.net.sentData",
                FormattingUtils.formatBytes(Statistic.totalSentBytesProperty().get()),
                Statistic.numTotalSentMessagesProperty().get(),
                Statistic.numTotalSentMessagesPerSecProperty().get()),
                Statistic.numTotalSentMessagesPerSecProperty()));

        receivedDataTextField.textProperty().bind(createStringBinding(() -> Res.get("settings.net.receivedData",
                FormattingUtils.formatBytes(Statistic.totalReceivedBytesProperty().get()),
                Statistic.numTotalReceivedMessagesProperty().get(),
                Statistic.numTotalReceivedMessagesPerSecProperty().get()),
                Statistic.numTotalReceivedMessagesPerSecProperty()));

        moneroSortedList.comparatorProperty().bind(moneroConnectionsTableView.comparatorProperty());
        moneroConnectionsTableView.setItems(moneroSortedList);

        p2pSortedList.comparatorProperty().bind(p2pPeersTableView.comparatorProperty());
        p2pPeersTableView.setItems(p2pSortedList);

        xmrNodesInputTextField.setText(preferences.getMoneroNodes());

        xmrNodesInputTextField.focusedProperty().addListener(xmrNodesInputTextFieldFocusListener);

        openTorSettingsButton.setOnAction(e -> torNetworkSettingsWindow.show());
    }

    @Override
    public void deactivate() {
        useTorForXmrToggleGroup.selectedToggleProperty().removeListener(useTorForXmrToggleGroupListener);
        moneroPeersToggleGroup.selectedToggleProperty().removeListener(moneroPeersToggleGroupListener);
        filterManager.filterProperty().removeListener(filterPropertyListener);

        if (nodeAddressSubscription != null)
            nodeAddressSubscription.unsubscribe();

        if (moneroConnectionsSubscription != null)
            moneroConnectionsSubscription.unsubscribe();

        if (moneroBlockHeightSubscription != null)
            moneroBlockHeightSubscription.unsubscribe();

        if (numP2PPeersSubscription != null)
            numP2PPeersSubscription.unsubscribe();

        sentDataTextField.textProperty().unbind();
        receivedDataTextField.textProperty().unbind();

        moneroSortedList.comparatorProperty().unbind();
        p2pSortedList.comparatorProperty().unbind();
        p2pPeersTableView.getItems().forEach(P2pNetworkListItem::cleanup);
        xmrNodesInputTextField.focusedProperty().removeListener(xmrNodesInputTextFieldFocusListener);

        openTorSettingsButton.setOnAction(null);
    }

    private boolean isPreventPublicXmrNetwork() {
       return filterManager.getFilter() != null &&
               filterManager.getFilter().isPreventPublicXmrNetwork();
    }

    private void selectUseTorForXmrToggle() {
        switch (selectedUseTorForXmr) {
            case OFF:
                useTorForXmrToggleGroup.selectToggle(useTorForXmrOffRadio);
                break;
            case ON:
                useTorForXmrToggleGroup.selectToggle(useTorForXmrOnRadio);
                break;
            default:
            case AFTER_SYNC:
                useTorForXmrToggleGroup.selectToggle(useTorForXmrAfterSyncRadio);
                break;
        }
    }

    private void selectMoneroPeersToggle() {
        switch (selectedMoneroNodesOption) {
            case CUSTOM:
                moneroPeersToggleGroup.selectToggle(useCustomNodesRadio);
                break;
            case PUBLIC:
                moneroPeersToggleGroup.selectToggle(usePublicNodesRadio);
                break;
            default:
            case PROVIDED:
                moneroPeersToggleGroup.selectToggle(useProvidedNodesRadio);
                break;
        }
    }

    private void showShutDownPopup() {
        new Popup()
                .information(Res.get("settings.net.needRestart"))
                .closeButtonText(Res.get("shared.cancel"))
                .useShutDownButton()
                .show();
    }

    private void onUseTorForXmrToggleSelected(boolean calledFromUser) {
        Preferences.UseTorForXmr currentUseTorForXmr = Preferences.UseTorForXmr.values()[preferences.getUseTorForXmrOrdinal()];
        if (currentUseTorForXmr != selectedUseTorForXmr) {
            if (calledFromUser) {
                new Popup().information(Res.get("settings.net.needRestart"))
                    .actionButtonText(Res.get("shared.applyAndShutDown"))
                    .onAction(() -> {
                        preferences.setUseTorForXmrOrdinal(selectedUseTorForXmr.ordinal());
                        UserThread.runAfter(HavenoApp.getShutDownHandler(), 500, TimeUnit.MILLISECONDS);
                    })
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(() -> {
                        selectedUseTorForXmr = currentUseTorForXmr;
                        selectUseTorForXmrToggle();
                    })
                    .show();
            }
        }
    }

    private void onMoneroPeersToggleSelected(boolean calledFromUser) {
        usePublicNodesRadio.setDisable(isPublicNodesDisabled());

        XmrNodes.MoneroNodesOption currentMoneroNodesOption = XmrNodes.MoneroNodesOption.values()[preferences.getMoneroNodesOptionOrdinal()];

        switch (selectedMoneroNodesOption) {
            case CUSTOM:
                xmrNodesInputTextField.setDisable(false);
                xmrNodesLabel.setDisable(false);
                if (!xmrNodesInputTextField.getText().isEmpty()
                        && xmrNodesInputTextField.validate()
                        && currentMoneroNodesOption != XmrNodes.MoneroNodesOption.CUSTOM) {
                    preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                    if (calledFromUser) {
                        if (isPreventPublicXmrNetwork()) {
                            new Popup().warning(Res.get("settings.net.warn.useCustomNodes.B2XWarning"))
                                    .onAction(() -> UserThread.runAfter(this::showShutDownPopup, 300, TimeUnit.MILLISECONDS)).show();
                        } else {
                            showShutDownPopup();
                        }
                    }
                }
                break;
            case PUBLIC:
                xmrNodesInputTextField.setDisable(true);
                xmrNodesLabel.setDisable(true);
                if (currentMoneroNodesOption != XmrNodes.MoneroNodesOption.PUBLIC) {
                    preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                    if (calledFromUser) {
                        new Popup()
                                .warning(Res.get("settings.net.warn.usePublicNodes"))
                                .actionButtonText(Res.get("settings.net.warn.usePublicNodes.useProvided"))
                                .onAction(() -> UserThread.runAfter(() -> {
                                    selectedMoneroNodesOption = XmrNodes.MoneroNodesOption.PROVIDED;
                                    preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                                    selectMoneroPeersToggle();
                                    onMoneroPeersToggleSelected(false);
                                }, 300, TimeUnit.MILLISECONDS))
                                .closeButtonText(Res.get("settings.net.warn.usePublicNodes.usePublic"))
                                .onClose(() -> UserThread.runAfter(this::showShutDownPopup, 300, TimeUnit.MILLISECONDS))
                                .show();
                    }
                }
                break;
            default:
            case PROVIDED:
                xmrNodesInputTextField.setDisable(true);
                xmrNodesLabel.setDisable(true);
                if (currentMoneroNodesOption != XmrNodes.MoneroNodesOption.PROVIDED) {
                    preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                    if (calledFromUser) {
                        showShutDownPopup();
                    }
                }
                break;
        }
    }


    private void applyFilter() {

        // prevent public xmr network
        final boolean preventPublicXmrNetwork = isPreventPublicXmrNetwork();
        usePublicNodesRadio.setDisable(isPublicNodesDisabled());
        if (preventPublicXmrNetwork && selectedMoneroNodesOption == XmrNodes.MoneroNodesOption.PUBLIC) {
            selectedMoneroNodesOption = XmrNodes.MoneroNodesOption.PROVIDED;
            preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
            selectMoneroPeersToggle();
            onMoneroPeersToggleSelected(false);
        }

        // set min version for trading
        String minVersion = filterManager.getDisableTradeBelowVersion();
        minVersionForTrading.textProperty().setValue(minVersion == null ? Res.get("shared.none") : minVersion);
    }

    private boolean isPublicNodesDisabled() {
        return xmrNodes.getPublicXmrNodes().isEmpty() || isPreventPublicXmrNetwork();
    }

    private void updateP2PTable() {
        UserThread.execute(() -> {
            if (connectionService.isShutDownStarted()) return; // ignore if shutting down
            p2pPeersTableView.getItems().forEach(P2pNetworkListItem::cleanup);
            p2pNetworkListItems.clear();
            p2pNetworkListItems.setAll(p2PService.getNetworkNode().getAllConnections().stream()
                    .map(connection -> new P2pNetworkListItem(connection, clockWatcher))
                    .collect(Collectors.toList()));
        });
    }

    private void updateMoneroConnectionsTable() {
        UserThread.execute(() -> {
            if (connectionService.isShutDownStarted()) return; // ignore if shutting down
            moneroNetworkListItems.clear();
            moneroNetworkListItems.setAll(connectionService.getConnections().stream()
                    .map(connection -> new MoneroNetworkListItem(connection, connection == connectionService.getConnection() && Boolean.TRUE.equals(connectionService.isConnected())))
                    .collect(Collectors.toList()));
            updateChainHeightTextField(connectionService.chainHeightProperty().get());
        });
    }

    private void updateChainHeightTextField(Number chainHeight) {
        chainHeightTextField.textProperty().setValue(Res.get("settings.net.chainHeight", chainHeight));
    }
}

