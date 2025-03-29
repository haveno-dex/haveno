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

package haveno.desktop.main.portfolio.openoffer;

import com.google.inject.Inject;
import com.googlecode.jcsv.writer.CSVEntryConverter;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.AutoTooltipSlideToggleButton;
import haveno.desktop.components.AutoTooltipTableColumn;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.withdrawal.WithdrawalView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.presentation.PortfolioUtil;

import static haveno.desktop.util.FormBuilder.getRegularIconButton;

import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.jetbrains.annotations.NotNull;

@FxmlView
public class OpenOffersView extends ActivatableViewAndModel<VBox, OpenOffersViewModel> {

    @FXML
    TableView<OpenOfferListItem> tableView;
    @FXML
    TableColumn<OpenOfferListItem, OpenOfferListItem> priceColumn, deviationColumn, amountColumn, volumeColumn,
            marketColumn, directionColumn, dateColumn, offerIdColumn, deactivateItemColumn, makerReserveTxIdColumn,
            removeItemColumn, editItemColumn, triggerPriceColumn, triggerIconColumn, paymentMethodColumn, duplicateItemColumn,
            cloneItemColumn;
    @FXML
    HBox searchBox;
    @FXML
    AutoTooltipLabel filterLabel;
    @FXML
    InputTextField filterTextField;
    @FXML
    Pane searchBoxSpacer;
    @FXML
    Label numItems;
    @FXML
    Region footerSpacer;
    @FXML
    AutoTooltipButton exportButton;
    @FXML
    AutoTooltipSlideToggleButton selectToggleButton;

    private final Navigation navigation;
    private final OfferDetailsWindow offerDetailsWindow;
    private SortedList<OpenOfferListItem> sortedList;
    private FilteredList<OpenOfferListItem> filteredList;
    private ChangeListener<String> filterTextFieldListener;
    private final OpenOfferManager openOfferManager;
    private PortfolioView.EditOpenOfferHandler editOpenOfferHandler;
    private PortfolioView.CloneOpenOfferHandler cloneOpenOfferHandler;
    private ChangeListener<Number> widthListener;
    private ListChangeListener<OpenOfferListItem> sortedListeChangedListener;

    private Map<String, ChangeListener<OpenOffer.State>> offerStateChangeListeners = new HashMap<String, ChangeListener<OpenOffer.State>>();

    @Inject
    public OpenOffersView(OpenOffersViewModel model,
                          OpenOfferManager openOfferManager,
                          Navigation navigation,
                          OfferDetailsWindow offerDetailsWindow) {
        super(model);
        this.navigation = navigation;
        this.offerDetailsWindow = offerDetailsWindow;
        this.openOfferManager = openOfferManager;
    }

    @Override
    public void initialize() {
        widthListener = (observable, oldValue, newValue) -> onWidthChange((double) newValue);
        makerReserveTxIdColumn.setGraphic(new AutoTooltipLabel(Res.get("openOffer.header.groupId")));
        paymentMethodColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.paymentMethod")));
        priceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.price")));
        deviationColumn.setGraphic(new AutoTooltipTableColumn<>(Res.get("shared.deviation"),
                Res.get("portfolio.closedTrades.deviation.help")).getGraphic());
        amountColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.XMRMinMax")));
        volumeColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amountMinMax")));
        marketColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.market")));
        directionColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.offerType")));
        dateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.dateTime")));
        offerIdColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.offerId")));
        triggerPriceColumn.setGraphic(new AutoTooltipLabel(Res.get("openOffer.header.triggerPrice")));
        deactivateItemColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.enabled")));
        editItemColumn.setGraphic(new AutoTooltipLabel(""));
        duplicateItemColumn.setText("");
        cloneItemColumn.setText("");
        removeItemColumn.setGraphic(new AutoTooltipLabel(""));

        setOfferIdColumnCellFactory();
        setMakerReserveTxIdColumnCellFactory();
        setDirectionColumnCellFactory();
        setMarketColumnCellFactory();
        setPriceColumnCellFactory();
        setDeviationColumnCellFactory();
        setAmountColumnCellFactory();
        setVolumeColumnCellFactory();
        setPaymentMethodColumnCellFactory();
        setDateColumnCellFactory();
        setDeactivateColumnCellFactory();
        setEditColumnCellFactory();
        setTriggerIconColumnCellFactory();
        setTriggerPriceColumnCellFactory();
        setDuplicateColumnCellFactory();
        setCloneColumnCellFactory();
        setRemoveColumnCellFactory();

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noItems", Res.get("shared.openOffers"))));

        offerIdColumn.setComparator(Comparator.comparing(o -> o.getOffer().getId()));
        makerReserveTxIdColumn.setComparator(Comparator.comparing(o -> o.getOpenOffer().getReserveTxHash()));
        directionColumn.setComparator(Comparator.comparing(o -> o.getOffer().getDirection()));
        marketColumn.setComparator(Comparator.comparing(model::getMarketLabel));
        amountColumn.setComparator(Comparator.comparing(o -> o.getOffer().getAmount()));
        priceColumn.setComparator(Comparator.comparing(o -> o.getOffer().getPrice(), Comparator.nullsFirst(Comparator.naturalOrder())));
        deviationColumn.setComparator(Comparator.comparing(model::getPriceDeviationAsDouble, Comparator.nullsFirst(Comparator.naturalOrder())));
        triggerPriceColumn.setComparator(Comparator.comparing(o -> o.getOpenOffer().getTriggerPrice(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        volumeColumn.setComparator(Comparator.comparing(o -> o.getOffer().getVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
        dateColumn.setComparator(Comparator.comparing(o -> o.getOffer().getDate()));
        paymentMethodColumn.setComparator(Comparator.comparing(o -> Res.get(o.getOffer().getPaymentMethod().getId())));

        dateColumn.setSortType(TableColumn.SortType.ASCENDING);
        tableView.getSortOrder().add(dateColumn);

        tableView.setRowFactory(
                tableView -> {
                    final TableRow<OpenOfferListItem> row = new TableRow<>();
                    final ContextMenu rowMenu = new ContextMenu();

                    MenuItem duplicateOfferMenuItem = new MenuItem(Res.get("portfolio.tab.duplicateOffer"));
                    duplicateOfferMenuItem.setOnAction((event) -> onDuplicateOffer(row.getItem()));
                    rowMenu.getItems().add(duplicateOfferMenuItem);

                    MenuItem cloneOfferMenuItem = new MenuItem(Res.get("portfolio.tab.cloneOpenOffer"));
                    cloneOfferMenuItem.setOnAction((event) -> onCloneOffer(row.getItem()));
                    rowMenu.getItems().add(cloneOfferMenuItem);

                    row.contextMenuProperty().bind(
                            Bindings.when(Bindings.isNotNull(row.itemProperty()))
                                    .then(rowMenu)
                                    .otherwise((ContextMenu) null));
                    return row;
                });

        filterLabel.setText(Res.get("shared.filter"));
        HBox.setMargin(filterLabel, new Insets(5, 0, 0, 10));
        filterTextFieldListener = (observable, oldValue, newValue) -> applyFilteredListPredicate(filterTextField.getText());
        searchBox.setSpacing(5);
        HBox.setHgrow(searchBoxSpacer, Priority.ALWAYS);

        selectToggleButton.setPadding(new Insets(0, 60, -20, 0));
        selectToggleButton.setText(Res.get("shared.enabled"));
        selectToggleButton.setDisable(true);

        numItems.setId("num-offers");
        numItems.setPadding(new Insets(-5, 0, 0, 10));
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox.setMargin(exportButton, new Insets(0, 10, 0, 0));
        exportButton.updateText(Res.get("shared.exportCSV"));

        sortedListeChangedListener = c -> {
            c.next();
            if (c.wasAdded() || c.wasRemoved()) {
                updateNumberOfOffers();
                updateMakerReserveTxIdColumnVisibility();
                updateTriggerColumnVisibility();
            }
        };
    }

    @Override
    protected void activate() {
        filteredList = new FilteredList<>(model.getList());
        sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        sortedList.addListener(sortedListeChangedListener);
        tableView.setItems(sortedList);

        updateMakerReserveTxIdColumnVisibility();
        updateTriggerColumnVisibility();
        updateSelectToggleButtonState();

        selectToggleButton.setOnAction(event -> {
            if (model.isBootstrappedOrShowPopup()) {
                if (selectToggleButton.isSelected()) {
                    sortedList.forEach(openOfferListItem -> onActivateOpenOffer(openOfferListItem.getOpenOffer()));
                } else {
                    sortedList.forEach(openOfferListItem -> onDeactivateOpenOffer(openOfferListItem.getOpenOffer()));
                }
            }
            tableView.refresh();
        });

        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));
        exportButton.setOnAction(event -> {
            ObservableList<TableColumn<OpenOfferListItem, ?>> tableColumns = tableView.getColumns();
            int reportColumns = tableColumns.size() - 3;    // CSV report excludes the last columns (icons)
            CSVEntryConverter<OpenOfferListItem> headerConverter = item -> {
                String[] columns = new String[reportColumns];
                for (int i = 0; i < columns.length; i++) {
                    Node graphic = tableColumns.get(i).getGraphic();
                    if (graphic instanceof AutoTooltipLabel) {
                        columns[i] = ((AutoTooltipLabel) graphic).getText();
                    } else if (graphic instanceof HBox) {
                        // Deviation has a Hbox with AutoTooltipLabel as first child in header
                        columns[i] = ((AutoTooltipLabel) ((Parent) graphic).getChildrenUnmodifiable().get(0)).getText();
                    } else {
                        // Not expected
                        columns[i] = "N/A";
                    }
                }
                return columns;
            };
            CSVEntryConverter<OpenOfferListItem> contentConverter = item -> {
                String[] columns = new String[reportColumns];
                columns[0] = model.getOfferId(item);
                columns[1] = model.getMakerReserveTxId(item);
                columns[2] = model.getDate(item);
                columns[3] = model.getMarketLabel(item);
                columns[4] = model.getPrice(item);
                columns[5] = model.getPriceDeviation(item);
                columns[6] = model.getTriggerPrice(item);
                columns[7] = model.getAmount(item);
                columns[8] = model.getVolume(item);
                columns[9] = model.getPaymentMethod(item);
                columns[10] = model.getDirectionLabel(item);
                columns[11] = String.valueOf(!item.getOpenOffer().isDeactivated());
                return columns;
            };

            GUIUtil.exportCSV("openOffers.csv",
                    headerConverter,
                    contentConverter,
                    new OpenOfferListItem(),
                    sortedList,
                    (Stage) root.getScene().getWindow());
        });

        filterTextField.textProperty().addListener(filterTextFieldListener);
        applyFilteredListPredicate(filterTextField.getText());

        root.widthProperty().addListener(widthListener);
        onWidthChange(root.getWidth());
    }

    private void updateNumberOfOffers() {
        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));
    }

    private void updateMakerReserveTxIdColumnVisibility() {
        makerReserveTxIdColumn.setVisible(model.dataModel.getList().stream()
                .collect(Collectors.groupingBy(OpenOfferListItem::getMakerReserveTxId, Collectors.counting()))
                .values().stream().anyMatch(i -> i > 1));
    }

    private void updateTriggerColumnVisibility() {
        triggerIconColumn.setVisible(model.dataModel.getList().stream()
                .mapToLong(item -> item.getOpenOffer().getTriggerPrice())
                .sum() > 0);
    }

    @Override
    protected void deactivate() {
        sortedList.comparatorProperty().unbind();
        sortedList.removeListener(sortedListeChangedListener);
        exportButton.setOnAction(null);

        filterTextField.textProperty().removeListener(filterTextFieldListener);
        root.widthProperty().removeListener(widthListener);
    }

    private void refresh() {
        tableView.refresh();
        updateSelectToggleButtonState();
    }

    private void updateSelectToggleButtonState() {
        List<OpenOfferListItem> availableItems = sortedList.stream()
                .filter(openOfferListItem -> !openOfferListItem.getOpenOffer().isPending())
                .collect(Collectors.toList());
        if (availableItems.size() == 0) {
            selectToggleButton.setDisable(true);
            selectToggleButton.setSelected(false);
        } else {
            selectToggleButton.setDisable(false);
            long numDeactivated = availableItems.stream()
                    .filter(openOfferListItem -> openOfferListItem.getOpenOffer().isDeactivated())
                    .count();
            if (numDeactivated == availableItems.size()) {
                selectToggleButton.setSelected(false);
            } else if (numDeactivated == 0) {
                selectToggleButton.setSelected(true);
            }
        }
    }

    private void applyFilteredListPredicate(String filterString) {
        filteredList.setPredicate(item -> {
            if (filterString.isEmpty())
                return true;

            Offer offer = item.getOpenOffer().getOffer();
            if (offer.getId().contains(filterString)) {
                return true;
            }
            if (model.getDate(item).contains(filterString)) {
                return true;
            }
            if (model.getMarketLabel(item).contains(filterString)) {
                return true;
            }
            if (model.getPrice(item).contains(filterString)) {
                return true;
            }
            if (model.getPriceDeviation(item).contains(filterString)) {
                return true;
            }
            if (model.getPaymentMethod(item).contains(filterString)) {
                return true;
            }
            if (model.getVolume(item).contains(filterString)) {
                return true;
            }
            if (model.getAmount(item).contains(filterString)) {
                return true;
            }
            if (model.getDirectionLabel(item).contains(filterString)) {
                return true;
            }
            return false;
        });
    }

    private void onWidthChange(double width) {
        triggerPriceColumn.setVisible(width > 1300);
    }

    private void onDeactivateOpenOffer(OpenOffer openOffer) {
        if (model.isBootstrappedOrShowPopup()) {
            model.onDeactivateOpenOffer(openOffer,
                    () -> log.debug("Deactivate offer was successful"),
                    (message) -> {
                        log.error(message);
                        new Popup().warning(message).show();
                    });
            updateSelectToggleButtonState();
        }
    }

    private void onActivateOpenOffer(OpenOffer openOffer) {
        if (model.isBootstrappedOrShowPopup() && !model.dataModel.isTriggered(openOffer)) {
            model.onActivateOpenOffer(openOffer,
                    () -> log.debug("Activate offer was successful"),
                    (message) -> {
                        log.error(message);
                        new Popup().warning(Res.get("offerbook.activateOffer.failed", message)).show();
                    });
            updateSelectToggleButtonState();
        }
    }

    private void onRemoveOpenOffer(OpenOffer openOffer) {
        if (model.isBootstrappedOrShowPopup()) {
            String key = "RemoveOfferWarning";
            if (DontShowAgainLookup.showAgain(key)) {
                new Popup().warning(Res.get("popup.warning.removeOffer"))
                        .actionButtonText(Res.get("shared.removeOffer"))
                        .onAction(() -> doRemoveOpenOffer(openOffer))
                        .closeButtonText(Res.get("shared.dontRemoveOffer"))
                        .dontShowAgainId(key)
                        .show();
            } else {
                doRemoveOpenOffer(openOffer);
            }
            updateSelectToggleButtonState();
        }
    }

    private void doRemoveOpenOffer(OpenOffer openOffer) {
        boolean hasSharedReserveTx = openOfferManager.hasSharedReserveTx(openOffer.getId());
        model.onRemoveOpenOffer(openOffer,
                () -> {
                    log.debug("Remove offer was successful");

                    tableView.refresh();

                    // We do not show the popup if it's a cloned offer with shared maker reserve tx
                    if (hasSharedReserveTx) {
                        return;
                    }

                    String key = "WithdrawFundsAfterRemoveOfferInfo";
                    if (DontShowAgainLookup.showAgain(key)) {
                        new Popup().instruction(Res.get("offerbook.withdrawFundsHint", Res.get("navigation.funds.availableForWithdrawal")))
                                .actionButtonTextWithGoTo("navigation.funds.availableForWithdrawal")
                                .onAction(() -> navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class))
                                .dontShowAgainId(key)
                                .show();
                    }
                },
                (message) -> {
                    log.error(message);
                    new Popup().warning(Res.get("offerbook.removeOffer.failed", message)).show();
                });
    }

    private void onEditOpenOffer(OpenOffer openOffer) {
        if (model.isBootstrappedOrShowPopup()) {
            editOpenOfferHandler.onEditOpenOffer(openOffer);
        }
    }

    private void onDuplicateOffer(OpenOfferListItem item) {
        if (item == null || item.getOffer().getOfferPayload() == null) {
            return;
        }
        if (model.isBootstrappedOrShowPopup()) {
            PortfolioUtil.duplicateOffer(navigation, item.getOffer().getOfferPayload());
        }
    }

    private void onCloneOffer(OpenOfferListItem item) {
        if (item == null) {
            return;
        }
        if (model.isBootstrappedOrShowPopup()) {
            String key = "clonedOfferInfo";
            if (DontShowAgainLookup.showAgain(key)) {
                new Popup().backgroundInfo(Res.get("offerbook.clonedOffer.info"))
                        .useIUnderstandButton()
                        .dontShowAgainId(key)
                        .onClose(() -> doCloneOffer(item))
                        .show();
            } else {
                doCloneOffer(item);
            }
        }   
    }

    private void doCloneOffer(OpenOfferListItem item) {
        OpenOffer openOffer = item.getOpenOffer();
        if (openOffer == null || openOffer.getOffer() == null || openOffer.getOffer().getOfferPayload() == null) {
            return;
        }
        cloneOpenOfferHandler.onCloneOpenOffer(openOffer);
    }

    private void setOfferIdColumnCellFactory() {
        offerIdColumn.setCellValueFactory((openOfferListItem) -> new ReadOnlyObjectWrapper<>(openOfferListItem.getValue()));
        offerIdColumn.getStyleClass().addAll("number-column", "first-column");
        offerIdColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem,
                            OpenOfferListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon hyperlinkWithIcon;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    hyperlinkWithIcon = new HyperlinkWithIcon(item.getOffer().getShortId());
                                    if (model.isDeactivated(item)) {
                                        // getStyleClass().add("offer-disabled"); does not work with hyperlinkWithIcon;-(
                                        hyperlinkWithIcon.setStyle("-fx-text-fill: -bs-color-gray-3;");
                                        hyperlinkWithIcon.getIcon().setOpacity(0.2);
                                    }
                                    hyperlinkWithIcon.setOnAction(event -> {
                                        offerDetailsWindow.show(item.getOffer());
                                    });

                                    hyperlinkWithIcon.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                    setGraphic(hyperlinkWithIcon);
                                } else {
                                    setGraphic(null);
                                    if (hyperlinkWithIcon != null)
                                        hyperlinkWithIcon.setOnAction(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setMakerReserveTxIdColumnCellFactory() {
        makerReserveTxIdColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        makerReserveTxIdColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {

                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");
                                if (item != null) {
                                    Label label;
                                    Text icon;
                                    if (openOfferManager.hasSharedReserveTx(item.getOpenOffer().getId())) {
                                        label = new Label(item.getOpenOffer().getReserveTxHash().substring(0, 5));
                                        icon = FormBuilder.getRegularIconForLabel(MaterialDesignIcon.LINK, label, "icon");
                                        icon.setVisible(true);
                                        setTooltip(new Tooltip(Res.get("offerbook.clonedOffer.tooltip", item.getOpenOffer().getReserveTxHash())));
                                    } else {
                                        label = new Label("");
                                        icon = FormBuilder.getRegularIconForLabel(MaterialDesignIcon.LINK_OFF, label, "icon");
                                        icon.setVisible(false);
                                        setTooltip(new Tooltip(Res.get("offerbook.nonClonedOffer.tooltip", item.getOpenOffer().getReserveTxHash())));
                                    }

                                    if (model.isDeactivated(item)) {
                                        getStyleClass().add("offer-disabled");
                                        icon.setOpacity(0.2);
                                    }
                                    setGraphic(label);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((openOfferListItem) -> new ReadOnlyObjectWrapper<>(openOfferListItem.getValue()));
        dateColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");
                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getDate(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setAmountColumnCellFactory() {
        amountColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        amountColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getAmount(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setPriceColumnCellFactory() {
        priceColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        priceColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getPrice(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDeviationColumnCellFactory() {
        deviationColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        deviationColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    AutoTooltipLabel autoTooltipLabel = new AutoTooltipLabel(model.getPriceDeviation(item));
                                    autoTooltipLabel.setOpacity(item.getOffer().isUseMarketBasedPrice() ? 1 : 0.4);
                                    setGraphic(autoTooltipLabel);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setTriggerPriceColumnCellFactory() {
        triggerPriceColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        triggerPriceColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");
                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getTriggerPrice(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setVolumeColumnCellFactory() {
        volumeColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        volumeColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getVolume(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setPaymentMethodColumnCellFactory() {
        paymentMethodColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        paymentMethodColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getPaymentMethod(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDirectionColumnCellFactory() {
        directionColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        directionColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getDirectionLabel(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setMarketColumnCellFactory() {
        marketColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        marketColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(
                            TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                getStyleClass().removeAll("offer-disabled");

                                if (item != null) {
                                    if (model.isDeactivated(item)) getStyleClass().add("offer-disabled");
                                    setGraphic(new AutoTooltipLabel(model.getMarketLabel(item)));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDeactivateColumnCellFactory() {
        deactivateItemColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        deactivateItemColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            final ImageView iconView = new ImageView();
                            AutoTooltipSlideToggleButton checkBox;

                            private void updateState(@NotNull OpenOffer openOffer) {
                                if (checkBox != null) checkBox.setSelected(openOffer.getState() == OpenOffer.State.AVAILABLE);
                            }

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    OpenOffer openOffer = item.getOpenOffer();

                                    // refresh on state change
                                    if (offerStateChangeListeners.containsKey(openOffer.getId())) {
                                        openOffer.stateProperty().removeListener(offerStateChangeListeners.get(openOffer.getId()));
                                        offerStateChangeListeners.remove(openOffer.getId());
                                    }
                                    ChangeListener<OpenOffer.State> listener = (observable, oldValue, newValue) -> { if (oldValue != newValue) refresh(); };
                                    offerStateChangeListeners.put(openOffer.getId(), listener);
                                    openOffer.stateProperty().addListener(listener);

                                    if (openOffer.getState() == OpenOffer.State.PENDING) {
                                        setGraphic(new AutoTooltipLabel(Res.get("shared.pending")));
                                        return;
                                    }

                                    if (checkBox == null) {
                                        checkBox = new AutoTooltipSlideToggleButton();
                                        checkBox.setPadding(new Insets(-7, 0, -7, 0));
                                        checkBox.setGraphic(iconView);
                                    }
                                    checkBox.setDisable(model.dataModel.isTriggered(openOffer));
                                    checkBox.setOnAction(event -> {
                                        if (openOffer.isDeactivated()) {
                                            onActivateOpenOffer(openOffer);
                                        } else {
                                            onDeactivateOpenOffer(openOffer);
                                        }
                                        updateState(openOffer);
                                        tableView.refresh();
                                    });
                                    updateState(openOffer);
                                    setGraphic(checkBox);
                                } else {
                                    setGraphic(null);
                                    if (checkBox != null) {
                                        checkBox.setOnAction(null);
                                        checkBox = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    private void setRemoveColumnCellFactory() {
        removeItemColumn.getStyleClass().addAll("last-column", "avatar-column");
        removeItemColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        removeItemColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = getRegularIconButton(MaterialDesignIcon.DELETE_FOREVER, "delete");
                                        button.setTooltip(new Tooltip(Res.get("shared.removeOffer")));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(event -> onRemoveOpenOffer(item.getOpenOffer()));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    private void setDuplicateColumnCellFactory() {
        duplicateItemColumn.getStyleClass().add("avatar-column");
        duplicateItemColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        duplicateItemColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = getRegularIconButton(MaterialDesignIcon.CONTENT_COPY);
                                        button.setTooltip(new Tooltip(Res.get("shared.duplicateOffer")));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(event -> onDuplicateOffer(item));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    private void setCloneColumnCellFactory() {
        cloneItemColumn.getStyleClass().add("avatar-column");
        cloneItemColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        cloneItemColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = getRegularIconButton(MaterialDesignIcon.BOX_SHADOW);
                                        button.setTooltip(new Tooltip(Res.get("offerbook.cloneOffer")));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(event -> onCloneOffer(item));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    private void setTriggerIconColumnCellFactory() {
        triggerIconColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        triggerIconColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = getRegularIconButton(MaterialDesignIcon.SHIELD_HALF_FULL);
                                        boolean triggerPriceSet = item.getOpenOffer().getTriggerPrice() > 0;
                                        button.setVisible(triggerPriceSet);

                                        if (model.dataModel.isTriggered(item.getOpenOffer())) {
                                            button.getGraphic().getStyleClass().add("warning");
                                            button.setTooltip(new Tooltip(Res.get("openOffer.triggered")));
                                        } else {
                                            button.getGraphic().getStyleClass().remove("warning");
                                            button.setTooltip(new Tooltip(Res.get("openOffer.triggerPrice", model.getTriggerPrice(item))));
                                        }
                                        setGraphic(button);
                                    }
                                    button.setOnAction(event -> onEditOpenOffer(item.getOpenOffer()));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    private void setEditColumnCellFactory() {
        editItemColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        editItemColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OpenOfferListItem, OpenOfferListItem> call(TableColumn<OpenOfferListItem, OpenOfferListItem> column) {
                        return new TableCell<>() {
                            Button button;

                            @Override
                            public void updateItem(final OpenOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (button == null) {
                                        button = getRegularIconButton(MaterialDesignIcon.PENCIL);
                                        button.setTooltip(new Tooltip(Res.get("shared.editOffer")));
                                        setGraphic(button);
                                    }
                                    button.setOnAction(event -> onEditOpenOffer(item.getOpenOffer()));
                                } else {
                                    setGraphic(null);
                                    if (button != null) {
                                        button.setOnAction(null);
                                        button = null;
                                    }
                                }
                            }
                        };
                    }
                });
    }

    public void setEditOpenOfferHandler(PortfolioView.EditOpenOfferHandler editOpenOfferHandler) {
        this.editOpenOfferHandler = editOpenOfferHandler;
    }

    public void setCloneOpenOfferHandler(PortfolioView.CloneOpenOfferHandler cloneOpenOfferHandler) {
        this.cloneOpenOfferHandler = cloneOpenOfferHandler;
    }
}

