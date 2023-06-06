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

package haveno.desktop.main.settings.network;

import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.core.api.CoreMoneroConnectionsService;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.validation.RegexValidator;
import haveno.core.util.validation.RegexValidatorFactory;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.LocalBitcoinNode;
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
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import monero.daemon.model.MoneroPeer;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static javafx.beans.binding.Bindings.createStringBinding;

@FxmlView
public class NetworkSettingsView extends ActivatableView<GridPane, Void> {

    @FXML
    TitledGroupBg p2pHeader, btcHeader;
    @FXML
    Label xmrNodesLabel, moneroNodesLabel, localhostXmrNodeInfoLabel;
    @FXML
    InputTextField xmrNodesInputTextField;
    @FXML
    TextField onionAddress, sentDataTextField, receivedDataTextField, chainHeightTextField;
    @FXML
    Label p2PPeersLabel, moneroPeersLabel;
    @FXML
    CheckBox useTorForXmrJCheckBox;
    @FXML
    RadioButton useProvidedNodesRadio, useCustomNodesRadio, usePublicNodesRadio;
    @FXML
    TableView<P2pNetworkListItem> p2pPeersTableView;
    @FXML
    TableView<MoneroNetworkListItem> moneroPeersTableView;
    @FXML
    TableColumn<P2pNetworkListItem, String> onionAddressColumn, connectionTypeColumn, creationDateColumn,
            roundTripTimeColumn, sentBytesColumn, receivedBytesColumn, peerTypeColumn;
    @FXML
    TableColumn<MoneroNetworkListItem, String> moneroPeerAddressColumn, moneroPeerVersionColumn,
            moneroPeerSubVersionColumn, moneroPeerHeightColumn;
    @FXML
    Label rescanOutputsLabel;
    @FXML
    AutoTooltipButton rescanOutputsButton, openTorSettingsButton;

    private final Preferences preferences;
    private final XmrNodes xmrNodes;
    private final FilterManager filterManager;
    private final LocalBitcoinNode localBitcoinNode;
    private final TorNetworkSettingsWindow torNetworkSettingsWindow;
    private final ClockWatcher clockWatcher;
    private final WalletsSetup walletsSetup;
    private final P2PService p2PService;
    private final CoreMoneroConnectionsService connectionManager;

    private final ObservableList<P2pNetworkListItem> p2pNetworkListItems = FXCollections.observableArrayList();
    private final SortedList<P2pNetworkListItem> p2pSortedList = new SortedList<>(p2pNetworkListItems);

    private final ObservableList<MoneroNetworkListItem> moneroNetworkListItems = FXCollections.observableArrayList();
    private final SortedList<MoneroNetworkListItem> moneroSortedList = new SortedList<>(moneroNetworkListItems);

    private Subscription numP2PPeersSubscription;
    private Subscription moneroPeersSubscription;
    private Subscription moneroBlockHeightSubscription;
    private Subscription nodeAddressSubscription;
    private ChangeListener<Boolean> xmrNodesInputTextFieldFocusListener;
    private ToggleGroup moneroPeersToggleGroup;
    private XmrNodes.MoneroNodesOption selectedMoneroNodesOption;
    private ChangeListener<Toggle> moneroPeersToggleGroupListener;
    private ChangeListener<Filter> filterPropertyListener;

    @Inject
    public NetworkSettingsView(WalletsSetup walletsSetup,
                               P2PService p2PService,
                               CoreMoneroConnectionsService connectionManager,
                               Preferences preferences,
                               XmrNodes xmrNodes,
                               FilterManager filterManager,
                               LocalBitcoinNode localBitcoinNode,
                               TorNetworkSettingsWindow torNetworkSettingsWindow,
                               ClockWatcher clockWatcher) {
        super();
        this.walletsSetup = walletsSetup;
        this.p2PService = p2PService;
        this.connectionManager = connectionManager;
        this.preferences = preferences;
        this.xmrNodes = xmrNodes;
        this.filterManager = filterManager;
        this.localBitcoinNode = localBitcoinNode;
        this.torNetworkSettingsWindow = torNetworkSettingsWindow;
        this.clockWatcher = clockWatcher;
    }

    @Override
    public void initialize() {
        btcHeader.setText(Res.get("settings.net.btcHeader"));
        p2pHeader.setText(Res.get("settings.net.p2pHeader"));
        onionAddress.setPromptText(Res.get("settings.net.onionAddressLabel"));
        xmrNodesLabel.setText(Res.get("settings.net.xmrNodesLabel"));
        moneroPeersLabel.setText(Res.get("settings.net.moneroPeersLabel"));
        useTorForXmrJCheckBox.setText(Res.get("settings.net.useTorForXmrJLabel"));
        moneroNodesLabel.setText(Res.get("settings.net.moneroNodesLabel"));
        moneroPeerAddressColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.onionAddressColumn")));
        moneroPeerAddressColumn.getStyleClass().add("first-column");
        moneroPeerVersionColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.versionColumn")));
        moneroPeerSubVersionColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.subVersionColumn")));
        moneroPeerHeightColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.heightColumn")));
        localhostXmrNodeInfoLabel.setText(Res.get("settings.net.localhostXmrNodeInfo"));
        useProvidedNodesRadio.setText(Res.get("settings.net.useProvidedNodesRadio"));
        useCustomNodesRadio.setText(Res.get("settings.net.useCustomNodesRadio"));
        usePublicNodesRadio.setText(Res.get("settings.net.usePublicNodesRadio"));
        rescanOutputsLabel.setText(Res.get("settings.net.rescanOutputsLabel"));
        rescanOutputsButton.updateText(Res.get("settings.net.rescanOutputsButton"));
        p2PPeersLabel.setText(Res.get("settings.net.p2PPeersLabel"));
        onionAddressColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.onionAddressColumn")));
        onionAddressColumn.getStyleClass().add("first-column");
        creationDateColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.creationDateColumn")));
        connectionTypeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.connectionTypeColumn")));
        sentDataTextField.setPromptText(Res.get("settings.net.sentDataLabel"));
        receivedDataTextField.setPromptText(Res.get("settings.net.receivedDataLabel"));
        chainHeightTextField.setPromptText(Res.get("settings.net.chainHeightLabel"));
        roundTripTimeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.roundTripTimeColumn")));
        sentBytesColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.sentBytesColumn")));
        receivedBytesColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.receivedBytesColumn")));
        peerTypeColumn.setGraphic(new AutoTooltipLabel(Res.get("settings.net.peerTypeColumn")));
        peerTypeColumn.getStyleClass().add("last-column");
        openTorSettingsButton.updateText(Res.get("settings.net.openTorSettingsButton"));

        // TODO: hiding button to rescan outputs until supported
        rescanOutputsLabel.setVisible(false);
        rescanOutputsButton.setVisible(false);

        GridPane.setMargin(moneroPeersLabel, new Insets(4, 0, 0, 0));
        GridPane.setValignment(moneroPeersLabel, VPos.TOP);

        GridPane.setMargin(p2PPeersLabel, new Insets(4, 0, 0, 0));
        GridPane.setValignment(p2PPeersLabel, VPos.TOP);

        moneroPeersTableView.setMinHeight(180);
        moneroPeersTableView.setPrefHeight(180);
        moneroPeersTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        moneroPeersTableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noData")));
        moneroPeersTableView.getSortOrder().add(moneroPeerAddressColumn);
        moneroPeerAddressColumn.setSortType(TableColumn.SortType.ASCENDING);


        p2pPeersTableView.setMinHeight(180);
        p2pPeersTableView.setPrefHeight(180);
        p2pPeersTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p2pPeersTableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noData")));
        p2pPeersTableView.getSortOrder().add(creationDateColumn);
        creationDateColumn.setSortType(TableColumn.SortType.ASCENDING);

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

        xmrNodesInputTextField.setPromptText(Res.get("settings.net.ips"));
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
        filterPropertyListener = (observable, oldValue, newValue) -> applyPreventPublicBtcNetwork();

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
        moneroPeersToggleGroup.selectedToggleProperty().addListener(moneroPeersToggleGroupListener);

        if (filterManager.getFilter() != null)
            applyPreventPublicBtcNetwork();

        filterManager.filterProperty().addListener(filterPropertyListener);

        useTorForXmrJCheckBox.setSelected(preferences.getUseTorForMonero());
        useTorForXmrJCheckBox.setOnAction(event -> {
            boolean selected = useTorForXmrJCheckBox.isSelected();
            if (selected != preferences.getUseTorForMonero()) {
                new Popup().information(Res.get("settings.net.needRestart"))
                        .actionButtonText(Res.get("shared.applyAndShutDown"))
                        .onAction(() -> {
                            preferences.setUseTorForMonero(selected);
                            UserThread.runAfter(HavenoApp.getShutDownHandler(), 500, TimeUnit.MILLISECONDS);
                        })
                        .closeButtonText(Res.get("shared.cancel"))
                        .onClose(() -> useTorForXmrJCheckBox.setSelected(!selected))
                        .show();
            }
        });

        rescanOutputsButton.setOnAction(event -> GUIUtil.rescanOutputs(preferences));

        moneroPeersSubscription = EasyBind.subscribe(connectionManager.peerConnectionsProperty(),
                this::updateMoneroPeersTable);

        moneroBlockHeightSubscription = EasyBind.subscribe(connectionManager.chainHeightProperty(),
                this::updateChainHeightTextField);

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

        moneroSortedList.comparatorProperty().bind(moneroPeersTableView.comparatorProperty());
        moneroPeersTableView.setItems(moneroSortedList);

        p2pSortedList.comparatorProperty().bind(p2pPeersTableView.comparatorProperty());
        p2pPeersTableView.setItems(p2pSortedList);

        xmrNodesInputTextField.setText(preferences.getMoneroNodes());

        xmrNodesInputTextField.focusedProperty().addListener(xmrNodesInputTextFieldFocusListener);

        openTorSettingsButton.setOnAction(e -> torNetworkSettingsWindow.show());
    }

    @Override
    public void deactivate() {
        moneroPeersToggleGroup.selectedToggleProperty().removeListener(moneroPeersToggleGroupListener);
        filterManager.filterProperty().removeListener(filterPropertyListener);

        useTorForXmrJCheckBox.setOnAction(null);

        if (nodeAddressSubscription != null)
            nodeAddressSubscription.unsubscribe();

        if (moneroPeersSubscription != null)
            moneroPeersSubscription.unsubscribe();

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
               filterManager.getFilter().isPreventPublicBtcNetwork();
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

    private void onMoneroPeersToggleSelected(boolean calledFromUser) {
        boolean localBitcoinNodeShouldBeUsed = localBitcoinNode.shouldBeUsed();
        useTorForXmrJCheckBox.setDisable(localBitcoinNodeShouldBeUsed);
        moneroNodesLabel.setDisable(localBitcoinNodeShouldBeUsed);
        xmrNodesLabel.setDisable(localBitcoinNodeShouldBeUsed);
        xmrNodesInputTextField.setDisable(localBitcoinNodeShouldBeUsed);
        useProvidedNodesRadio.setDisable(localBitcoinNodeShouldBeUsed);
        useCustomNodesRadio.setDisable(localBitcoinNodeShouldBeUsed);
        usePublicNodesRadio.setDisable(localBitcoinNodeShouldBeUsed || isPreventPublicXmrNetwork());

        XmrNodes.MoneroNodesOption currentBitcoinNodesOption = XmrNodes.MoneroNodesOption.values()[preferences.getMoneroNodesOptionOrdinal()];

        switch (selectedMoneroNodesOption) {
            case CUSTOM:
                xmrNodesInputTextField.setDisable(false);
                xmrNodesLabel.setDisable(false);
                if (!xmrNodesInputTextField.getText().isEmpty()
                        && xmrNodesInputTextField.validate()
                        && currentBitcoinNodesOption != XmrNodes.MoneroNodesOption.CUSTOM) {
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
                if (currentBitcoinNodesOption != XmrNodes.MoneroNodesOption.PUBLIC) {
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
                if (currentBitcoinNodesOption != XmrNodes.MoneroNodesOption.PROVIDED) {
                    preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
                    if (calledFromUser) {
                        showShutDownPopup();
                    }
                }
                break;
        }
    }


    private void applyPreventPublicBtcNetwork() {
        final boolean preventPublicBtcNetwork = isPreventPublicXmrNetwork();
        usePublicNodesRadio.setDisable(localBitcoinNode.shouldBeUsed() || preventPublicBtcNetwork);
        if (preventPublicBtcNetwork && selectedMoneroNodesOption == XmrNodes.MoneroNodesOption.PUBLIC) {
            selectedMoneroNodesOption = XmrNodes.MoneroNodesOption.PROVIDED;
            preferences.setMoneroNodesOptionOrdinal(selectedMoneroNodesOption.ordinal());
            selectMoneroPeersToggle();
            onMoneroPeersToggleSelected(false);
        }
    }

    private void updateP2PTable() {
        p2pPeersTableView.getItems().forEach(P2pNetworkListItem::cleanup);
        p2pNetworkListItems.clear();
        p2pNetworkListItems.setAll(p2PService.getNetworkNode().getAllConnections().stream()
                .map(connection -> new P2pNetworkListItem(connection, clockWatcher))
                .collect(Collectors.toList()));
    }

    private void updateMoneroPeersTable(List<MoneroPeer> peers) {
        moneroNetworkListItems.clear();
        if (peers != null) {
            moneroNetworkListItems.setAll(peers.stream()
                    .map(MoneroNetworkListItem::new)
                    .collect(Collectors.toList()));
        }
    }

    private void updateChainHeightTextField(Number chainHeight) {
        chainHeightTextField.textProperty().setValue(Res.get("settings.net.chainHeight", chainHeight));
    }
}

