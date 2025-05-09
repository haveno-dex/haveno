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

package haveno.desktop.main.funds.locked;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.googlecode.jcsv.writer.CSVEntryConverter;
import de.jensd.fx.fontawesome.AwesomeIcon;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.BalanceListener;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.util.GUIUtil;
import java.util.Comparator;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

@FxmlView
public class LockedView extends ActivatableView<VBox, Void> {
    @FXML
    TableView<LockedListItem> tableView;
    @FXML
    TableColumn<LockedListItem, LockedListItem> dateColumn, detailsColumn, addressColumn, balanceColumn;
    @FXML
    Label numItems;
    @FXML
    Region spacer;
    @FXML
    AutoTooltipButton exportButton;

    private final BtcWalletService btcWalletService;
    private final TradeManager tradeManager;
    private final OpenOfferManager openOfferManager;
    private final Preferences preferences;
    private final CoinFormatter formatter;
    private final OfferDetailsWindow offerDetailsWindow;
    private final TradeDetailsWindow tradeDetailsWindow;
    private final ObservableList<LockedListItem> observableList = FXCollections.observableArrayList();
    private final SortedList<LockedListItem> sortedList = new SortedList<>(observableList);
    private BalanceListener balanceListener;
    private ListChangeListener<OpenOffer> openOfferListChangeListener;
    private ListChangeListener<Trade> tradeListChangeListener;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private LockedView(BtcWalletService btcWalletService,
                       TradeManager tradeManager,
                       OpenOfferManager openOfferManager,
                       Preferences preferences,
                       @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                       OfferDetailsWindow offerDetailsWindow,
                       TradeDetailsWindow tradeDetailsWindow) {
        this.btcWalletService = btcWalletService;
        this.tradeManager = tradeManager;
        this.openOfferManager = openOfferManager;
        this.preferences = preferences;
        this.formatter = formatter;
        this.offerDetailsWindow = offerDetailsWindow;
        this.tradeDetailsWindow = tradeDetailsWindow;
    }

    @Override
    public void initialize() {
        dateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.dateTime")));
        detailsColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.details")));
        addressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        balanceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.balanceWithCur", Res.getBaseCurrencyCode())));

        GUIUtil.applyTableStyle(tableView);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("funds.locked.noFunds")));

        setDateColumnCellFactory();
        setDetailsColumnCellFactory();
        setAddressColumnCellFactory();
        setBalanceColumnCellFactory();

        addressColumn.setComparator(Comparator.comparing(LockedListItem::getAddressString));
        detailsColumn.setComparator(Comparator.comparing(o -> o.getTrade().getId()));
        balanceColumn.setComparator(Comparator.comparing(LockedListItem::getBalance));
        dateColumn.setComparator(Comparator.comparing(o -> getTradable(o).map(Tradable::getDate).orElse(new Date(0))));
        tableView.getSortOrder().add(dateColumn);
        dateColumn.setSortType(TableColumn.SortType.DESCENDING);

        balanceListener = new BalanceListener() {
            @Override
            public void onBalanceChanged(Coin balance, Transaction tx) {
                updateList();
            }
        };
        openOfferListChangeListener = c -> updateList();
        tradeListChangeListener = c -> updateList();

        HBox.setHgrow(spacer, Priority.ALWAYS);
        numItems.setId("num-offers");
        numItems.setPadding(new Insets(-5, 0, 0, 10));
        exportButton.updateText(Res.get("shared.exportCSV"));
    }

    @Override
    protected void activate() {
        openOfferManager.getObservableList().addListener(openOfferListChangeListener);
        tradeManager.getObservableList().addListener(tradeListChangeListener);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);
        updateList();

        btcWalletService.addBalanceListener(balanceListener);

        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));
        exportButton.setOnAction(event -> {
            ObservableList<TableColumn<LockedListItem, ?>> tableColumns = tableView.getColumns();
            int reportColumns = tableColumns.size();
            CSVEntryConverter<LockedListItem> headerConverter = item -> {
                String[] columns = new String[reportColumns];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = ((AutoTooltipLabel) tableColumns.get(i).getGraphic()).getText();
                return columns;
            };
            CSVEntryConverter<LockedListItem> contentConverter = item -> {
                String[] columns = new String[reportColumns];
                columns[0] = item.getDateString();
                columns[1] = item.getDetails();
                columns[2] = item.getAddressString();
                columns[3] = item.getBalanceString();
                return columns;
            };

            GUIUtil.exportCSV("lockedInTradesFunds.csv",
                    headerConverter,
                    contentConverter,
                    new LockedListItem(),
                    sortedList,
                    (Stage) root.getScene().getWindow());
        });
    }

    @Override
    protected void deactivate() {
        openOfferManager.getObservableList().removeListener(openOfferListChangeListener);
        tradeManager.getObservableList().removeListener(tradeListChangeListener);
        sortedList.comparatorProperty().unbind();
        observableList.forEach(LockedListItem::cleanup);
        btcWalletService.removeBalanceListener(balanceListener);
        exportButton.setOnAction(null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateList() {
        observableList.forEach(LockedListItem::cleanup);
        observableList.setAll(tradeManager.getTradesStreamWithFundsLockedIn()
                .map(trade -> {
                    Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(trade.getId(),
                            AddressEntry.Context.MULTI_SIG);
                    return addressEntryOptional.map(addressEntry -> new LockedListItem(trade,
                            addressEntry,
                            btcWalletService,
                            formatter)).orElse(null);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private Optional<Tradable> getTradable(LockedListItem item) {
        String offerId = item.getAddressEntry().getOfferId();
        Optional<Trade> tradeOptional = tradeManager.getOpenTrade(offerId);
        if (tradeOptional.isPresent()) {
            return Optional.of(tradeOptional.get());
        } else if (openOfferManager.getOpenOffer(offerId).isPresent()) {
            return Optional.of(openOfferManager.getOpenOffer(offerId).get());
        } else {
            return Optional.empty();
        }
    }

    private void openDetailPopup(LockedListItem item) {
        Optional<Tradable> tradableOptional = getTradable(item);
        if (tradableOptional.isPresent()) {
            Tradable tradable = tradableOptional.get();
            if (tradable instanceof Trade) {
                tradeDetailsWindow.show((Trade) tradable);
            } else if (tradable instanceof OpenOffer) {
                offerDetailsWindow.show(tradable.getOffer());
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ColumnCellFactories
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        dateColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<LockedListItem, LockedListItem> call(TableColumn<LockedListItem,
                    LockedListItem> column) {
                return new TableCell<>() {

                    @Override
                    public void updateItem(final LockedListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            if (getTradable(item).isPresent())
                                setGraphic(new AutoTooltipLabel(item.getDateString()));
                            else
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
        detailsColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<LockedListItem, LockedListItem> call(TableColumn<LockedListItem,
                    LockedListItem> column) {
                return new TableCell<>() {

                    private HyperlinkWithIcon field;

                    @Override
                    public void updateItem(final LockedListItem item, boolean empty) {
                        super.updateItem(item, empty);

                        if (item != null && !empty) {
                            Optional<Tradable> tradableOptional = getTradable(item);
                            if (tradableOptional.isPresent()) {
                                field = new HyperlinkWithIcon(item.getDetails(),
                                        AwesomeIcon.INFO_SIGN);
                                field.setOnAction(event -> openDetailPopup(item));
                                field.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                setGraphic(field);
                            } else {
                                setGraphic(new AutoTooltipLabel(item.getDetails()));
                            }

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

    private void setAddressColumnCellFactory() {
        addressColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));

        addressColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<LockedListItem, LockedListItem> call(TableColumn<LockedListItem,
                            LockedListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final LockedListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String address = item.getAddressString();
                                    setGraphic(new AutoTooltipLabel(address));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setBalanceColumnCellFactory() {
        balanceColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        balanceColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<LockedListItem, LockedListItem> call(TableColumn<LockedListItem,
                            LockedListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final LockedListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic((item != null && !empty) ? item.getBalanceLabel() : null);
                            }
                        };
                    }
                });
    }

}


