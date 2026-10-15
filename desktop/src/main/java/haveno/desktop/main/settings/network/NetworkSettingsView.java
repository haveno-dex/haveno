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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import javafx.scene.control.CustomMenuItem;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.PopupWindow.AnchorLocation;
import javafx.util.StringConverter;
import monero.common.MoneroRpcConnection;
import monero.common.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FxmlView
public class NetworkSettingsView extends ActivatableView<GridPane, Void> {
    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsView.class);
    @FXML private Label moneroHeading, moneroStatus, blockHeight, routeDivider, route, statusTitle, statusDetail, selectionNote;
    @FXML private Label sourceLabel, torLabel, torHelp, addHint, pendingText;
    @FXML private Label havenoHeading, havenoStatus, ownAddressLabel, ownAddress, sentLabel, receivedLabel;
    @FXML private Label sentTotal, sentMessages, receivedTotal, receivedMessages, peersLabel;
    @FXML private Text sourceHelp;
    @FXML private VBox statusNote, nodeCard;
    @FXML private HBox addRow, torPending;
    @FXML private ComboBox<MoneroNodesOption> source;
    @FXML private ComboBox<UseTorForXmr> tor;
    @FXML private TextField addInput;
    @FXML private Button addButton, shutdownButton, copyAddressButton;
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
    private static final PseudoClass EMPTY = PseudoClass.getPseudoClass("empty");
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
    private final Set<String> checkingUris = new HashSet<>();
    private final Map<String, MoneroRpcConnection> probeResults = new HashMap<>();
    private final Map<String, Integer> nodeOrder = new HashMap<>();
    private int nextNodeOrder;
    private boolean pendingCustom, switching, editingNodes;
    private SourceChange sourceChange, queuedSourceChange;
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
        addInput.textProperty().addListener((observable, oldValue, newValue) -> {
            setHint(null);
            addButton.pseudoClassStateChanged(EMPTY, newValue.isBlank());
        });
        addButton.pseudoClassStateChanged(EMPTY, true);
        shutdownButton.setText(text("shutdown"));
        shutdownButton.setOnAction(event -> HavenoApp.getShutDownHandler().run());
        pendingText.setText(text("torPending"));
        selectionNote.setGraphic(new MaterialDesignIconView(MaterialDesignIcon.INFORMATION_OUTLINE, "14"));
        selectionNote.setGraphicTextGap(8);
        pendingText.setGraphic(new MaterialDesignIconView(MaterialDesignIcon.REFRESH, "15"));
        GUIUtil.configureCopyIcon(copyAddressButton, () -> {
            var address = p2PService.getNetworkNode().getNodeAddress();
            return address == null ? null : address.getFullAddress();
        });
        for (Node node : List.of(statusNote, addRow, addHint, torPending, remoteNodeLink, selectionNote, source)) {
            node.managedProperty().bind(node.visibleProperty());
        }
        source.setItems(FXCollections.observableArrayList(Stream.of(MoneroNodesOption.PROVIDED, MoneroNodesOption.PUBLIC, MoneroNodesOption.CUSTOM)
                .filter(this::hasNodes).collect(Collectors.toList()))); // options without nodes on this network are hidden
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
            if (loading || newValue == null || newValue == oldValue) return;
            if (!canSelectSource() || !sourceAvailable(newValue)) {
                revertSource(oldValue);
                return;
            }
            if (newValue == (sourceChange == null ? preferences.getEffectiveNetworkSettings().nodes() : sourceChange.option())) {
                queuedSourceChange = null;
                pendingCustom = false;
                refresh();
            } else if (newValue == MoneroNodesOption.CUSTOM && (sourceChange == null ? customNodesCsv() : sourceChange.customNodes()).isBlank()) {
                queuedSourceChange = null;
                pendingCustom = true;
                refresh();
                addInput.requestFocus();
            } else {
                applySource(newValue, null);
            }
        });
        tor.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (loading || newValue == null || newValue == oldValue) return;
            preferences.setTorForNextStart(newValue);
            refresh();
        });
        tor.setDisable(config.useTorForXmrOptionSetExplicitly);
        setupTables();
        updateSettings();
    }

    private void setupTables() {
        GUIUtil.applyTableStyle(moneroConnectionsTableView, false);
        GUIUtil.applyRoundedArc(nodeCard); // round the card so the add row, not the last node, takes the bottom corners
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
            if (operator || item.getOwner() == null) return address;
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
                setPrefHeight(!empty && item != null && hasSubtitle(item) ? 54 : 40);
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
        addInput.clear();
        setHint(null);
        updateSettings();
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
        checkingUris.clear();
        pendingCustom = false;
        if (refreshTimer != null) refreshTimer.stop();
        if (nodeMenu != null) nodeMenu.hide();
        filterManager.filterProperty().removeListener(filterListener);
        peers.forEach(P2pNetworkListItem::cleanup);
        peers.clear();
    }

    private void refresh() {
        if (!active || connectionService.isShutDownStarted()) return;
        updateSettings();
        MoneroRpcConnection current = connectionService.getConnection();
        Boolean connectionStatus = current == null ? null : connectionService.isConnected();
        boolean changingConnection = switching || connectionService.isReloadingConnections();
        boolean connected = !changingConnection && Boolean.TRUE.equals(connectionStatus);
        List<MoneroRpcConnection> liveConnections = connectionService.getConnections();
        List<MoneroRpcConnection> connections = pendingCustom ? List.of() : liveConnections;
        for (MoneroRpcConnection connection : connections) nodeOrder.computeIfAbsent(connection.getUri(), key -> nextNodeOrder++);
        if (pendingCustom) {
            Label title = new Label(text("noCustomNodesTitle"));
            title.getStyleClass().add("network-empty-title");
            Label detail = new Label(text("noCustomNodesDetail"));
            detail.getStyleClass().add("network-empty-detail");
            detail.setWrapText(true);
            VBox empty = new VBox(4, title, detail);
            empty.setAlignment(Pos.CENTER);
            moneroConnectionsTableView.setPlaceholder(empty);
        } else {
            moneroConnectionsTableView.setPlaceholder(new Label(text(connections.isEmpty() && isPublicBlocked() ? "noConfiguredNodes" : "loadingNodes")));
        }
        if (nodeMenu == null || !nodeMenu.isShowing()) {
            MoneroNetworkListItem selected = moneroConnectionsTableView.getSelectionModel().getSelectedItem();
            List<MoneroNetworkListItem> updated = connections.stream().sorted(Comparator.comparingInt(connection -> nodeOrder.get(connection.getUri())))
                    .map(connection -> new MoneroNetworkListItem(displayConnection(connection, current), current != null && connection.getUri().equals(current.getUri()),
                            connectionService.isReloadingConnections() ? null : connectionStatus, connection.getUri().equals(checkingUri) || checkingUris.contains(connection.getUri()) || connectionService.isReloadingConnections(), owner(connection)))
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
        fitTable(moneroConnectionsTableView, nodes.stream().mapToInt(item -> hasSubtitle(item) ? 54 : 40).sum(), 212); // cap on a half row so the scroll is visible
        moneroStatus.setText("●  " + text(queuedSourceChange != null || pendingCustom && sourceChange != null ? "waitingForNodeCheck" : changingConnection ? "connecting" : connected ? "connected" : Boolean.FALSE.equals(connectionStatus) ? "notResponding" : "connecting"));
        moneroStatus.pseudoClassStateChanged(WARNING, !connected);
        long height = connectionService.chainHeightProperty().get();
        blockHeight.setText(text(height <= 0 ? "unknownBlock" : connected ? "block" : "lastBlock", String.format(Locale.getDefault(), "%,d", height)));
        boolean local = current != null && NetworkUtils.isLocalHost(current.getUri());
        boolean showRoute = current != null && !changingConnection; // the route still applies while the node is not responding
        routeDivider.setVisible(showRoute);
        routeDivider.setManaged(showRoute);
        route.setVisible(showRoute);
        route.setManaged(showRoute);
        route.setText(!showRoute ? "" : text(local ? "directLocal" : walletService.isProxyApplied() ? "viaTor" :
                preferences.getEffectiveNetworkSettings().tor() == UseTorForXmr.AFTER_SYNC && !walletService.wasWalletSynced() &&
                        !NetworkUtils.isPrivateIp(current.getUri()) ? "directUntilSynced" : "direct"));
        if (pendingCustom && showRoute) {
            route.setText(route.getText() + " · " + text(preferences.getEffectiveNetworkSettings().nodes() == MoneroNodesOption.PUBLIC ? "runningPublic" : "runningProvided"));
        }
        route.setGraphic(showRoute && !local && walletService.isProxyApplied() ? routeIcon : null);
        updateStatus(current, connectionStatus, liveConnections);
        if (manualUri != null && (current == null || !manualUri.equals(current.getUri()))) manualUri = null;
        boolean localNode = !xmrLocalNode.shouldBeIgnored();
        selectionNote.setText(text(connections.isEmpty() ? "checkingNodes" :
                manualUri != null ? (localNode ? "manualNodeLocal" : "manualNode") : localNode ? "automaticNodesLocal" : "automaticNodes"));
        selectionNote.setVisible(!pendingCustom && !connectionService.isFixedConnection() && (source.getValue() != MoneroNodesOption.CUSTOM || manualUri != null)); // the source help names the startup option
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
    }

    private MoneroRpcConnection displayConnection(MoneroRpcConnection connection, MoneroRpcConnection current) {
        MoneroRpcConnection probe = probeResults.get(connection.getUri());
        if (connection == current || probe == null) return connection;
        Long checked = XmrConnectionService.getLastCheckedTimestamp(connection);
        Long probed = XmrConnectionService.getLastCheckedTimestamp(probe);
        return probed != null && (checked == null || probed > checked) ? probe : connection;
    }

    private void updateStatus(MoneroRpcConnection current, Boolean connectionStatus, List<MoneroRpcConnection> connections) {
        boolean noNodes = isPublicBlocked() && connections.isEmpty();
        boolean pendingFilter = isPublicBlocked() && (!hasStartupOverride() || connectionService.isFallbackApplied()) &&
                !connectionService.isReloadingConnections() && connections.stream().anyMatch(connection -> !isNodeAllowed(connection));
        boolean notResponding = current != null && Boolean.FALSE.equals(connectionStatus); // shown by the node row
        boolean syncing = !notResponding && walletService.isSyncing() && !walletService.wasWalletSynced();
        statusNote.setVisible(noNodes || pendingFilter || syncing);
        statusNote.pseudoClassStateChanged(WARNING, noNodes || pendingFilter);
        if (noNodes) {
            statusTitle.setText(text("noUsableNodes"));
            statusDetail.setText(text("addCustomNodes"));
        } else if (pendingFilter) {
            statusTitle.setText(text("filterPending"));
            statusDetail.setText(text("filterPendingDetail"));
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
        return null;
    }

    private boolean hasOperator(MoneroRpcConnection connection) {
        return !NetworkUtils.isLocalHost(connection.getUri()) && xmrNodes.getAllXmrNodes().stream()
                .anyMatch(node -> node.getOperator() != null && matchesNode(node, connection));
    }

    private boolean hasSubtitle(MoneroNetworkListItem item) {
        return item.getOwner() != null && !hasOperator(item.getConnection());
    }

    private static void fitTable(TableView<?> table, int contentHeight, int cap) {
        double height = Math.min(cap, TABLE_CHROME_HEIGHT + (table.getItems().isEmpty() ? 68 : contentHeight));
        table.setMinHeight(height);
        table.setPrefHeight(height);
        table.setMaxHeight(height);
    }

    private boolean isChangingNodes() {
        return switching || editingNodes || checkingUri != null || connectionService.isReloadingConnections();
    }

    private boolean canSelectSource() {
        return !hasStartupOverride() && !editingNodes && checkingUri == null && (sourceChange != null || !connectionService.isReloadingConnections());
    }

    private boolean hasStartupOverride() {
        return !config.xmrNodes.isEmpty() || !config.xmrNode.isEmpty() &&
                (!NetworkUtils.isLocalHost(config.xmrNode) || !config.ignoreLocalXmrNode);
    }

    // after a startup fallback the startup option no longer selects this session's nodes
    private String startupOverrideKey() {
        if (!connectionService.isFallbackApplied()) return "startupOverride";
        return preferences.getEffectiveNetworkSettings().nodes() == MoneroNodesOption.PUBLIC ? "startupFallbackPublic" : "startupFallbackProvided";
    }

    private String customNodesCsv() {
        String addresses = preferences.getEffectiveNetworkSettings().customNodes();
        return addresses == null ? "" : addresses;
    }

    private void revertSource(MoneroNodesOption option) {
        loading = true;
        source.setValue(option);
        loading = false;
    }

    private void updateSettings() {
        NetworkSettings saved = preferences.getNetworkSettings();
        NetworkSettings effective = preferences.getEffectiveNetworkSettings();
        loading = true;
        if (!pendingCustom) source.setValue(queuedSourceChange != null ? queuedSourceChange.option() : sourceChange != null ? sourceChange.option() : effective.nodes());
        tor.setValue(saved.tor());
        loading = false;
        source.setVisible(!hasStartupOverride()); // the saved choice is not in effect, so only the help text shows
        source.setDisable(!canSelectSource());
        MoneroNodesOption option = source.getValue();
        addRow.setVisible(option == MoneroNodesOption.CUSTOM && !hasStartupOverride() && !switching && !connectionService.isReloadingConnections());
        addInput.setDisable(isChangingNodes());
        addButton.setDisable(isChangingNodes());
        addHint.setVisible(addRow.isVisible());
        remoteNodeLink.setVisible(option == MoneroNodesOption.PUBLIC && !hasStartupOverride());
        sourceHelp.setText(text(hasStartupOverride() ? startupOverrideKey() : option == MoneroNodesOption.CUSTOM ? isPublicBlocked() ? "customHelpTrusted" : "customHelp" :
                option == MoneroNodesOption.PUBLIC ? "publicHelp" : "providedHelp") + " ");
        boolean local = connectionService.isConnectionLocalHost();
        torHelp.setText(text(tor.isDisabled() ? "startupOverride" : local ? "torHelpLocal" : tor.getValue() == UseTorForXmr.AFTER_SYNC ? "torHelpAfter" : tor.getValue() == UseTorForXmr.ON ? "torHelpAlways" : "torHelpNever"));
        torPending.setVisible(saved.tor() != effective.tor());
    }

    private boolean applySource(MoneroNodesOption option, String customNodes) {
        if (!active || !canSelectSource() || !sourceAvailable(option)) {
            if (pendingCustom) revertSource(MoneroNodesOption.CUSTOM);
            refresh();
            return false;
        }
        pendingCustom = false;
        SourceChange change = new SourceChange(option, customNodes, activation);
        if (sourceChange != null) {
            queuedSourceChange = option == sourceChange.option() && customNodes == null ? null : change; // only the latest selection needs to run after the current check
            refresh();
        } else {
            startSourceChange(change);
        }
        return true;
    }

    private void startSourceChange(SourceChange change) {
        // resolve retained addresses after the previous change has succeeded or rolled back
        SourceChange request = new SourceChange(change.option(), change.customNodes() == null ? customNodesCsv() : change.customNodes(), change.activation());
        sourceChange = request;
        switching = true;
        if (nodeMenu != null) nodeMenu.hide();
        refresh();
        ThreadUtils.submitToPool(() -> {
            String error = null;
            try {
                if (!sourceAvailable(request.option())) throw new IllegalStateException(sourceName(request.option()));
                connectionService.setNodeSettings(request.option(), request.customNodes());
            } catch (Exception e) {
                log.warn("Could not switch Monero node source to {}", request.option(), e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String failure = error;
            UserThread.execute(() -> {
                sourceChange = null;
                switching = false;
                manualUri = null;
                probeResults.clear();
                SourceChange next = queuedSourceChange;
                queuedSourceChange = null;
                if (next != null) {
                    startSourceChange(next);
                } else {
                    if (active && activation == request.activation() && failure != null) new Popup().warning(text("switchFailed", sourceName(request.option()), nodeErrorMessage(failure))).show();
                    refresh();
                }
            });
        });
    }

    private record SourceChange(MoneroNodesOption option, String customNodes, long activation) {}

    private boolean isPublicBlocked() {
        return filterManager.getFilter() != null && filterManager.getFilter().isPreventPublicXmrNetwork();
    }

    private boolean hasNodes(MoneroNodesOption option) {
        return option == MoneroNodesOption.CUSTOM || !(option == MoneroNodesOption.PROVIDED ? xmrNodes.getProvidedXmrNodes() : xmrNodes.getPublicXmrNodes()).isEmpty();
    }

    private boolean sourceAvailable(MoneroNodesOption option) {
        return hasNodes(option) && (option != MoneroNodesOption.PUBLIC || !isPublicBlocked());
    }

    private String sourceName(MoneroNodesOption option) {
        String name = text(option == MoneroNodesOption.PROVIDED ? "provided" : option == MoneroNodesOption.PUBLIC ? "public" : "custom");
        return sourceAvailable(option) || !hasNodes(option) ? name : name + " · " + text("blockedByFilter");
    }

    private void applyFilter() {
        if (isPublicBlocked() && queuedSourceChange != null && queuedSourceChange.option() == MoneroNodesOption.PUBLIC) queuedSourceChange = null;
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
        updateSettings();
    }

    private void addNodes() {
        if (!active || isChangingNodes() || hasStartupOverride() || source.getValue() != MoneroNodesOption.CUSTOM) return;
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        Set<String> uris = pendingCustom ? new HashSet<>() : connectionService.getConnections().stream()
                .map(connection -> connection.getUri().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String address : addInput.getText().trim().split("[\\s,]+")) {
            if (address.isEmpty()) continue;
            String error = validateAddress(address);
            if (error == null && !uris.add(nodeUri(address))) error = text("duplicate");
            if (error != null) {
                rejected.add(address);
                errors.add(address + ": " + error);
            } else {
                addresses.add(address);
            }
        }
        if (addresses.isEmpty()) {
            setHint(errors.isEmpty() ? null : String.join("\n", errors));
            return;
        }
        if (pendingCustom) {
            if (applySource(MoneroNodesOption.CUSTOM, String.join(",", addresses))) {
                addInput.setText(String.join(", ", rejected));
                setHint(errors.isEmpty() ? null : String.join("\n", errors));
            }
            return;
        }
        editingNodes = true;
        refresh();
        long generation = activation;
        ThreadUtils.submitToPool(() -> {
            List<MoneroRpcConnection> added = new ArrayList<>();
            for (String address : addresses) {
                try {
                    added.add(connectionService.addCustomNode(address));
                } catch (Exception e) {
                    rejected.add(address);
                    errors.add(address + ": " + nodeErrorMessage(e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
            UserThread.execute(() -> {
                editingNodes = false;
                if (active && activation == generation) {
                    for (MoneroRpcConnection connection : added) checkingUris.add(connection.getUri());
                    addInput.setText(String.join(", ", rejected));
                    setHint(errors.isEmpty() ? null : (added.isEmpty() ? "" : text("added", added.size()) + " ") + String.join("\n", errors));
                }
                refresh();
                if (active && activation == generation) addInput.requestFocus();
            });
            for (MoneroRpcConnection connection : added) probeNode(connection, generation); // capture settings only after the entire paste is saved
        });
    }

    private void probeNode(MoneroRpcConnection connection, long generation) {
        ThreadUtils.submitToPool(() -> {
            MoneroRpcConnection checked = null;
            try {
                checked = connectionService.probeConnection(connection);
                connectionService.connectToNodeIfDisconnected(connection, checked);
            } catch (Exception ignored) {
                // a concurrent edit or an unavailable node can prevent switching
            }
            MoneroRpcConnection result = checked;
            UserThread.execute(() -> {
                if (!active || activation != generation) return;
                checkingUris.remove(connection.getUri());
                MoneroRpcConnection live = connectionService.getConnection(connection.getUri());
                if (result != null && (live == connection || live == result)) probeResults.put(connection.getUri(), result);
                refresh();
            });
        });
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

    private static String nodeErrorMessage(String message) {
        String key = switch (message) {
            case "No usable nodes are configured" -> "noUsableNodes";
            case "Public nodes are blocked by filter" -> "publicNodesBlocked";
            case "Custom nodes are not in use" -> "customNodesNotSelected";
            case "Local node is ignored by a startup option" -> "localNodeIgnored";
            case "Cannot remove the only node" -> "removeLastNode";
            case "Connection service is not running" -> "serviceNotRunning";
            case "Node settings are set by a startup option" -> "startupOverride";
            case "Node configuration has changed" -> "configurationChanged";
            case "Tor is not ready" -> "torNotReady";
            case "Connection cannot be changed" -> "connectionLocked";
            case "Node is unavailable or is not synchronized" -> "nodeUnavailable";
            default -> null;
        };
        return key == null ? message : text(key);
    }

    private void setHint(String error) {
        addHint.setText(error == null ? text("addHint", HavenoUtils.getDefaultMoneroPort()) : error);
        addHint.pseudoClassStateChanged(INVALID, error != null);
        addInput.pseudoClassStateChanged(INVALID, error != null);
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
        connect.setDisable(!isNodeAllowed(item.getConnection()) || isChangingNodes() || checkingUris.contains(item.getConnection().getUri()) || item.isConnected() || connectionService.isFixedConnection());
        connect.setOnAction(event -> connectNow(item));
        MenuItem copy = new MenuItem(text("copyAddress"));
        copy.setOnAction(event -> Utilities.copyToClipboard(item.getAddress()));
        nodeMenu = new ContextMenu(connect, new SeparatorMenuItem(), copy);
        if (source.getValue() == MoneroNodesOption.CUSTOM && !hasStartupOverride() && !pendingCustom) {
            MenuItem remove;
            MoneroNodesOption fallback = Stream.of(MoneroNodesOption.PROVIDED, MoneroNodesOption.PUBLIC)
                    .filter(option -> sourceAvailable(option) && connectionService.hasUsableNodes(option, null)).findFirst().orElse(null);
            boolean onlyNode = connectionService.getConnections().size() <= 1;
            if (onlyNode && fallback == null) {
                Label caption = new Label(text("removeLastNode"));
                caption.getStyleClass().add("network-menu-caption");
                remove = new CustomMenuItem(new VBox(2, new Label(text("remove")), caption), false);
                remove.setDisable(true);
            } else {
                remove = new MenuItem(text("remove"));
                remove.setDisable(isChangingNodes() || checkingUris.contains(item.getConnection().getUri()));
                remove.setOnAction(event -> {
                    if (onlyNode) removeOnlyNode(item, fallback);
                    else removeNode(item);
                });
            }
            nodeMenu.getItems().addAll(new SeparatorMenuItem(), remove);
        }
        ContextMenu menu = nodeMenu;
        menu.setId("network-node-menu");
        Scene scene = anchor.getScene();
        menu.setConsumeAutoHidingEvents(false);
        // JavaFX auto-hide ignores clicks on the owner row, so also dismiss from the page scene
        EventHandler<MouseEvent> dismiss = event -> menu.hide();
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
        if (!active || isChangingNodes() || !isNodeAllowed(item.getConnection())) return;
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
                connectionService.connectToNode(original, checked, () -> active && activation == generation);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String failure = error;
            MoneroRpcConnection result = checked;
            UserThread.execute(() -> {
                if (!active || activation != generation) return;
                checkingUri = null;
                if (result != null && (connectionService.getConnection(original.getUri()) == original || connectionService.getConnection(original.getUri()) == result)) probeResults.put(result.getUri(), result);
                if (failure == null) manualUri = item.getConnection().getUri();
                else if (!"Node configuration has changed".equals(failure)) new Popup().warning(text("connectFailed", item.getAddress(), nodeErrorMessage(failure))).show();
                refresh();
            });
        });
    }

    // the last custom node can only go by switching to another source, so confirm where Haveno connects next
    private void removeOnlyNode(MoneroNetworkListItem item, MoneroNodesOption fallback) {
        boolean usePublic = fallback == MoneroNodesOption.PUBLIC;
        new Popup().information(text(usePublic ? "removeOnlyNodePublic" : "removeOnlyNodeProvided"))
                .actionButtonText(text(usePublic ? "removeAndUsePublic" : "removeAndUseProvided"))
                .onAction(() -> {
                    if (connectionService.getConnections().size() > 1) removeNode(item); // another node was added meanwhile
                    else applySource(fallback, XmrNodes.removeUri(customNodesCsv(), item.getConnection().getUri()));
                })
                .closeButtonText(Res.get("shared.cancel")).show();
    }

    private void removeNode(MoneroNetworkListItem item) {
        if (!active || isChangingNodes() || hasStartupOverride()) return;
        String uri = item.getConnection().getUri();
        editingNodes = true;
        switching = item.isCurrent();
        if (nodeMenu != null) nodeMenu.hide();
        refresh();
        long generation = activation;
        ThreadUtils.submitToPool(() -> {
            String error = null;
            try {
                connectionService.removeCustomNode(uri);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String failure = error;
            UserThread.execute(() -> {
                switching = false;
                editingNodes = false;
                probeResults.remove(uri);
                nodeOrder.remove(uri);
                if (uri.equals(manualUri)) manualUri = null;
                if (active && activation == generation && failure != null) new Popup().warning(text("removeFailed", item.getAddress(), nodeErrorMessage(failure))).show();
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

}
