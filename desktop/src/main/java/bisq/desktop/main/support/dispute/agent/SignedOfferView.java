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

package bisq.desktop.main.support.dispute.agent;

import bisq.desktop.common.view.ActivatableView;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.AutoTooltipLabel;
import bisq.desktop.components.AutoTooltipTableColumn;
import bisq.desktop.components.HyperlinkWithIcon;
import bisq.desktop.components.InputTextField;
import bisq.desktop.main.offer.OfferViewUtil;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.main.overlays.windows.OfferDetailsWindow;
import bisq.desktop.util.DisplayUtils;
import bisq.desktop.util.GUIUtil;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.offer.SignedOffer;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;


import javax.inject.Inject;

import javafx.fxml.FXML;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;

import javafx.beans.property.ReadOnlyObjectWrapper;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;

import javafx.util.Callback;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.Date;

@FxmlView
public class SignedOfferView extends ActivatableView<VBox, Void> {

    private final OpenOfferManager openOfferManager;

    @FXML
    protected TableView<SignedOffer> tableView;
    @FXML
    TableColumn<SignedOffer, SignedOffer> dateColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> offerIdColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> reserveTxHashColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> reserveTxHexColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> reserveTxKeyImages;
    @FXML
    TableColumn<SignedOffer, SignedOffer> arbitratorSignatureColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> reserveTxMinerFeeColumn;
    @FXML
    TableColumn<SignedOffer, SignedOffer> makerTradeFeeColumn;
    @FXML
    InputTextField filterTextField;
    @FXML
    Label numItems;
    @FXML
    Region footerSpacer;

    private SignedOffer selectedSignedOffer;

    private XmrWalletService xmrWalletService;

    private TradeManager tradeManager;

    private ContextMenu contextMenu;

    private OfferDetailsWindow offerDetailsWindow;

    @Inject
    public SignedOfferView(OpenOfferManager openOfferManager, XmrWalletService xmrWalletService,
                           TradeManager tradeManager, OfferDetailsWindow offerDetailsWindow) {
        this.openOfferManager = openOfferManager;
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.offerDetailsWindow = offerDetailsWindow;
    }

    private Offer fetchOffer(SignedOffer signedOffer) {
        Trade trade = tradeManager.getTrade(signedOffer.getOfferId());

        return trade != null ? trade.getOffer() : null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Life cycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void initialize() {
        Label label = new AutoTooltipLabel(Res.get("support.filter"));
        HBox.setMargin(label, new Insets(5, 0, 0, 0));
        HBox.setHgrow(label, Priority.NEVER);

        filterTextField = new InputTextField();
        Tooltip tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(100));
        tooltip.setShowDuration(Duration.seconds(10));
        filterTextField.setTooltip(tooltip);
        HBox.setHgrow(filterTextField, Priority.NEVER);

        filterTextField.setText("open");

        setupTable();
    }
    @Override
    protected void activate() {
        super.activate();

        ObservableList<SignedOffer> observableList = FXCollections.observableArrayList();
        observableList.addAll(openOfferManager.getSignedOffers());

        SortedList<SignedOffer> sortedList = new SortedList<>(observableList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        sortedList.addListener((ListChangeListener<SignedOffer>) c -> tableView.refresh());
        tableView.setItems(sortedList);

        contextMenu = new ContextMenu();
        MenuItem item1 = new MenuItem(Res.get("support.contextmenu.penalize.msg",
                Res.get("shared.maker")));
        contextMenu.getItems().addAll(item1);

        tableView.setRowFactory(tv -> {
            TableRow<SignedOffer> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                contextMenu.show(row, event.getScreenX(), event.getScreenY());
            });
            return row;
        });

        item1.setOnAction(event -> {
            selectedSignedOffer = tableView.getSelectionModel().getSelectedItem();
            if(selectedSignedOffer != null) {
                new Popup().warning(Res.get("support.prompt.signedOffer.penalty.msg",
                        selectedSignedOffer.getOfferId(),
                        HavenoUtils.formatToXmr(HavenoUtils.centinerosToCoin(selectedSignedOffer.getMakerTradeFee())),
                        HavenoUtils.formatToXmr(HavenoUtils.centinerosToCoin(selectedSignedOffer.getReserveTxMinerFee())),
                        selectedSignedOffer.getReserveTxHash(),
                        selectedSignedOffer.getReserveTxKeyImages())
                ).onAction(() -> OfferViewUtil.submitTransactionHex(xmrWalletService, tableView,
                        selectedSignedOffer.getReserveTxHex())).show();
            } else {
                new Popup().error(Res.get("support.prompt.signedOffer.error.msg")).show();
            }
        });

        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));

        GUIUtil.requestFocus(tableView);
    }

    @Override
    protected void deactivate() {
        super.deactivate();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // SignedOfferView
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void setupTable() {
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new AutoTooltipLabel(Res.get("support.noTickets"));
        placeholder.setWrapText(true);
        tableView.setPlaceholder(placeholder);
        tableView.getSelectionModel().clearSelection();

        dateColumn = getDateColumn();
        tableView.getColumns().add(dateColumn);

        offerIdColumn = getOfferIdColumn();
        tableView.getColumns().add(offerIdColumn);

        reserveTxHashColumn = getReserveTxHashColumn();
        tableView.getColumns().add(reserveTxHashColumn);

        reserveTxHexColumn = getReserveTxHexColumn();
        tableView.getColumns().add(reserveTxHexColumn);

        reserveTxKeyImages = getReserveTxKeyImagesColumn();
        tableView.getColumns().add(reserveTxKeyImages);

        arbitratorSignatureColumn = getArbitratorSignatureColumn();
        tableView.getColumns().add(arbitratorSignatureColumn);

        makerTradeFeeColumn = getMakerTradeFeeColumn();
        tableView.getColumns().add(makerTradeFeeColumn);

        reserveTxMinerFeeColumn = getReserveTxMinerFeeColumn();
        tableView.getColumns().add(reserveTxMinerFeeColumn);

        offerIdColumn.setComparator(Comparator.comparing(SignedOffer::getOfferId));
        dateColumn.setComparator(Comparator.comparing(SignedOffer::getTimeStamp));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);
    }

    private TableColumn<SignedOffer, SignedOffer> getDateColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("shared.date")) {
            {
                setMinWidth(180);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(DisplayUtils.formatDateTime(new Date(item.getTimeStamp())));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getOfferIdColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("shared.offerId")) {
            {
                setMinWidth(110);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    Offer offer = fetchOffer(item);

                                    if (offer != null) {
                                        field = new HyperlinkWithIcon(item.getOfferId());
                                        field.setMouseTransparent(false);
                                        field.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                        field.setOnAction(event -> offerDetailsWindow.show(offer));
                                    } else {
                                        setText(item.getOfferId());
                                    }
                                    setGraphic(field);
                                } else {
                                    setGraphic(null);
                                    setText("");
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getReserveTxHashColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.txHash")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(item.getReserveTxHash());
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getReserveTxHexColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.txHex")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(item.getReserveTxHex());
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getReserveTxKeyImagesColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.txKeyImages")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(item.getReserveTxKeyImages().toString());
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getArbitratorSignatureColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.signature")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(item.getArbitratorSignature());
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getMakerTradeFeeColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.maker.trade.fee")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(HavenoUtils.formatToXmr(HavenoUtils.centinerosToCoin(item.getMakerTradeFee())));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOffer, SignedOffer> getReserveTxMinerFeeColumn() {
        TableColumn<SignedOffer, SignedOffer> column = new AutoTooltipTableColumn<>(Res.get("support.tx.miner.fee")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOffer, SignedOffer> call(TableColumn<SignedOffer, SignedOffer> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOffer item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(HavenoUtils.formatToXmr(HavenoUtils.centinerosToCoin(item.getReserveTxMinerFee())));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }
}


