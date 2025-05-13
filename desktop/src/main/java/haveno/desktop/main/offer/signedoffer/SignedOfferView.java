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

package haveno.desktop.main.offer.signedoffer;

import com.google.inject.Inject;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.offer.SignedOffer;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.AutoTooltipTableColumn;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.list.FilterBox;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import java.util.Comparator;
import java.util.Date;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.Duration;

@FxmlView
public class SignedOfferView extends ActivatableViewAndModel<VBox, SignedOffersViewModel> {

    @FXML
    FilterBox filterBox;
    @FXML
    protected TableView<SignedOfferListItem> tableView;
    @FXML
    TableColumn<SignedOfferListItem, SignedOfferListItem> dateColumn;
    @FXML
    TableColumn<SignedOfferListItem, SignedOfferListItem> traderIdColumn;
    @FXML
    TableColumn<SignedOfferListItem, SignedOfferListItem> offerIdColumn;
    @FXML
    TableColumn<SignedOfferListItem, SignedOfferListItem> reserveTxKeyImages;
    @FXML
    TableColumn<SignedOfferListItem, SignedOfferListItem> makerPenaltyFeeColumn;
    @FXML
    InputTextField filterTextField;
    @FXML
    Label numItems;
    @FXML
    Region footerSpacer;

    private SignedOfferListItem selectedSignedOffer;

    private final XmrWalletService xmrWalletService;

    private ContextMenu contextMenu;

    @Inject
    public SignedOfferView(SignedOffersViewModel model, XmrWalletService xmrWalletService) {
        super(model);
        this.xmrWalletService = xmrWalletService;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Life cycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void initialize() {
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
        FilteredList<SignedOfferListItem> filteredList = new FilteredList<>(model.getList());
        SortedList<SignedOfferListItem> sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);
        filterBox.initialize(filteredList, tableView);
        filterBox.setPromptText(Res.get("filter.prompt.offers"));
        filterBox.activate();

        contextMenu = new ContextMenu();
        MenuItem makerPenalization = new MenuItem(
                Res.get("support.contextmenu.penalize.msg", Res.get("shared.maker").toLowerCase())
        );
        MenuItem copyToClipboard = new MenuItem(Res.get("shared.copyToClipboard"));
        contextMenu.getItems().addAll(makerPenalization, copyToClipboard);

        tableView.setRowFactory(tv -> {
            TableRow<SignedOfferListItem> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> contextMenu.show(row, event.getScreenX(), event.getScreenY()));
            return row;
        });

        copyToClipboard.setOnAction(event -> {
            selectedSignedOffer = tableView.getSelectionModel().getSelectedItem();
            Utilities.copyToClipboard(selectedSignedOffer.getSignedOffer().toJson());
        });

        makerPenalization.setOnAction(event -> {
            selectedSignedOffer = tableView.getSelectionModel().getSelectedItem();
            if(selectedSignedOffer != null) {
                SignedOffer signedOffer = selectedSignedOffer.getSignedOffer();
                new Popup().warning(Res.get("support.prompt.signedOffer.penalty.msg",
                        signedOffer.getOfferId(),
                        HavenoUtils.formatXmr(signedOffer.getPenaltyAmount(), true),
                        HavenoUtils.formatXmr(signedOffer.getReserveTxMinerFee(), true),
                        signedOffer.getReserveTxHash(),
                        signedOffer.getReserveTxKeyImages())
                ).onAction(() -> OfferViewUtil.submitTransactionHex(xmrWalletService, tableView,
                        signedOffer.getReserveTxHex())).show();
            } else {
                new Popup().error(Res.get("support.prompt.signedOffer.error.msg")).show();
            }
        });

        GUIUtil.requestFocus(tableView);
    }

    @Override
    protected void deactivate() {
        filterBox.deactivate();
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

        traderIdColumn = getTraderIdColumn();
        tableView.getColumns().add(traderIdColumn);

        offerIdColumn = getOfferIdColumn();
        tableView.getColumns().add(offerIdColumn);

        makerPenaltyFeeColumn = getMakerPenaltyFeeColumn();
        tableView.getColumns().add(makerPenaltyFeeColumn);

        reserveTxKeyImages = getReserveTxKeyImagesColumn();
        tableView.getColumns().add(reserveTxKeyImages);

        traderIdColumn.setComparator(Comparator.comparing(o -> o.getSignedOffer().getTraderId()));
        offerIdColumn.setComparator(Comparator.comparing(o -> o.getSignedOffer().getOfferId()));
        dateColumn.setComparator(Comparator.comparing(o -> o.getSignedOffer().getTimeStamp()));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);
    }

    private TableColumn<SignedOfferListItem, SignedOfferListItem> getDateColumn() {
        TableColumn<SignedOfferListItem, SignedOfferListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.date")) {
            {
                setMinWidth(180);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOfferListItem, SignedOfferListItem> call(TableColumn<SignedOfferListItem, SignedOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(DisplayUtils.formatDateTime(new Date(item.getSignedOffer().getTimeStamp())));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOfferListItem, SignedOfferListItem> getTraderIdColumn() {
        TableColumn<SignedOfferListItem, SignedOfferListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.traderId")) {
            {
                setMinWidth(110);
            }
        };
        column.setCellValueFactory(signedOffer -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOfferListItem, SignedOfferListItem> call(TableColumn<SignedOfferListItem, SignedOfferListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final SignedOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setText(String.valueOf(item.getSignedOffer().getTraderId()));
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

    private TableColumn<SignedOfferListItem, SignedOfferListItem> getOfferIdColumn() {
        TableColumn<SignedOfferListItem, SignedOfferListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.offerId")) {
            {
                setMinWidth(110);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOfferListItem, SignedOfferListItem> call(TableColumn<SignedOfferListItem, SignedOfferListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final SignedOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setText(String.valueOf(item.getSignedOffer().getOfferId()));
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

    private TableColumn<SignedOfferListItem, SignedOfferListItem> getMakerPenaltyFeeColumn() {
        TableColumn<SignedOfferListItem, SignedOfferListItem> column = new AutoTooltipTableColumn<>(Res.get("support.maker.penalty.fee")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOfferListItem, SignedOfferListItem> call(TableColumn<SignedOfferListItem, SignedOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(HavenoUtils.formatXmr(item.getSignedOffer().getPenaltyAmount(), true));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<SignedOfferListItem, SignedOfferListItem> getReserveTxKeyImagesColumn() {
        TableColumn<SignedOfferListItem, SignedOfferListItem> column = new AutoTooltipTableColumn<>(Res.get("support.txKeyImages")) {
            {
                setMinWidth(160);
            }
        };
        column.setCellValueFactory((signedOffer) -> new ReadOnlyObjectWrapper<>(signedOffer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<SignedOfferListItem, SignedOfferListItem> call(TableColumn<SignedOfferListItem, SignedOfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final SignedOfferListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(item.getSignedOffer().getReserveTxKeyImages().toString());
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }
}
