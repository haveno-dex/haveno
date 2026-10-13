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
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIconView;
import haveno.common.ClockWatcher;
import haveno.common.config.Config;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrLocalNode;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.Preferences.NetworkSettings;
import haveno.core.user.Preferences.UseTorForXmr;
import haveno.core.util.FormattingUtils;
import haveno.core.util.validation.RegexValidatorFactory;
import haveno.core.util.validation.RegexValidator;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodes.MoneroNodesOption;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.DisplayUtils;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.Statistic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.text.Text;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.PopupWindow.AnchorLocation;
import javafx.util.StringConverter;
import monero.common.MoneroRpcConnection;
import monero.common.NetworkUtils;

@FxmlView
public class NetworkSettingsView extends ActivatableView<GridPane, Void> {
    @FXML private Label moneroHeading, moneroStatus, blockHeight, routeDivider, route, statusTitle, statusDetail, selectionNote;
    @FXML private Label sourceLabel, torLabel, torHelp, addHint, draftText, pendingText;
    @FXML private Label havenoHeading, havenoStatus, ownAddressLabel, ownAddress, sentLabel, receivedLabel;
    @FXML private Label sentTotal, sentMessages, receivedTotal, receivedMessages, peersLabel;
    @FXML private Text sourceHelp;
    @FXML private VBox statusNote, customEditor, customList;
    @FXML private HBox draftBar, pendingBar;
    @FXML private ComboBox<MoneroNodesOption> source;
    @FXML private ComboBox<UseTorForXmr> tor;
    @FXML private TextField addInput;
    @FXML private Button addButton, cancelButton, saveButton, shutdownButton, copyAddressButton;
    @FXML private Hyperlink remoteNodeLink, openTorSettingsButton;
    @FXML private TableView<MoneroNetworkListItem> moneroConnectionsTableView;
    @FXML private TableColumn<MoneroNetworkListItem, MoneroNetworkListItem> nodeAddressColumn, nodeLatencyColumn, nodeStatusColumn, nodeActionsColumn;
    @FXML private TableView<P2pNetworkListItem> p2pPeersTableView;
    @FXML private TableColumn<P2pNetworkListItem, P2pNetworkListItem> peerAddressColumn, peerTypeColumn, peerDirectionColumn,
            peerSinceColumn, peerLatencyColumn, peerSentColumn, peerReceivedColumn;

    private static final RegexValidator NODE_ADDRESS_VALIDATOR = RegexValidatorFactory.addressRegexValidator();
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
    private static final PseudoClass ONLINE = PseudoClass.getPseudoClass("online");
    private static final PseudoClass UNCHECKED = PseudoClass.getPseudoClass("unchecked");
    private static final int TABLE_CHROME_HEIGHT = 32; // header bar
    private final Config config;
    private final Preferences preferences;
    private final XmrNodes xmrNodes;
    private final FilterManager filterManager;
    private final TorNetworkSettingsWindow torNetworkSettingsWindow;
    private final ClockWatcher clockWatcher;
    private final P2PService p2PService;
    private final XmrConnectionService connectionService;
    private final XmrWalletService walletService;
    private final XmrLocalNode xmrLocalNode;
    private final ObservableList<MoneroNetworkListItem> nodes = FXCollections.observableArrayList();
    private final ObservableList<P2pNetworkListItem> peers = FXCollections.observableArrayList();
    private final SortedList<MoneroNetworkListItem> sortedNodes = new SortedList<>(nodes);
    private final SortedList<P2pNetworkListItem> sortedPeers = new SortedList<>(peers);
    private final List<CustomNode> customNodes = new ArrayList<>();
    private final Map<String, MoneroRpcConnection> probeResults = new HashMap<>();
    private List<CustomStatus> renderedCustomStatuses = List.of();
    private final Map<String, Integer> nodeOrder = new HashMap<>();
    private NetworkSettings loadedSettings;
    private Timer refreshTimer;
    private volatile boolean active;
    private final ChangeListener<Filter> filterListener = (observable, oldValue, newValue) -> UserThread.execute(() -> {
        if (active) applyFilter();
    });
    private boolean loading;
    private volatile long activation;
    private String checkingUri, manualUri;
    private ContextMenu nodeMenu;
    private final Node routeIcon = torIcon();

    @Inject
    public NetworkSettingsView(Config config, P2PService p2PService, XmrConnectionService connectionService,
                               Preferences preferences, XmrNodes xmrNodes, FilterManager filterManager,
                               TorNetworkSettingsWindow torNetworkSettingsWindow, ClockWatcher clockWatcher,
                               XmrWalletService walletService, XmrLocalNode xmrLocalNode) {
        this.config = config;
        this.p2PService = p2PService;
        this.connectionService = connectionService;
        this.preferences = preferences;
        this.xmrNodes = xmrNodes;
        this.filterManager = filterManager;
        this.torNetworkSettingsWindow = torNetworkSettingsWindow;
        this.clockWatcher = clockWatcher;
        this.walletService = walletService;
        this.xmrLocalNode = xmrLocalNode;
    }

    @Override
    public void initialize() {
        root.getStylesheets().add(getClass().getResource("network-settings.css").toExternalForm());
        moneroHeading.setText(Res.get("settings.net.xmrHeader"));
        havenoHeading.setText(Res.get("settings.net.p2pHeader"));
        sourceLabel.setText(text("nodes"));
        sourceLabel.setLabelFor(source);
        torLabel.setText(text("tor"));
        torLabel.setLabelFor(tor);
        remoteNodeLink.setText(text("aboutNodes"));
        remoteNodeLink.setOnAction(event -> GUIUtil.openWebPage("https://www.getmonero.org/resources/moneropedia/remote-node.html"));
        openTorSettingsButton.setText(text("torSettings"));
        openTorSettingsButton.setOnAction(event -> torNetworkSettingsWindow.show());
        peersLabel.setText(Res.get("settings.net.p2PPeersLabel"));
        sentLabel.setText(Res.get("settings.net.sentBytesColumn"));
        receivedLabel.setText(Res.get("settings.net.receivedBytesColumn"));
        addInput.setPromptText(text("addPrompt"));
        addInput.setAccessibleText(text("node"));
        addButton.setText(text("add"));
        addButton.setOnAction(event -> addNodes());
        addInput.setOnAction(event -> addNodes());
        addInput.textProperty().addListener((observable, oldValue, newValue) -> setHint(null));
        cancelButton.setText(Res.get("shared.cancel"));
        cancelButton.setOnAction(event -> loadSavedSettings());
        saveButton.setText(text("save"));
        saveButton.setOnAction(event -> saveSettings());
        shutdownButton.setText(text("shutdown"));
        shutdownButton.setOnAction(event -> HavenoApp.getShutDownHandler().run());
        pendingText.setText(text("pending"));
        selectionNote.setGraphic(new MaterialDesignIconView(MaterialDesignIcon.INFORMATION_OUTLINE, "14"));
        selectionNote.setGraphicTextGap(8);
        draftText.setGraphic(new MaterialDesignIconView(MaterialDesignIcon.REFRESH, "15"));
        pendingText.setGraphic(new MaterialDesignIconView(MaterialDesignIcon.REFRESH, "15"));
        GUIUtil.configureCopyIcon(copyAddressButton, () -> {
            var address = p2PService.getNetworkNode().getNodeAddress();
            return address == null ? null : address.getFullAddress();
        });
        for (Node node : List.of(statusNote, customEditor, draftBar, pendingBar, remoteNodeLink)) {
            node.managedProperty().bind(node.visibleProperty());
        }
        source.setItems(FXCollections.observableArrayList(MoneroNodesOption.PROVIDED, MoneroNodesOption.PUBLIC, MoneroNodesOption.CUSTOM));
        source.setConverter(converter(this::sourceName));
        source.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(MoneroNodesOption option, boolean empty) {
                super.updateItem(option, empty);
                setText(empty || option == null ? null : sourceName(option));
                setDisable(!empty && option != null && !sourceAvailable(option));
            }
        });
        tor.setItems(FXCollections.observableArrayList(UseTorForXmr.AFTER_SYNC, UseTorForXmr.ON, UseTorForXmr.OFF));
        tor.setConverter(converter(option -> Res.get("settings.net.useTorForXmr" +
                (option == UseTorForXmr.ON ? "On" : option == UseTorForXmr.OFF ? "Off" : "AfterSync") + "Radio")));
        source.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!loading && newValue != null && newValue != oldValue && !sourceAvailable(newValue)) {
                loading = true;
                source.setValue(oldValue);
                loading = false;
                updateDraft();
                return;
            }
            updateDraft();
        });
        tor.valueProperty().addListener((observable, oldValue, newValue) -> updateDraft());
        source.setDisable(!config.xmrNodes.isEmpty() || !config.xmrNode.isEmpty() &&
                (!NetworkUtils.isLocalHost(config.xmrNode) || !config.ignoreLocalXmrNode));
        customEditor.setDisable(source.isDisabled());
        tor.setDisable(config.useTorForXmrOptionSetExplicitly);
        setupTables();
        loadSavedSettings();
    }

    private void setupTables() {
        GUIUtil.applyTableStyle(moneroConnectionsTableView);
        GUIUtil.applyTableStyle(p2pPeersTableView);
        moneroConnectionsTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        moneroConnectionsTableView.setPlaceholder(new Label(text("loadingNodes")));
        sortedNodes.comparatorProperty().bind(moneroConnectionsTableView.comparatorProperty());
        moneroConnectionsTableView.setItems(sortedNodes);
        configureColumn(nodeAddressColumn, text("node"), Comparator.comparing(MoneroNetworkListItem::getAddress), item -> {
            boolean operator = hasOperator(item.getConnection());
            Label address = operator ? new Label(item.getAddress()) : new AutoTooltipLabel(item.getAddress());
            if (operator) address.setTooltip(new Tooltip(item.getAddress() + "\n" + item.getOwner()));
            address.getStyleClass().add("network-node-address");
            address.setMinWidth(0);
            address.setMaxWidth(Double.MAX_VALUE);
            if (operator) return address;
            Label owner = new Label(item.getOwner());
            owner.getStyleClass().add("network-node-owner");
            VBox box = new VBox(1, address, owner);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        });
        configureColumn(nodeLatencyColumn, text("responseTime"), nullsLast(nodeLatencyColumn, MoneroNetworkListItem::getResponseTime),
                item -> {
                    Label label = new Label(item.isUnchecked() ? "" : formatResponseTime(item.getResponseTime())); // blank until checked
                    Long checked = XmrConnectionService.getLastCheckedTimestamp(item.getConnection());
                    if (checked != null) label.setTooltip(new Tooltip(text("checkedAt", DisplayUtils.formatDateTime(new Date(checked)))));
                    return label;
                });
        configureColumn(nodeStatusColumn, text("status"), Comparator.comparingInt(MoneroNetworkListItem::getRank), item -> {
            Label label = new Label(item.getStatus());
            label.getStyleClass().add("network-node-status");
            label.pseudoClassStateChanged(ACTIVE, item.isConnected());
            label.pseudoClassStateChanged(WARNING, item.isUnavailable());
            label.pseudoClassStateChanged(UNCHECKED, item.isUnchecked());
            if (item.isChecking()) label.setGraphic(checkingIndicator());
            else if (item.isConnected()) label.setGraphic(statusDot());
            return label;
        });
        configureColumn(nodeActionsColumn, "", null, item -> {
            Button button = new Button("⋯");
            button.getStyleClass().add("network-icon-button");
            button.setAccessibleText(text("actions", item.getAddress()));
            button.setOnAction(event -> showNodeMenu(item, button, 0, 0));
            return button;
        });
        moneroConnectionsTableView.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(MoneroNetworkListItem item, boolean empty) {
                super.updateItem(item, empty);
                pseudoClassStateChanged(ACTIVE, !empty && item != null && item.isCurrent());
                setPrefHeight(!empty && item != null && hasOperator(item.getConnection()) ? 40 : 54);
            }
        });
        moneroConnectionsTableView.setOnContextMenuRequested(event -> {
            Node node = event.getPickResult().getIntersectedNode();
            while (node != null && !(node instanceof TableRow)) node = node.getParent();
            if (node instanceof TableRow<?> row && row.getItem() instanceof MoneroNetworkListItem item) {
                showNodeMenu(item, row, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
        moneroConnectionsTableView.getSortOrder().add(nodeStatusColumn);
        p2pPeersTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p2pPeersTableView.setPlaceholder(new Label(text("noPeers")));
        sortedPeers.comparatorProperty().bind(p2pPeersTableView.comparatorProperty());
        p2pPeersTableView.setItems(sortedPeers);
        configureColumn(peerAddressColumn, text("peer"), Comparator.comparing(P2pNetworkListItem::getOnionAddress), item -> {
            Label label = new AutoTooltipLabel();
            label.textProperty().bind(item.onionAddressProperty());
            label.getStyleClass().add("network-peer-address");
            label.setMaxWidth(Double.MAX_VALUE);
            return label;
        });
        configureColumn(peerTypeColumn, text("type"), Comparator.comparing(P2pNetworkListItem::getPeerType), item -> boundLabel(item.peerTypeProperty()));
        configureColumn(peerDirectionColumn, text("direction"), Comparator.comparing(P2pNetworkListItem::isInbound),
                item -> new Label((item.isInbound() ? "↙  " : "↗  ") + text(item.isInbound() ? "directionInbound" : "directionOutbound")));
        configureColumn(peerSinceColumn, text("since"), Comparator.comparingLong(P2pNetworkListItem::getCreationTimestamp), item -> {
            Label label = boundLabel(item.sinceProperty());
            label.setTooltip(new Tooltip(item.getCreationDate()));
            return label;
        });
        configureColumn(peerLatencyColumn, Res.get("settings.net.roundTripTimeColumn"), nullsLast(peerLatencyColumn, P2pNetworkListItem::getResponseTime),
                item -> boundLabel(item.roundTripTimeProperty()));
        configureColumn(peerSentColumn, Res.get("settings.net.sentBytesColumn"), Comparator.comparingLong(P2pNetworkListItem::getSentBytesValue),
                item -> boundLabel(item.sentBytesProperty()));
        configureColumn(peerReceivedColumn, Res.get("settings.net.receivedBytesColumn"), Comparator.comparingLong(P2pNetworkListItem::getReceivedBytesValue),
                item -> boundLabel(item.receivedBytesProperty()));
        p2pPeersTableView.getSortOrder().add(peerSinceColumn);
    }

    private static Label boundLabel(ObservableValue<String> value) {
        Label label = new Label();
        label.textProperty().bind(value);
        return label;
    }

    private static <T> void configureColumn(TableColumn<T, T> column, String title, Comparator<T> comparator, Function<T, Node> contents) {
        column.setText(title);
        column.setReorderable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        if (comparator != null) {
            column.setComparator(comparator);
            column.setSortNode(new Region()); // arrow drawn beside the title instead of at the column edge
            column.setGraphic(sortArrow(column));
        }
        column.setCellFactory(value -> new TableCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                T previous = getItem();
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) setGraphic(null);
                else if (item != previous || getGraphic() == null) setGraphic(contents.apply(item));
            }
        });
    }

    private static Node sortArrow(TableColumn<?, ?> column) {
        Region arrow = new Region();
        arrow.getStyleClass().add("arrow");
        ObservableList<?> sortOrder = column.getTableView().getSortOrder();
        arrow.visibleProperty().bind(Bindings.createBooleanBinding(() -> !sortOrder.isEmpty() && sortOrder.get(0) == column, sortOrder)); // primary sort column only, stays managed so the skin keeps it
        arrow.rotateProperty().bind(Bindings.when(column.sortTypeProperty().isEqualTo(TableColumn.SortType.ASCENDING)).then(180).otherwise(0));
        return arrow;
    }

    private static <T> Comparator<T> nullsLast(TableColumn<T, T> column, Function<T, Long> value) {
        return (first, second) -> {
            Long a = value.apply(first), b = value.apply(second);
            if (a == null || b == null) {
                int result = a == b ? 0 : a == null ? 1 : -1;
                return column.getSortType() == TableColumn.SortType.DESCENDING ? -result : result;
            }
            return Long.compare(a, b);
        };
    }

    static String formatResponseTime(Long time) {
        if (time == null) return "—";
        return time >= 1000 ? text("seconds", String.format(Locale.getDefault(), "%.1f", time / 1000d)) : text("milliseconds", time);
    }

    @Override
    public void activate() {
        active = true;
        activation++;
        loadSavedSettings();
        applyFilter();
        filterManager.filterProperty().addListener(filterListener);
        refresh();
        refreshTimer = UserThread.runPeriodically(this::refresh, 1);
    }

    @Override
    public void deactivate() {
        active = false;
        activation++;
        checkingUri = null;
        if (refreshTimer != null) refreshTimer.stop();
        if (nodeMenu != null) nodeMenu.hide();
        filterManager.filterProperty().removeListener(filterListener);
        peers.forEach(P2pNetworkListItem::cleanup);
        peers.clear();
    }

    private void refresh() {
        if (!active || connectionService.isShutDownStarted()) return;
        MoneroRpcConnection current = connectionService.getConnection();
        Boolean connectionStatus = current == null ? null : connectionService.isConnected();
        boolean connected = Boolean.TRUE.equals(connectionStatus);
        List<MoneroRpcConnection> connections = connectionService.getConnections();
        for (MoneroRpcConnection connection : connections) nodeOrder.computeIfAbsent(connection.getUri(), key -> nodeOrder.size());
        moneroConnectionsTableView.setPlaceholder(new Label(text(connections.isEmpty() && isPublicBlocked() ? "noConfiguredNodes" : "loadingNodes")));
        if (nodeMenu == null || !nodeMenu.isShowing()) {
            MoneroNetworkListItem selected = moneroConnectionsTableView.getSelectionModel().getSelectedItem();
            List<MoneroNetworkListItem> updated = connections.stream().sorted(Comparator.comparingInt(connection -> nodeOrder.get(connection.getUri())))
                    .map(connection -> new MoneroNetworkListItem(displayConnection(connection, current), current != null && connection.getUri().equals(current.getUri()),
                            connectionStatus, connection.getUri().equals(checkingUri), owner(connection)))
                    .collect(Collectors.toList());
            if (updated.size() != nodes.size()) nodes.setAll(updated);
            else for (int i = 0; i < updated.size(); i++) {
                if (!nodes.get(i).hasSameDisplay(updated.get(i))) nodes.set(i, updated.get(i));
            }
            if (selected != null) {
                moneroConnectionsTableView.getSelectionModel().select(nodes.stream()
                        .filter(item -> item.getConnection().getUri().equals(selected.getConnection().getUri())).findFirst().orElse(null));
            }
        }
        fitTable(moneroConnectionsTableView, nodes.stream().mapToInt(item -> hasOperator(item.getConnection()) ? 40 : 54).sum(), 212); // cap on a half row so the scroll is visible
        moneroStatus.setText("●  " + text(connected ? "connected" : Boolean.FALSE.equals(connectionStatus) ? "notResponding" : "connecting"));
        moneroStatus.pseudoClassStateChanged(WARNING, !connected);
        long height = connectionService.chainHeightProperty().get();
        blockHeight.setText(text(height <= 0 ? "unknownBlock" : connected ? "block" : "lastBlock", String.format(Locale.getDefault(), "%,d", height)));
        boolean local = current != null && NetworkUtils.isLocalHost(current.getUri());
        boolean showRoute = current != null; // the route still applies while the node is not responding
        routeDivider.setVisible(showRoute);
        routeDivider.setManaged(showRoute);
        route.setVisible(showRoute);
        route.setManaged(showRoute);
        route.setText(!showRoute ? "" : text(local ? "directLocal" : walletService.isProxyApplied() ? "viaTor" :
                preferences.getEffectiveNetworkSettings().tor() == UseTorForXmr.AFTER_SYNC && !walletService.wasWalletSynced() &&
                        !NetworkUtils.isPrivateIp(current.getUri()) ? "directUntilSynced" : "direct"));
        route.setGraphic(showRoute && !local && walletService.isProxyApplied() ? routeIcon : null);
        updateStatus(current, connectionStatus, connections.size());
        if (!customNodes.stream().map(this::customStatus).collect(Collectors.toList()).equals(renderedCustomStatuses)) renderCustomNodes();
        if (manualUri != null && (current == null || !manualUri.equals(current.getUri()))) manualUri = null;
        boolean localNode = !xmrLocalNode.shouldBeIgnored();
        selectionNote.setText(text(connectionService.isFixedConnection() ? "fixedNode" : connections.isEmpty() ? "checkingNodes" : connections.size() == 1 ? "onlyNode" :
                manualUri != null ? (localNode ? "manualNodeLocal" : "manualNode") : localNode ? "automaticNodesLocal" : "automaticNodes"));
        Set<Connection> livePeers = p2PService.getNetworkNode().getAllConnections();
        List<P2pNetworkListItem> removed = peers.stream().filter(item -> !livePeers.contains(item.getConnection())).collect(Collectors.toList());
        removed.forEach(P2pNetworkListItem::cleanup);
        peers.removeAll(removed);
        Set<Connection> existing = peers.stream().map(P2pNetworkListItem::getConnection).collect(Collectors.toSet());
        for (Connection connection : livePeers) if (!existing.contains(connection)) peers.add(new P2pNetworkListItem(connection, p2PService.getPeerManager(), clockWatcher));
        p2pPeersTableView.sort();
        fitTable(p2pPeersTableView, peers.size() * 40, 372);
        havenoStatus.setText("●  " + (peers.isEmpty() ? text("connecting") : text("peerCount", peers.size())));
        havenoStatus.pseudoClassStateChanged(WARNING, peers.isEmpty());
        var address = p2PService.getNetworkNode().getNodeAddress();
        ownAddress.setText(address == null ? text("notAvailable") : address.getFullAddress());
        ownAddressLabel.setText(address != null && !address.getHostName().endsWith(".onion") ? text("localAddress") : Res.get("settings.net.onionAddressLabel"));
        copyAddressButton.setDisable(address == null);
        sentTotal.setText(FormattingUtils.formatBytes(Statistic.totalSentBytesProperty().get()));
        receivedTotal.setText(FormattingUtils.formatBytes(Statistic.totalReceivedBytesProperty().get()));
        sentMessages.setText(text("messages", Statistic.numTotalSentMessagesProperty().get(), String.format(Locale.getDefault(), "%.2f", Statistic.numTotalSentMessagesPerSecProperty().get())));
        receivedMessages.setText(text("messages", Statistic.numTotalReceivedMessagesProperty().get(), String.format(Locale.getDefault(), "%.2f", Statistic.numTotalReceivedMessagesPerSecProperty().get())));
        updateDraft();
    }

    private MoneroRpcConnection displayConnection(MoneroRpcConnection connection, MoneroRpcConnection current) {
        MoneroRpcConnection probe = probeResults.get(connection.getUri());
        if (connection == current || probe == null) return connection;
        Long checked = XmrConnectionService.getLastCheckedTimestamp(connection);
        Long probed = XmrConnectionService.getLastCheckedTimestamp(probe);
        return probed != null && (checked == null || probed > checked) ? probe : connection;
    }

    private void updateStatus(MoneroRpcConnection current, Boolean connectionStatus, int numConnections) {
        boolean noNodes = isPublicBlocked() && numConnections == 0;
        boolean notResponding = current != null && Boolean.FALSE.equals(connectionStatus); // shown by the node row
        boolean syncing = !notResponding && walletService.isSyncing() && !walletService.wasWalletSynced();
        statusNote.setVisible(noNodes || syncing);
        statusNote.pseudoClassStateChanged(WARNING, noNodes);
        if (noNodes) {
            statusTitle.setText(text("noUsableNodes"));
            statusDetail.setText(text("addCustomNodes"));
        } else if (syncing) {
            long percentage = Math.round(walletService.downloadPercentageProperty().get() * 100);
            statusTitle.setText(percentage > 0 ? text("syncing", percentage) : text("syncingStarting"));
            statusDetail.setText(text(walletService.isProxyApplied() ? "syncTor" : connectionService.isConnectionLocalHost() ? "syncLocal" :
                    preferences.getEffectiveNetworkSettings().tor() == UseTorForXmr.AFTER_SYNC && current != null &&
                            !NetworkUtils.isPrivateIp(current.getUri()) ? "syncDirect" : "syncNever"));
        }
    }

    private String owner(MoneroRpcConnection connection) {
        if (NetworkUtils.isLocalHost(connection.getUri())) return text("thisDevice");
        for (XmrNode node : xmrNodes.getAllXmrNodes()) {
            if (matchesNode(node, connection)) {
                return node.getOperator() != null ? node.getOperator() :
                        text(node.getType() == MoneroNodesOption.PROVIDED ? "providedNode" : node.getType() == MoneroNodesOption.PUBLIC ? "publicNode" : "customNode");
            }
        }
        return text("customNode");
    }

    private boolean hasOperator(MoneroRpcConnection connection) {
        return !NetworkUtils.isLocalHost(connection.getUri()) && xmrNodes.getAllXmrNodes().stream()
                .anyMatch(node -> node.getOperator() != null && matchesNode(node, connection));
    }

    private static void fitTable(TableView<?> table, int contentHeight, int cap) {
        double height = Math.min(cap, TABLE_CHROME_HEIGHT + (table.getItems().isEmpty() ? 68 : contentHeight));
        table.setMinHeight(height);
        table.setPrefHeight(height);
        table.setMaxHeight(height);
    }

    private void loadSavedSettings() {
        loading = true;
        NetworkSettings saved = preferences.getNetworkSettings();
        loadedSettings = saved;
        source.setValue(saved.nodes());
        tor.setValue(saved.tor());
        customNodes.clear();
        if (saved.customNodes() != null && !saved.customNodes().isBlank()) {
            for (String address : saved.customNodes().split(",\\s*")) customNodes.add(new CustomNode(address.trim()));
        }
        addInput.clear();
        loading = false;
        setHint(null);
        renderCustomNodes();
        updateDraft();
    }

    private NetworkSettings draftSettings() {
        return new NetworkSettings(source.getValue(), tor.getValue(), source.getValue() == MoneroNodesOption.CUSTOM ?
                customNodes.stream().map(node -> node.address).collect(Collectors.joining(",")) : preferences.getNetworkSettings().customNodes());
    }

    private void updateDraft() {
        if (loading || source.getValue() == null || tor.getValue() == null) return;
        if (loadedSettings != null && draftSettings().sameConfiguration(loadedSettings) &&
                !preferences.getNetworkSettings().sameConfiguration(loadedSettings)) {
            loadSavedSettings();
            return;
        }
        MoneroNodesOption option = source.getValue();
        customEditor.setVisible(option == MoneroNodesOption.CUSTOM);
        remoteNodeLink.setVisible(option == MoneroNodesOption.PUBLIC);
        sourceHelp.setText(text(option == MoneroNodesOption.CUSTOM ? isPublicBlocked() ? "customHelpTrusted" : "customHelp" :
                option == MoneroNodesOption.PUBLIC ? "publicHelp" : "providedHelp") + " ");
        if (source.isDisabled()) sourceHelp.setText(text("startupOverride"));
        boolean local = connectionService.isConnectionLocalHost();
        torHelp.setText(text(local ? "torHelpLocal" : tor.getValue() == UseTorForXmr.AFTER_SYNC ? "torHelpAfter" : tor.getValue() == UseTorForXmr.ON ? "torHelpAlways" : "torHelpNever"));
        if (tor.isDisabled()) torHelp.setText(text("startupOverride"));
        boolean dirty = !draftSettings().sameConfiguration(preferences.getNetworkSettings());
        boolean blocked = !canSaveSettings();
        draftBar.setVisible(dirty);
        pendingBar.setVisible(!dirty && !preferences.getNetworkSettings().sameConfiguration(preferences.getEffectiveNetworkSettings()));
        draftText.setText(text(option == MoneroNodesOption.CUSTOM && customNodes.isEmpty() ? "emptyCustom" :
                option == MoneroNodesOption.CUSTOM && blocked ? "invalidCustom" : "draft"));
        saveButton.setDisable(blocked);
    }

    private boolean canSaveSettings() {
        MoneroNodesOption option = source.getValue();
        if (!sourceAvailable(option) && (option != preferences.getNetworkSettings().nodes() || option == MoneroNodesOption.PUBLIC)) return false;
        return option != MoneroNodesOption.CUSTOM || customEditor.isDisabled() || !customNodes.isEmpty() && customNodes.stream().allMatch(node -> validateAddress(node.address) == null);
    }

    private void saveSettings() {
        if (!canSaveSettings()) return;
        preferences.setNetworkSettingsForNextStart(draftSettings());
        loadSavedSettings();
    }

    private boolean isPublicBlocked() {
        return filterManager.getFilter() != null && filterManager.getFilter().isPreventPublicXmrNetwork();
    }

    private boolean sourceAvailable(MoneroNodesOption option) {
        return option == MoneroNodesOption.CUSTOM || (option == MoneroNodesOption.PROVIDED ? !xmrNodes.getProvidedXmrNodes().isEmpty() :
                !xmrNodes.getPublicXmrNodes().isEmpty() && !isPublicBlocked());
    }

    private String sourceName(MoneroNodesOption option) {
        String name = text(option == MoneroNodesOption.PROVIDED ? "provided" : option == MoneroNodesOption.PUBLIC ? "public" : "custom");
        return sourceAvailable(option) ? name : name + " · " + text(option == MoneroNodesOption.PUBLIC && isPublicBlocked() ? "blockedByFilter" : "noneOnNetwork");
    }

    private void applyFilter() {
        if (isPublicBlocked() && source.getValue() == MoneroNodesOption.PUBLIC) {
            loading = true;
            source.setValue(MoneroNodesOption.PROVIDED);
            loading = false;
        }
        source.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(MoneroNodesOption option, boolean empty) {
                super.updateItem(option, empty);
                setText(empty || option == null ? null : sourceName(option));
            }
        });
        updateDraft();
    }

    private void addNodes() {
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int added = 0;
        for (String address : addInput.getText().trim().split("[\\s,]+")) {
            if (address.isEmpty()) continue;
            String error = validateAddress(address);
            String uri = error == null ? nodeUri(address) : null;
            if (error == null && customNodes.stream().anyMatch(node -> Objects.equals(node.uri, uri))) error = text("duplicate");
            if (error != null) {
                rejected.add(address);
                errors.add(address + ": " + error);
            } else {
                CustomNode node = new CustomNode(address.toLowerCase(Locale.ROOT));
                customNodes.add(node);
                checkCustomNode(node);
                added++;
            }
        }
        addInput.setText(String.join(", ", rejected));
        setHint(errors.isEmpty() ? null : (added == 0 ? "" : text("added", added) + " ") + String.join("\n", errors));
        renderCustomNodes();
        updateDraft();
    }

    static String validateAddress(String address) {
        String normalized = address.toLowerCase(Locale.ROOT);
        if (normalized.contains("://") && !normalized.startsWith("http://") && !normalized.startsWith("https://")) return text("invalidScheme");
        String hostPort = normalized.replaceFirst("^https?://", "");
        if (hostPort.matches(".*\\.onion(?::.*)?") && !hostPort.matches("[a-z2-7]{56}\\.onion(?::[0-9]+)?")) return text("invalidOnion");
        String port = hostPort.startsWith("[") ? hostPort.substring(Math.min(hostPort.length(), hostPort.indexOf(']') + 1)) :
                hostPort.chars().filter(character -> character == ':').count() == 1 ? hostPort.substring(hostPort.indexOf(':')) : "";
        if (!port.isEmpty()) {
            try {
                int number = Integer.parseInt(port.substring(1));
                if (!port.matches(":[0-9]{1,5}") || number < 1 || number > 65535) return text("invalidPort");
            } catch (NumberFormatException e) {
                return text("invalidPort");
            }
        }
        if (address.isBlank() || address.matches(".*[\\s,].*") || !NODE_ADDRESS_VALIDATOR.validate(normalized).isValid) return text("invalidAddress");
        try {
            nodeUri(normalized);
        } catch (IllegalArgumentException e) {
            return text("invalidAddress");
        }
        return null;
    }

    static String nodeUri(String address) {
        return XmrNode.fromFullAddress(address.toLowerCase(Locale.ROOT)).getUri();
    }

    static String displayAddress(String uri) {
        return uri.startsWith("http://") ? uri.substring(7) : uri;
    }

    private void setHint(String error) {
        addHint.setText(error == null ? text("addHint", HavenoUtils.getDefaultMoneroPort()) : error);
        addHint.pseudoClassStateChanged(INVALID, error != null);
        addInput.pseudoClassStateChanged(INVALID, error != null);
    }

    private void renderCustomNodes() {
        customList.getChildren().clear();
        List<CustomStatus> statuses = new ArrayList<>();
        if (customNodes.isEmpty()) {
            Label empty = new Label(text("noCustomNodes"));
            empty.getStyleClass().addAll("network-help", "network-custom-entry");
            customList.getChildren().add(empty);
        }
        for (CustomNode node : customNodes) {
            Label address = new AutoTooltipLabel(node.address);
            address.getStyleClass().add("network-mono");
            if (node.uri != null && node.uri.matches("https?://[a-z2-7]{56}\\.onion:[0-9]+")) address.setGraphic(torIcon());
            address.setMinWidth(0);
            address.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(address, Priority.ALWAYS);
            String validation = validateAddress(node.address);
            CustomStatus status = customStatus(node);
            statuses.add(status);
            HBox row = new HBox(14, address);
            if (status != null) {
                Label result = new Label(status.text());
                result.getStyleClass().add("network-result");
                if (node.checking) result.setGraphic(checkingIndicator());
                if (node.checkError != null) result.setTooltip(new Tooltip(node.checkError));
                result.setMinWidth(Label.USE_PREF_SIZE);
                if (status.tone() != null) result.pseudoClassStateChanged(status.tone(), true);
                if (validation != null) result.setTooltip(new Tooltip(validation));
                row.getChildren().add(result);
            }
            Button remove = new Button("×");
            remove.getStyleClass().add("network-icon-button");
            remove.setMinWidth(30);
            remove.setAccessibleText(text("removeNode", node.address));
            remove.setOnAction(event -> {
                customNodes.remove(node);
                renderCustomNodes();
                updateDraft();
            });
            row.getChildren().add(remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("network-custom-entry");
            customList.getChildren().add(row);
        }
        renderedCustomStatuses = statuses;
    }

    // status of a node not yet in the node table, which shows the status of the others
    private CustomStatus customStatus(CustomNode node) {
        if (validateAddress(node.address) != null) return new CustomStatus(text("invalidNode"), INVALID);
        if (connectionService.getConnections().stream().anyMatch(connection -> connection.getUri().equalsIgnoreCase(node.uri))) return null; // hosts are case-insensitive
        if (node.checking) return new CustomStatus(text("checking"), null);
        if (node.checkError != null) return new CustomStatus(text("checkFailed"), null);
        MoneroRpcConnection connection = probeResults.get(node.uri);
        if (connection == null) return new CustomStatus(text("notChecked"), null);
        MoneroNetworkListItem item = new MoneroNetworkListItem(connection, false, connectionService.isConnected(), false, null);
        return new CustomStatus(item.getStatus(), item.isUnavailable() ? WARNING : item.isReachable() ? ONLINE : null);
    }

    private void checkCustomNode(CustomNode node) {
        long generation = activation;
        node.checking = true;
        boolean useTor = tor.getValue() != UseTorForXmr.OFF;
        ThreadUtils.submitToPool(() -> {
            if (!active || activation != generation || connectionService.isShutDownStarted()) return;
            MoneroRpcConnection checked = null;
            String error = null;
            try {
                checked = connectionService.probeConnection(new MoneroRpcConnection(node.uri), useTor);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            MoneroRpcConnection result = checked;
            String checkError = error;
            UserThread.execute(() -> {
                if (!active || activation != generation || !customNodes.contains(node)) return;
                node.checking = false;
                if (result != null) probeResults.put(node.uri, result);
                node.checkError = checkError;
                renderCustomNodes();
            });
        });
    }

    private static boolean matchesNode(XmrNode node, MoneroRpcConnection connection) {
        return node.hasClearNetAddress() && connection.getUri().equals(node.getClearNetUri()) ||
                node.hasOnionAddress() && connection.getUri().equals(node.getUri());
    }

    private boolean isNodeAllowed(MoneroRpcConnection connection) {
        return !isPublicBlocked() || preferences.getEffectiveNetworkSettings().nodes() == MoneroNodesOption.CUSTOM ||
                xmrNodes.getProvidedXmrNodes().stream().anyMatch(node -> matchesNode(node, connection));
    }

    private void showNodeMenu(MoneroNetworkListItem item, Node anchor, double x, double y) {
        if (nodeMenu != null) nodeMenu.hide();
        MenuItem connect = new MenuItem(text("connectNow"));
        connect.setDisable(!isNodeAllowed(item.getConnection()) || checkingUri != null || item.isConnected() || connectionService.isFixedConnection());
        connect.setOnAction(event -> {
            if (walletService.isSyncing() || connectionService.hasSyncingWallets()) {
                new Popup().information(text("switchWhileSyncing"))
                        .actionButtonText(text("switchNode")).onAction(() -> connectNow(item))
                        .closeButtonText(Res.get("shared.cancel")).show();
            } else {
                connectNow(item);
            }
        });
        MenuItem copy = new MenuItem(text("copyAddress"));
        copy.setOnAction(event -> Utilities.copyToClipboard(item.getAddress()));
        nodeMenu = new ContextMenu(connect, new SeparatorMenuItem(), copy);
        ContextMenu menu = nodeMenu;
        menu.setId("network-node-menu");
        menu.getStyleClass().add("network-node-menu");
        Scene scene = anchor.getScene();
        menu.setConsumeAutoHidingEvents(false);
        // JavaFX auto-hide ignores clicks on the owner row, so also dismiss from the page scene
        EventHandler<MouseEvent> dismiss = event -> menu.hide();
        // presses on the drop shadow land in the menu's own window, so dismiss those too
        menu.getScene().addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            Node content = menu.getSkin().getNode();
            if (!content.getLayoutBounds().contains(content.sceneToLocal(event.getSceneX(), event.getSceneY()))) menu.hide();
        });
        menu.setOnShown(event -> scene.addEventFilter(MouseEvent.MOUSE_PRESSED, dismiss));
        menu.setOnHidden(event -> {
            scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, dismiss);
            moneroConnectionsTableView.getSelectionModel().clearSelection();
        });
        moneroConnectionsTableView.getSelectionModel().select(item);
        if (anchor instanceof Button) {
            Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            menu.setAnchorLocation(AnchorLocation.CONTENT_TOP_RIGHT);
            menu.show(anchor, bounds.getMaxX(), bounds.getMaxY() + 4);
        } else {
            menu.setAnchorLocation(AnchorLocation.CONTENT_TOP_LEFT);
            menu.show(anchor, x, y);
        }
    }

    private void connectNow(MoneroNetworkListItem item) {
        if (!active || checkingUri != null || !isNodeAllowed(item.getConnection())) return;
        long generation = activation;
        MoneroRpcConnection original = connectionService.getConnection(item.getConnection().getUri());
        if (original == null) return;
        checkingUri = original.getUri();
        if (nodeMenu != null) nodeMenu.hide(); // menu actions fire before the menu hides, which freezes the table
        refresh();
        ThreadUtils.submitToPool(() -> {
            String error = null;
            MoneroRpcConnection checked = null;
            try {
                checked = connectionService.probeConnection(original);
                if (!Boolean.TRUE.equals(checked.isConnected())) throw new IllegalStateException(text(Boolean.FALSE.equals(checked.isAuthenticated()) ? "requiresLogin" : "notReachable"));
                if (!active || activation != generation) return;
                connectionService.connectToNode(original, checked);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String failure = error;
            MoneroRpcConnection result = checked;
            UserThread.execute(() -> {
                if (!active || activation != generation) return;
                checkingUri = null;
                if (result != null) probeResults.put(result.getUri(), result);
                if (failure == null) manualUri = item.getConnection().getUri();
                else new Popup().warning(text("connectFailed", item.getAddress(), failure)).show();
                refresh();
            });
        });
    }

    private static Node torIcon() {
        MaterialDesignIconView icon = new MaterialDesignIconView(MaterialDesignIcon.SHIELD_OUTLINE, "14");
        icon.getStyleClass().add("network-tor-icon");
        return icon;
    }

    private static Circle statusDot() {
        Circle dot = new Circle(3);
        dot.getStyleClass().add("network-status-dot");
        return dot;
    }

    private static ProgressIndicator checkingIndicator() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMinSize(13, 13);
        indicator.setPrefSize(13, 13);
        indicator.setMaxSize(13, 13);
        return indicator;
    }

    private static <T> StringConverter<T> converter(Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String value) { return null; }
        };
    }

    static String text(String key, Object... arguments) {
        return Res.get("settings.net." + key, arguments);
    }

    private record CustomStatus(String text, PseudoClass tone) {} // tone matches the node table; red is only for invalid input

    private static class CustomNode {
        private final String address;
        private final String uri;
        private String checkError;
        private boolean checking;

        private CustomNode(String address) {
            this.address = address;
            String parsed;
            try {
                parsed = nodeUri(address);
            } catch (IllegalArgumentException e) {
                parsed = null;
            }
            uri = parsed;
        }
    }
}
