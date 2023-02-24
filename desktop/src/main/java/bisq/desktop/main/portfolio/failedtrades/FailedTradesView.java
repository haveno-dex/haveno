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
                            TableColumn<FailedTradesListItem, FailedTradesListItem	>�
%l�r-/�b�Qw�50�T��*����ռK�	(o�W]kvږ��l}��%1?,���NN �rh�^�?,���NN �rh�i� �]"�\L5����/����.n6h�֑*���F�|tF6���o�T���(�<r��v"tF=�:YP�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�]||5B�D�����D*�Qw?�50�T��*����ռK�	(o�W]R���·ּ��R��d?,���NN �rh�^�?,���NN �rh�i���g�Ad���Sȟ��"�����ۨZǺ���f%��	���z�L�m���<r��v"tF=�:YP�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�L�o����;S�ihsQw�50�T��*����ռK�	(o�W]��_#�>.1{�i��ЊB?,���NN �rh�^�?,���NN �rh�i�aav�Nغf�῵�mn�}0?cB� ���4�\�pd!��Ku���|4�Ы�<r��v"tF=�:YP�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�L�o����;S�ihsQw�50�T��*����ռK�	(o�W]�� {��=Q;8��E�?,���NN �rh�^�?,���NN �rh�i�qv�T��A����B��x��(X5�XTnERl�Ml$�S)��ea_�tXC�R�\�<r��v"tF=�:YP�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�����;v��jQw �50�T��*����ռK�	(o�W]<�Y,kD�C�p��m?,���NN �rh�^�?,���NN �rh�i����Oiq���Z0Ů���}W1S��C�G"�r��� 5�_�J�12�i�<r��v"tF=�:YP�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�0�����A�'�R
�Z:Qw��50�T��*����ռK�	(o�W]� ������jT���?,���NN �rh��^�?,���NN �rh�i�����H��e�o4��;�Scҋ�E���}窼b[0Ώ�-��A���8�di8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�diw��K�����LQw��50�T��*����ռK�	(o�W]B���P(��4���0?,���NN �rh��^�?,���NN �rh�i�!à�p�6)L�G�vc���������Z&m�I�'���͛�hR�T?TL�8A6i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i���zT_�K��e�W��Qw��50�T��*����ռK�	(o�W]h[i�g�_��]E�?,���NN �rh��^�?,���NN �rh�i��}�e�g�k֣/Cs+	̈�y:q��T�����zK(���T�j��He��R�8r.i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�	��ߧ�Z���Ҵ���1Qwp�50�T��*����ռK�	(o�W]B�hEa,�Dn�U��?,���NN �rh��^�?,���NN �rh�i¯$��l�AwԪ%ЇbźÑg%PT �۬9z7�S-e���Xܐ��8�Pi8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i��x�v�Ň ��Qwx�50�T��*����ռK�	(o�W]Q˳��\<����T��?,���NN �rh��^�?,���NN �rh�i�yPd��Yh[�8�0�O�6Rq'�
}r���ǋ%��`���J���>�U8�i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�1|�+^�C$5<�� +Qw��50�T��*����ռK�	(o�W]"��Y�P*��-�:D��?,���NN �rh��^�?,���NN �rh�i� ���
h�����K���Aa�8E]u0y�����rb�ʇ���<�r.q%Æ8�ei8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i��_Ҿ��n���p�[Qw�50�T��*����ռK�	(o�W]�2L�u��!F��Q�G�?,���NN �rh��^�?,���NN �rh�i_��ڕ���O\��h� NR�2��m~�/t9����30�PsC�m��@��8(i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i ��&L�h��ȕ��{Qw>�50�T��*����ռK�	(o�W]���˼������r��?,���NN �rh��^�?,���NN �rh�i�*"��<���=��iM�1]�#���Yqp�|�mNe4G%kk�&9�/�8�xi8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i§�F�J���_f�&Qw��50�T��*����ռK�	(o�W]�r.����煄E]��?,���NN �rh��^�?,���NN �rh�i�lM��6u��F����;E�%�u)���`Jeڛx��,�N��������g�8�8i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�]||5B�D�����D*�Qw?�50�T��*����ռK�	(o�W]f��|���W��"@?,���NN �rh��^�?,���NN �rh�i¹*�cJC%��\?�!tH527G9Q?��:���$��yb�7Wcוd��8i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i���J��k�ɦ�;'ViQw9�50�T��*����ռK�	(o�W]3�iÈ�_��&���v�?,���NN �rh��^�?,���NN �rh�i���ʒ��T�j;�T�:i��"����64:������'��zQeR $&��d�8��i8ӈ�h��ǀ
DǆwQ�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i���KnªE=l}�v%���F��T0��u��&��ռK�	(o�W]D�w�Y=?Lb�?,���NN <�rh4��?,���NN �rh�i�Q�-��"/�����<�)�1���.��(�O���ź	
�,�q��Q�2�}#����Y^��:�n9�u�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�iT�{���\��F�����K*=�� �\����>)��$�!�`�1\�v��?,���NN _� ��i�?,���NN �rh�i�?,���NN �rh�i���vy�;������'�&��Y�%����x��rS�`}�'	LokQM���R�٣��hZI��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�Uh1�|V�2�6�W9�^*=�� �\����Y�ʘ�a�;mv+��,�eUt6�
�]��GSZ���"'N�rh�i�?,���NN �rh�i��w��i-R��G~�ٛ���k8Cy2�IY�`ٿ�@���i'|ݞ-p���3���RH�٣��hZI��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�S� ���}y��c�^�_*=�� �\�������F��k¦����&��C�ۺM��L�s��^�Ʒ*$Aw�rh�i�?,���NN �rh�i¾�(�i����M[��c��U&�s3����EmX�>�S3��tLenZ�!��R�+٣��hZI��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�S� ���}y��c�^�_*=�� �\�������F��k¦����&��C�ۺM��L�s��^�Ʒ*$Aw�rh�i�?,���NN �rh�i�H"���N��7�C�U�������.������%Nz����73��S;-/�k�R}٣��hZI��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�tv��J��7�N���H*=�� �\�����ƪj���>��R�B�?,���NN J���i�?,���NN �rh�i�?,���NN �rh�i����#�o�n��� ��3y��9��~��ܧ�)���=�:M�5�9�R�Ղ#��hV;}��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�tv��J��7�N���H*=�� �\�����ƪj���>��R�B�?,���NN J���i�?,���NN �rh�i�?,���NN �rh�i¨�]S�����͡1;�p<L۴���9��#@%��q��k�%�MF�R��Ղ#��hV;}��~��w�rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�?,���NN �rh�i�tv��J��7�N���H*=�� �\�����iZ��hK�!�~�n�9�?,���NN Q�	��i�?,���NN �rh�i�?,���NN �rh�