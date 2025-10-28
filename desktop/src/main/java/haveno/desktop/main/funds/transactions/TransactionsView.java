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

package haveno.desktop.main.funds.transactions;

import com.google.inject.Inject;
import com.googlecode.jcsv.writer.CSVEntryConverter;
import de.jensd.fx.fontawesome.AwesomeIcon;
import haveno.common.UserThread;
import haveno.core.api.XmrConnectionService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.Trade;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AddressWithIconAndDirection;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.list.FilterBox;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.overlays.windows.TxDetailsWindow;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.Comparator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import monero.wallet.model.MoneroWalletListener;

@FxmlView
public class TransactionsView extends ActivatableView<VBox, Void> {

    @FXML
    FilterBox filterBox;
    @FXML
    TableView<TransactionsListItem> tableView;
    @FXML
    TableColumn<TransactionsListItem, TransactionsListItem> dateColumn, detailsColumn, addressColumn, transactionColumn, amountColumn, txFeeColumn, confidenceColumn, memoColumn;
    @FXML
    Label numItems;
    @FXML
    Region spacer;
    @FXML
    AutoTooltipButton exportButton;

    private final DisplayedTransactions displayedTransactions;

    private final ObservableList<TransactionsListItem> observableList = FXCollections.observableArrayList();
    private final FilteredList<TransactionsListItem> filteredList = new FilteredList<>(observableList);
    private final SortedList<TransactionsListItem> sortedList = new SortedList<>(filteredList);

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final TradeDetailsWindow tradeDetailsWindow;
    private final OfferDetailsWindow offerDetailsWindow;
    private final TxDetailsWindow txDetailsWindow;

    private EventHandler<KeyEvent> keyEventEventHandler;
    private Scene scene;

    private final TransactionsUpdater transactionsUpdater = new TransactionsUpdater();

    private class TransactionsUpdater extends MoneroWalletListener {
        @Override
        public void onNewBlock(long height) {
            updateList();
        }
        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
            updateList();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private TransactionsView(XmrWalletService xmrWalletService,
                             P2PService p2PService,
                             XmrConnectionService xmrConnectionService,
                             Preferences preferences,
                             TradeDetailsWindow tradeDetailsWindow,
                             OfferDetailsWindow offerDetailsWindow,
                             TxDetailsWindow txDetailsWindow,
                             DisplayedTransactionsFactory displayedTransactionsFactory) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.tradeDetailsWindow = tradeDetailsWindow;
        this.offerDetailsWindow = offerDetailsWindow;
        this.txDetailsWindow = txDetailsWindow;
        this.displayedTransactions = displayedTransactionsFactory.create();
        updateList();
    }

    @Override
    public void initialize() {
        GUIUtil.applyTableStyle(tableView);
        filterBox.initialize(filteredList, tableView);
        filterBox.setPromptText(Res.get("shared.filter"));

        dateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.dateTime")));
        detailsColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.details")));
        addressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        transactionColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.txId", Res.getBaseCurrencyCode())));
        amountColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amountWithCur", Res.getBaseCurrencyCode())));
        txFeeColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.txFee", Res.getBaseCurrencyCode())));
        confidenceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.confirmations", Res.getBaseCurrencyCode())));
        memoColumn.setGraphic(new AutoTooltipLabel(Res.get("funds.tx.memo")));

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("funds.tx.noTxAvailable")));
        tableView.getStyleClass().add("non-interactive-table");

        setDateColumnCellFactory();
        setDetailsColumnCellFactory();
        setAddressColumnCellFactory();
        setTransactionColumnCellFactory();
        setAmountColumnCellFactory();
        setTxFeeColumnCellFactory();
        setConfidenceColumnCellFactory();
        setMemoColumnCellFactory();

        dateColumn.setComparator(Comparator.comparing(TransactionsListItem::getDate));
        detailsColumn.setComparator((o1, o2) -> {
            String id1 = !o1.getDetails().isEmpty() ? o1.getDetails() :
                    o1.getTradable() != null ? o1.getTradable().getId() : o1.getTxId();
            String id2 = !o2.getDetails().isEmpty() ? o2.getDetails() :
                    o2.getTradable() != null ? o2.getTradable().getId() : o2.getTxId();
            return id1.compareTo(id2);
        });
        addressColumn.setComparator(Comparator.comparing(item -> item.getDirection() + item.getAddressString()));
        transactionColumn.setComparator(Comparator.comparing(TransactionsListItem::getTxId));
        amountColumn.setComparator(Comparator.comparing(TransactionsListItem::getAmount));
        confidenceColumn.setComparator(Comparator.comparingLong(TransactionsListItem::getNumConfirmations));
        memoColumn.setComparator(Comparator.comparing(TransactionsListItem::getMemo, Comparator.nullsLast(Comparator.naturalOrder())));
        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);

        keyEventEventHandler = event -> {
            // unused
        };

        HBox.setHgrow(spacer, Priority.ALWAYS);
        numItems.setId("num-offers");
        numItems.setPadding(new Insets(-5, 0, 0, 10));
        exportButton.updateText(Res.get("shared.exportCSV"));
    }

    @Override
    protected void activate() {
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);
        updateList();
        filterBox.initializeWithCallback(filteredList, tableView, () ->
                numItems.setText(Res.get("shared.numItemsLabel", sortedList.size())));
        filterBox.activate();

        xmrWalletService.addWalletListener(transactionsUpdater);

        scene = root.getScene();
        if (scene != null)
            scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);

        exportButton.setOnAction(event -> {
            final ObservableList<TableColumn<TransactionsListItem, ?>> tableColumns = GUIUtil.getContentColumns(tableView);
            final int reportColumns = tableColumns.size();
            CSVEntryConverter<TransactionsListItem> headerConverter = item -> {
                String[] columns = new String[reportColumns];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = ((AutoTooltipLabel) tableColumns.get(i).getGraphic()).getText();
                return columns;
            };
            CSVEntryConverter<TransactionsListItem> contentConverter = item -> {
                String[] columns = new String[reportColumns];
                columns[0] = item.getDateString();
                columns[1] = item.getDetails();
                columns[2] = item.getDirection() + " " + item.getAddressString();
                columns[3] = item.getTxId();
                columns[4] = item.getAmountStr();
                columns[5] = item.getTxFeeStr();
                columns[6] = String.valueOf(item.getNumConfirmations());
                columns[7] = item.getMemo() == null ? "" : item.getMemo();
                return columns;
            };

            GUIUtil.exportCSV("transactions.csv", headerConverter, contentConverter,
                    new TransactionsListItem(), sortedList, (Stage) root.getScene().getWindow());
        });
    }

    @Override
    protected void deactivate() {
        filterBox.deactivate();
        sortedList.comparatorProperty().unbind();
        xmrWalletService.removeWalletListener(transactionsUpdater);

        if (scene != null)
            scene.removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);

        exportButton.setOnAction(null);
    }

    private void updateList() {
        displayedTransactions.update();
        UserThread.execute((() -> {
            observableList.setAll(displayedTransactions);
        }));
    }

    private void openTxInBlockExplorer(TransactionsListItem item) {
        if (item.getTxId() != null)
            GUIUtil.openWebPage(preferences.getBlockChainExplorer().txUrl + item.getTxId(), false);
    }

    private void openDetailPopup(TransactionsListItem item) {
        if (item.getTradable() instanceof OpenOffer)
            offerDetailsWindow.show(item.getTradable().getOffer());
        else if (item.getTradable() instanceof Trade)
            tradeDetailsWindow.show((Trade) item.getTradable());
    }

    private void openTxDetailPopup(TransactionsListItem item) {
        txDetailsWindow.show(item);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ColumnCellFactories
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        dateColumn.setMaxWidth(200);
        dateColumn.setMinWidth(dateColumn.getMaxWidth());
        dateColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(new AutoTooltipLabel(item.getDateString()));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDetailsColumnCellFactory() {
        detailsColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        detailsColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            private HyperlinkWithIcon hyperlinkWithIcon;

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (item.getDetailsAvailable()) {
                                        hyperlinkWithIcon = new HyperlinkWithIcon(item.getDetails(), AwesomeIcon.INFO_SIGN);
                                        hyperlinkWithIcon.setOnAction(event -> openDetailPopup(item));
                                        hyperlinkWithIcon.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                        setGraphic(hyperlinkWithIcon);
                                        // If details are available its a trade tx and we don't expect any dust attack tx
                                    } else {
                                        setGraphic(new AutoTooltipLabel(item.getDetails()));
                                    }
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

    private void setAddressColumnCellFactory() {
        addressColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));

        addressColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            private AddressWithIconAndDirection field;

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String addressString = item.getAddressString();
                                    field = new AddressWithIconAndDirection(item.getDirection(), addressString,
                                            item.getReceived());
                                    field.setTooltip(new Tooltip(Res.get("tooltip.openBlockchainForAddress", addressString)));
                                    setGraphic(field);
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setTransactionColumnCellFactory() {
        transactionColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        transactionColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon hyperlinkWithIcon;

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                //noinspection Duplicates
                                if (item != null && !empty) {
                                    String transactionId = item.getTxId();
                                    hyperlinkWithIcon = new HyperlinkWithIcon(transactionId, AwesomeIcon.INFO_SIGN);
                                    hyperlinkWithIcon.setOnAction(event -> openTxDetailPopup(item));
                                    hyperlinkWithIcon.setTooltip(new Tooltip(Res.get("txDetailsWindow.headline")));
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

    private void setAmountColumnCellFactory() {
        amountColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        amountColumn.getStyleClass().add("highlight-text");
        amountColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(new AutoTooltipLabel(item.getAmountStr()));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }


    private void setTxFeeColumnCellFactory() {
        txFeeColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        txFeeColumn.getStyleClass().add("highlight-text");
        txFeeColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(new AutoTooltipLabel(item.getTxFeeStr()));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setMemoColumnCellFactory() {
        memoColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        memoColumn.getStyleClass().add("highlight-text");
        memoColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(new AutoTooltipLabel(item.getMemo()));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setConfidenceColumnCellFactory() {
        confidenceColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        confidenceColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<TransactionsListItem, TransactionsListItem> call(TableColumn<TransactionsListItem,
                            TransactionsListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final TransactionsListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(item.getTxConfidenceIndicator());
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }
}

