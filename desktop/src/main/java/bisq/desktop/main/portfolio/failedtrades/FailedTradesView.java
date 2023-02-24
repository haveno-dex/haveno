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

package bisq.desktop.main.portfolio.failedtrades;

import bisq.desktop.common.view.ActivatableViewAndModel;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.AutoTooltipButton;
import bisq.desktop.components.AutoTooltipLabel;
import bisq.desktop.components.HyperlinkWithIcon;
import bisq.desktop.components.InputTextField;
import bisq.desktop.main.offer.OfferViewUtil;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.main.overlays.windows.TradeDetailsWindow;
import bisq.desktop.util.FormBuilder;
import bisq.desktop.util.GUIUtil;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.trade.Contract;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;

import bisq.common.util.Utilities;

import org.bitcoinj.core.Coin;

import com.googlecode.jcsv.writer.CSVEntryConverter;

import javax.inject.Inject;

import de.jensd.fx.fontawesome.AwesomeIcon;

import com.jfoenix.controls.JFXButton;

import javafx.fxml.FXML;

import javafx.stage.Stage;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;

import javafx.event.EventHandler;

import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import javafx.util.Callback;

import java.util.Comparator;

@FxmlView
public class FailedTradesView extends ActivatableViewAndModel<VBox, FailedTradesViewModel> {

    @FXML
    TableView<FailedTradesListItem> tableView;
    @FXML
    TableColumn<FailedTradesListItem, FailedTradesListItem> priceColumn, amountColumn, volumeColumn,
            marketColumn, directionColumn, dateColumn, tradeIdColumn, stateColumn, removeTradeColumn;
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

    private final TradeDetailsWindow tradeDetailsWindow;
    private SortedList<FailedTradesListItem> sortedList;
    private FilteredList<FailedTradesListItem> filteredList;
    private EventHandler<KeyEvent> keyEventEventHandler;
    private ChangeListener<String> filterTextFieldListener;
    private Scene scene;
    private XmrWalletService xmrWalletService;
    private ContextMenu contextMenu;

    @Inject
    public FailedTradesView(FailedTradesViewModel model,
                            TradeDetailsWindow tradeDetailsWindow,
                            XmrWalletService xmrWalletService) {
        super(model);
        this.tradeDetailsWindow = tradeDetailsWindow;
        this.xmrWalletService = xmrWalletService;
    }

    @Override
    public void initialize() {
        priceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.price")));
        amountColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amountWithCur", Res.getBaseCurrencyCode())));
        volumeColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amount")));
        marketColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.market")));
        directionColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.offerType")));
        dateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.dateTime")));
        tradeIdColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.tradeId")));
        stateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.state")));

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noItems", Res.get("shared.trades"))));

        setTradeIdColumnCellFactory();
        setDirectionColumnCellFactory();
        setAmountColumnCellFactory();
        setPriceColumnCellFactory();
        setVolumeColumnCellFactory();
        setDateColumnCellFactory();
        setMarketColumnCellFactory();
        setStateColumnCellFactory();
        setRemoveTradeColumnCellFactory();

        tradeIdColumn.setComparator(Comparator.comparing(o -> o.getTrade().getId()));
        dateColumn.setComparator(Comparator.comparing(o -> o.getTrade().getDate()));
        priceColumn.setComparator(Comparator.comparing(o -> o.getTrade().getPrice()));
        volumeColumn.setComparator(Comparator.comparing(o -> o.getTrade().getVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
        amountColumn.setComparator(Comparator.comparing(o -> o.getTrade().getAmount(), Comparator.nullsFirst(Comparator.naturalOrder())));
        stateColumn.setComparator(Comparator.comparing(model::getState));
        marketColumn.setComparator(Comparator.comparing(model::getMarketLabel));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);

        keyEventEventHandler = keyEvent -> {
            if (Utilities.isAltOrCtrlPressed(KeyCode.Y, keyEvent)) {
                var checkTxs = checkTxs();
                var checkUnfailString = checkUnfail();
                if (!checkTxs.isEmpty()) {
                    log.warn("Cannot unfail, error {}", checkTxs);
                    new Popup().warning(checkTxs)
                            .show();
                } else if (!checkUnfailString.isEmpty()) {
                    log.warn("Cannot unfail, error {}", checkUnfailString);
                    new Popup().warning(Res.get("portfolio.failed.cantUnfail", checkUnfailString))
                            .show();
                } else {
                    new Popup().warning(Res.get("portfolio.failed.unfail"))
                            .onAction(this::onUnfail)
                            .show();
                }
            }
        };

        filterLabel.setText(Res.get("shared.filter"));
        HBox.setMargin(filterLabel, new Insets(5, 0, 0, 10));
        filterTextFieldListener = (observable, oldValue, newValue) -> applyFilteredListPredicate(filterTextField.getText());
        searchBox.setSpacing(5);
        HBox.setHgrow(searchBoxSpacer, Priority.ALWAYS);

        numItems.setId("num-offers");
        numItems.setPadding(new Insets(-5, 0, 0, 10));
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox.setMargin(exportButton, new Insets(0, 10, 0, 0));
        exportButton.updateText(Res.get("shared.exportCSV"));
    }

    @Override
    protected void activate() {
        scene = root.getScene();
        if (scene != null) {
            scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }

        filteredList = new FilteredList<>(model.getList());
        sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);


        contextMenu = new ContextMenu();
        MenuItem item1 = new MenuItem(Res.get("support.contextmenu.penalize.msg", Res.get("shared.maker")));
        MenuItem item2 = new MenuItem(Res.get("support.contextmenu.penalize.msg", Res.get("shared.taker")));
        contextMenu.getItems().addAll(item1, item2);

        tableView.setRowFactory(tv -> {
            TableRow<FailedTradesListItem> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                contextMenu.show(row, event.getScreenX(), event.getScreenY());
            });
            return row;
        });

        item1.setOnAction(event -> {
            Trade selectedFailedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
            handleContextMenu("portfolio.failed.penalty.msg",
                    "shared.maker",
                    selectedFailedTrade.getMakerFee(),
                    selectedFailedTrade.getMaker().getReserveTxHash(),
                    selectedFailedTrade.getMaker().getReserveTxHex());
        });

        item2.setOnAction(event -> {
            Trade selectedFailedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
            handleContextMenu("portfolio.failed.penalty.msg",
                    "shared.taker",
                    selectedFailedTrade.getTakerFee(),
                    selectedFailedTrade.getTaker().getReserveTxHash(),
                    selectedFailedTrade.getTaker().getReserveTxHex());
        });

        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));
        exportButton.setOnAction(event -> {
            ObservableList<TableColumn<FailedTradesListItem, ?>> tableColumns = tableView.getColumns();
            int reportColumns = tableColumns.size() - 1;    // CSV report excludes the last column (an icon)
            CSVEntryConverter<FailedTradesListItem> headerConverter = item -> {
                String[] columns = new String[reportColumns];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = ((AutoTooltipLabel) tableColumns.get(i).getGraphic()).getText();
                return columns;
            };
            CSVEntryConverter<FailedTradesListItem> contentConverter = item -> {
                String[] columns = new String[reportColumns];
                columns[0] = model.getTradeId(item);
                columns[1] = model.getDate(item);
                columns[2] = model.getMarketLabel(item);
                columns[3] = model.getPrice(item);
                columns[4] = model.getAmount(item);
                columns[5] = model.getVolume(item);
                columns[6] = model.getDirectionLabel(item);
                columns[7] = model.getState(item);
                return columns;
            };

            GUIUtil.exportCSV("failedTrades.csv",
                    headerConverter,
                    contentConverter,
                    new FailedTradesListItem(),
                    sortedList,
                    (Stage) root.getScene().getWindow());
        });

        filterTextField.textProperty().addListener(filterTextFieldListener);
        applyFilteredListPredicate(filterTextField.getText());
    }

    private void handleContextMenu(String msgKey, String takerOrMaker, Coin fee, String reserveTxHash, String reserveTxHex) {
        final Trade failedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
        log.debug("Found {} matching trade.", (failedTrade != null ? failedTrade.getId() : null));
        if(failedTrade != null) {
            new Popup().warning(Res.get(msgKey,
                    HavenoUtils.formatToXmr(fee),//traderFee
                    HavenoUtils.formatToXmr(failedTrade.getAmount().subtract(fee)),
                    HavenoUtils.formatToXmr(failedTrade.getTxFee()),
                    reserveTxHash,
                    takerOrMaker)
            ).onAction(() -> OfferViewUtil.submitTransactionHex(xmrWalletService, tableView, reserveTxHex)).show();
        } else {
            new Popup().error(Res.get("portfolio.failed.noSignedOffer.error.msg")).show();
        }
    }

    @Override
    protected void deactivate() {
        if (scene != null) {
            scene.removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }

        sortedList.comparatorProperty().unbind();
        exportButton.setOnAction(null);

        filterTextField.textProperty().removeListener(filterTextFieldListener);
    }

    private void applyFilteredListPredicate(String filterString) {
        filteredList.setPredicate(item -> {
            if (filterString.isEmpty())
                return true;

            Offer offer = item.getTrade().getOffer();

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
            if (model.getVolume(item).contains(filterString)) {
                return true;
            }
            if (model.getAmount(item).contains(filterString)) {
                return true;
            }
            if (model.getDirectionLabel(item).contains(filterString)) {
                return true;
            }
            if (offer.getOfferFeePaymentTxId().contains(filterString)) {
                return true;
            }

            Trade trade = item.getTrade();

            if (trade.getMaker().getDepositTxHash() != null && trade.getMaker().getDepositTxHash().contains(filterString)) {
                return true;
            }
            if (trade.getTaker().getDepositTxHash() != null && trade.getTaker().getDepositTxHash().contains(filterString)) {
                return true;
            }
            if (trade.getPayoutTxId() != null && trade.getPayoutTxId().contains(filterString)) {
                return true;
            }

            Contract contract = trade.getContract();

            boolean isBuyerOnion = false;
            boolean isSellerOnion = false;
            boolean matchesBuyersPaymentAccountData = false;
            boolean matchesSellersPaymentAccountData = false;
            if (contract != null) {
                isBuyerOnion = contract.getBuyerNodeAddress().getFullAddress().contains(filterString);
                isSellerOnion = contract.getSellerNodeAddress().getFullAddress().contains(filterString);
                matchesBuyersPaymentAccountData = trade.getBuyer().getPaymentAccountPayload().getPaymentDetails().contains(filterString);
                matchesSellersPaymentAccountData = trade.getSeller().getPaymentAccountPayload().getPaymentDetails().contains(filterString);
            }
            return isBuyerOnion || isSellerOnion ||
                    matchesBuyersPaymentAccountData || matchesSellersPaymentAccountData;
        });
    }

    private void onUnfail() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        model.dataModel.unfailTrade(trade);
    }

    private String checkUnfail() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        return model.dataModel.checkUnfail(trade);
    }

    private String checkTxs() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        log.info("Initiated unfail of trade {}", trade.getId());
        if (trade.getMakerDepositTx() == null || trade.getTakerDepositTx() == null) {
            log.info("Check unfail found no deposit tx(s) for trade {}", trade.getId());
            return Res.get("portfolio.failed.depositTxNull");
        }
        return "";
    }

    private void onRevertTrade(Trade trade) {
        new Popup().attention(Res.get("portfolio.failed.revertToPending.popup"))
                .onAction(() -> onMoveTradeToPendingTrades(trade))
                .actionButtonText(Res.get("shared.yes"))
                .closeButtonText(Res.get("shared.no"))
                .show();
    }

    private void onMoveTradeToPendingTrades(Trade trade) {
        model.dataModel.onMoveTradeToPendingTrades(trade);
    }

    private void setTradeIdColumnCellFactory() {
        tradeIdColumn.getStyleClass().add("first-column");
        tradeIdColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        tradeIdColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(TableColumn<FailedTradesListItem,
                            FailedTradesListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    field = new HyperlinkWithIcon(model.getTradeId(item));
                                    field.setOnAction(event -> tradeDetailsWindow.show(item.getTrade()));
                                    field.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
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

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        dateColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setGraphic(new AutoTooltipLabel(model.getDate(item)));
                                else
                                    setGraphic(null);
                            }
                        };
                    }
                });
    }

    private void setMarketColumnCellFactory() {
        marketColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        marketColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic(new AutoTooltipLabel(model.getMarketLabel(item)));
                            }
                        };
                    }
                });
    }

    private void setStateColumnCellFactory() {
        stateColumn.getStyleClass().add("last-column");
        stateColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        stateColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem	>•
%l§rÂ-/÷bîQwÇ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]ï“•kvÚ–úÔl}Ãè%1?,ö¤ŞNN ørhÀ^Ê?,ö¤ŞNN ørhî„iÂ ¶]"é\L5˜©óÓ/şûÒÙ.n6hêÖ‘*Æ»ÿFŸ|tF6ÃËßoûT™ÌĞ(Ô<rŠìv"tF=Ç:YPørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ]||5BËD»Ãú©äD*´Qw?Ç50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]R÷‰â‚Â·Ö¼¾R„ªd?,ö¤ŞNN ørhÀ^Ê?,ö¤ŞNN ørhî„iÂÊõgüAdò–ÚôSÈŸÜÊ"øÇşºÛ¨ZÇºôÍç—f%µÑ	×ËÄzLÄm·Ğµ<rŠìv"tF=Ç:YPørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂLËo½ËÅÓ;S ihsQwÇ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]Òü_#Ä>.1{˜iŠ²ĞŠB?,ö¤ŞNN ørhÀ^Ê?,ö¤ŞNN ørhî„iÂaavÑNØºf©á¿µÄmnÔ}0?cB† ª¨ò4û\¼pd!ÿ¥KuÁô’|4ÍĞ«ß<rŠìv"tF=Ç:YPørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂLËo½ËÅÓ;S ihsQwÇ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]Ôå {æõ=Q;8´êEº?,ö¤ŞNN ørhÀ^Ê?,ö¤ŞNN ørhî„iÂqvèT£ËA„øŸõB¹Ãx·ë•(X5ëXTnERl­Ml$ÎS)İÎea_øtXC“RĞ\†<rŠìv"tF=Ç:YPørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ«€Øˆë;vŞòjQw Ç50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]<ÍY,kDîCúp“•m?,ö¤ŞNN ørhÀ^Ê?,ö¤ŞNN ørhî„iÂõß—Oiqá—ğµØZ0Å®—³Ô}W1SïäCêŠG"Ór´Ïó 5’_–JÚ12ĞiĞ<rŠìv"tF=Ç:YPørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ0¿•Åú±A¤'³R
Z:Qw“Æ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW] ªşÁ×ıùjTÑÕ?,ö¤ŞNN ørhîÁ^Ê?,ö¤ŞNN ørhî„iÂÑ¾ËÄH’«e„o4‰;×ScÒ‹†E€â‡¸}çª¼b[0Î¢-¢ŞA£¢†8‚di8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂdiwÔÈKÆóºõ™şLQwùÆ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]B’™—P(µ°4¼¦Œ0?,ö¤ŞNN ørhïÁ^Ê?,ö¤ŞNN ørhî„iÂ!Ã ¤pë˜6)L¿Gÿvc®ıÜû‚ø•¼Z&m¬Ió'ãïùÍ›ÍhRì±T?TL†8A6i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂŞøzT_ëKîìeöW»ØQwİÆ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]h[i§gÿ_òÉ]EÏ?,ö¤ŞNN ørhìÁ^Ê?,ö¤ŞNN ørhî„iÂõ}€eÃg²kÖ£/Cs+	ÌˆÆy:q´äTúı°‘ĞzK(’“ıTÎj‡€He®¨R†8r.i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ	ûêß§ùZ¯˜Ò´÷Òï1QwpÇ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]BühEa,´Dn¥U«í?,ö¤ŞNN ørhíÁ^Ê?,ö¤ŞNN ørhî„iÂ¯$¦ólÆAwÔª%Ğ‡bÅºÃ‘g%PT ó«Û¬9z7ÑS-eş½ÓXÜ¶†8úPi8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂÃxşv±Å‡ ×ŒQwxÇ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]QË³¹ç\<éàÖTõ“?,ö¤ŞNN ørhêÁ^Ê?,ö¤ŞNN ørhî„iÂyPd£ĞYh[8î0¨O¯6Rq'—
}r½›±Ç‹%õÛ`¥ûŸJ©ª½>“UÂ†8¡i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ1|ì+^ÉC$5<½é +QwéÆ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]"ÖõYèP*¢ -í:DüÀ?,ö¤ŞNN ørhëÁ^Ê?,ö¤ŞNN ørhî„iÂ ‘¸û
h›¢ÈÂKÍÁÙAaê®8E]u0yü‡®äôrbëÊ‡¹Œâ<­r.q%Ã†8Åei8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂş_Ò¾ãön÷ÕÉp…[QwÆ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]È2Lôuãõ!F¡ì“QıG™?,ö¤ŞNN ørhèÁ^Ê?,ö¤ŞNN ørhî„iÂ—_­ˆÚ•º¡šO\ıÇhÜ NRÃ2èÎm~È/t9ÒûåÏ30õPsC­m¿Ç@¿†8(i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ Ò¨&Lœh­È•ãÍ{Qw>Æ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]ñÑÿË¼ƒ„º¿ëÅrûÿ?,ö¤ŞNN ørhéÁ^Ê?,ö¤ŞNN ørhî„iÂ*"©Ù<Åîô=üiÂMş1]í¦#í¨¥Yqp|„mNe4G%kkô&9²/†8ˆxi8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ§ùF¢JÎ¾¨_fİ&Qw¯Æ50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]Úr.§Ÿéšç…„E]¥§?,ö¤ŞNN ørhæÁ^Ê?,ö¤ŞNN ørhî„iÂlM¨é6uØÕF¨”…Ë;EÌ%×u)†’ˆ`JeÚ›x¯Ë,¶Nô÷•çÖû²¸g†8ê8i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ]||5BËD»Ãú©äD*´Qw?Ç50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]fèÖ|·±¢Wã ß"@?,ö¤ŞNN ørhçÁ^Ê?,ö¤ŞNN ørhî„iÂ¹*ªcJC%Ÿü\?÷!tH527G9Q?§:…ü$íÒyb¬7Wc×•d¯†8i8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ÷ªJ·ºk£É¦œ;'ViQw9Ç50ÈT‡ê*ªĞÑÕ¼Kì	(oŒW]3¨iÃˆô_ŞÛ&”–ívœ?,ö¤ŞNN ørhäÁ^Ê?,ö¤ŞNN ørhî„iÂçÈÊ’ËğT¸j;áT÷:i¡éº"Œ‡¶”64:³¿»êı'£ç¼zQeR $&¨ëd†8ªíi8Óˆíh¼ëÇ€
DÇ†wQørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂëÿKnÂªE=l}‘v%ù—‹FÄT0û¿ïŒµu‹¤&–ÑÕ¼Kì	(o¬W]DÊw•Y=?Lbû?,ö¤ŞNN <ørh4ÒË?,ö¤ŞNN ørhî„iÂQ¨-ö”"/Œü©±<°)’1’çÕ.ùú(äO€ñÄÅº	
ï,«q¨¥Q2˜}#Œ›ş¯Y^ Ü:Çn9êuørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ˜TÇ{âêà\ñÏFßóş¹K*=‹Ó ô\£õ¼>)èÅ$ê!“`¦1\âv¯Ã?,ö¤ßNN _™ ‹„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂÉÅvy;Üêñìµ¶'Ÿ&ŸòœY§%¶çŠúÃx¶rS‚`}ò'	LokQMşºÉRÀÙ£œ‹hZI«Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂUh1‚|VÅ2¨6´W9^*=‹Ó ô\£õ¼YÊ˜ça‰;mv+½¤,øeUt6½
ç]ÚÆGSZ´Á¬"'Nørhî„iÂ?,ö¤ŞNN ørhî„iÂÚw´Æi-Rà˜G~¢Ù›öÁ k8Cy2ğIY¡`Ù¿Ğ@”±™i'|İ-p¯öµ3ŒÍÉRHãÙ£œ‹hZI«Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂS‹ µŠ“}yšÆc•^_*=‹Ó ô\£õ¼•ùëFúë™kÂ¦ò™Ùå&éÆCœÛºMƒòLµsıÛ^·Æ·*$Awørhî„iÂ?,ö¤ŞNN ørhî„iÂ¾“(øiÿ­ùËM[«ôc’U&ás3“…šÌEmX—>üS3ÊtLenZõ!’ÉR+Ù£œ‹hZI«Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂS‹ µŠ“}yšÆc•^_*=‹Ó ô\£õ¼•ùëFúë™kÂ¦ò™Ùå&éÆCœÛºMƒòLµsıÛ^·Æ·*$Awørhî„iÂ?,ö¤ŞNN ørhî„iÂH"æÖÀNŒù7íC—UÔ™‹²å.ÇÒÍÍÄÄ%Nzÿ€ªé 73„°S;-/ÑkÉR}Ù£œ‹hZI«Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂtî™¬v ±J­Á7ÏNîùH*=‹Ó ô\£õ¼äÆªjÏşõ>ÿµRõBï?,ö¤ßNN J‹ıiÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ÷›æ#åoÿnğßş –®3yùù9äã~³äÜ§í)•ÒÀ=ó:MÕ5ğ«9ÉR¸Õ‚#Ÿ‹hV;}¨Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂtî™¬v ±J­Á7ÏNîùH*=‹Ó ô\£õ¼äÆªjÏşõ>ÿµRõBï?,ö¤ßNN J‹ıiÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ¨ş]SœõÅæóÍ¡1;‡p<LÛ´“Ÿğ9“—#@%¹±qäk¢%”MFÉRñİÕ‚#Ÿ‹hV;}¨Ç~ôÂwørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„iÂtî™¬v ±J­Á7ÏNîùH*=‹Ó ô\£õ¼œiZÁ€hK²!ì~³nŸ9û?,ö¤ßNN Q—	œğiÂ?,ö¤ŞNN ørhî„iÂ?,ö¤ŞNN ørhî„