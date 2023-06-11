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

package haveno.desktop.main.funds.deposit;

import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple3;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AddressTextField;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.ExternalHyperlink;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.QRCodeWindow;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static haveno.desktop.util.FormBuilder.addAddressTextField;
import static haveno.desktop.util.FormBuilder.addButtonCheckBoxWithBox;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;

@FxmlView
public class DepositView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;
    @FXML
    TableView<DepositListItem> tableView;
    @FXML
    TableColumn<DepositListItem, DepositListItem> addressColumn, balanceColumn, confirmationsColumn, usageColumn;
    private ImageView qrCodeImageView;
    private AddressTextField addressTextField;
    private Button generateNewAddressButton;
    private TitledGroupBg titledGroupBg;
    private InputTextField amountTextField;

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final CoinFormatter formatter;
    private String paymentLabelString;
    private final ObservableList<DepositListItem> observableList = FXCollections.observableArrayList();
    private final SortedList<DepositListItem> sortedList = new SortedList<>(observableList);
    private XmrBalanceListener balanceListener;
    private MoneroWalletListener walletListener;
    private Subscription amountTextFieldSubscription;
    private ChangeListener<DepositListItem> tableViewSelectionListener;
    private int gridRow = 0;
    List<MoneroTxWallet> txsWithIncomingOutputs;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private DepositView(XmrWalletService xmrWalletService,
                        Preferences preferences,
                        @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.formatter = formatter;
    }

    @Override
    public void initialize() {

        paymentLabelString = Res.get("funds.deposit.fundHavenoWallet");
        addressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        balanceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.balanceWithCur", Res.getBaseCurrencyCode())));
        confirmationsColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.confirmations")));
        usageColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.usage")));

        // prefetch all incoming txs to avoid query per subaddress
        txsWithIncomingOutputs = xmrWalletService.getTxsWithIncomingOutputs();

        // trigger creation of at least 1 address
        xmrWalletService.getFreshAddressEntry(txsWithIncomingOutputs);

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("funds.deposit.noAddresses")));
        tableViewSelectionListener = (observableValue, oldValue, newValue) -> {
            if (newValue != null) {
                fillForm(newValue.getAddressString());
                GUIUtil.requestFocus(amountTextField);
            }
        };

        setAddressColumnCellFactory();
        setBalanceColumnCellFactory();
        setUsageColumnCellFactory();
        setConfidenceColumnCellFactory();

        addressColumn.setComparator(Comparator.comparing(DepositListItem::getAddressString));
        balanceColumn.setComparator(Comparator.comparing(DepositListItem::getBalanceAsBI));
        confirmationsColumn.setComparator(Comparator.comparingLong(o -> o.getNumConfirmationsSinceFirstUsed(txsWithIncomingOutputs)));
        usageColumn.setComparator(Comparator.comparingInt(DepositListItem::getNumTxsWithOutputs));
        tableView.getSortOrder().add(usageColumn);
        tableView.setItems(sortedList);

        titledGroupBg = addTitledGroupBg(gridPane, gridRow, 4, Res.get("funds.deposit.fundWallet"));
        titledGroupBg.getStyleClass().add("last");

        qrCodeImageView = new ImageView();
        qrCodeImageView.setFitHeight(150);
        qrCodeImageView.setFitWidth(150);
        qrCodeImageView.getStyleClass().add("qr-code");
        Tooltip.install(qrCodeImageView, new Tooltip(Res.get("shared.openLargeQRWindow")));
        qrCodeImageView.setOnMouseClicked(e -> UserThread.runAfter(
                        () -> new QRCodeWindow(getPaymentUri()).show(),
                        200, TimeUnit.MILLISECONDS));
        GridPane.setRowIndex(qrCodeImageView, gridRow);
        GridPane.setRowSpan(qrCodeImageView, 4);
        GridPane.setColumnIndex(qrCodeImageView, 1);
        GridPane.setMargin(qrCodeImageView, new Insets(Layout.FIRST_ROW_DISTANCE, 0, 0, 10));
        gridPane.getChildren().add(qrCodeImageView);

        addressTextField = addAddressTextField(gridPane, ++gridRow, Res.get("shared.address"), Layout.FIRST_ROW_DISTANCE);
        addressTextField.setPaymentLabel(paymentLabelString);


        amountTextField = addInputTextField(gridPane, ++gridRow, Res.get("funds.deposit.amount"));
        amountTextField.setMaxWidth(380);
        if (DevEnv.isDevMode())
            amountTextField.setText("10");

        titledGroupBg.setVisible(false);
        titledGroupBg.setManaged(false);
        qrCodeImageView.setVisible(false);
        qrCodeImageView.setManaged(false);
        addressTextField.setVisible(false);
        addressTextField.setManaged(false);
        amountTextField.setManaged(false);

        Tuple3<Button, CheckBox, HBox> buttonCheckBoxHBox = addButtonCheckBoxWithBox(gridPane, ++gridRow,
                Res.get("funds.deposit.generateAddress"),
                null,
                15);
        buttonCheckBoxHBox.third.setSpacing(25);
        generateNewAddressButton = buttonCheckBoxHBox.first;

        generateNewAddressButton.setOnAction(event -> {
            boolean hasUnUsedAddress = observableList.stream().anyMatch(e -> e.getSubaddressIndex() != 0 && xmrWalletService.getTxsWithIncomingOutputs(e.getSubaddressIndex()).isEmpty());
            if (hasUnUsedAddress) {
                new Popup().warning(Res.get("funds.deposit.selectUnused")).show();
            } else {
                XmrAddressEntry newSavingsAddressEntry = xmrWalletService.getNewAddressEntry();
                updateList();
                observableList.stream()
                        .filter(depositListItem -> depositListItem.getAddressString().equals(newSavingsAddressEntry.getAddressString()))
                        .findAny()
                        .ifPresent(depositListItem -> tableView.getSelectionModel().select(depositListItem));
            }
        });

        balanceListener = new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateList();
            }
        };

        walletListener = new MoneroWalletListener() {
            @Override
            public void onNewBlock(long height) {
                updateList();
            }
        };

        GUIUtil.focusWhenAddedToScene(amountTextField);
    }

    @Override
    protected void activate() {
        tableView.getSelectionModel().selectedItemProperty().addListener(tableViewSelectionListener);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());

        updateList();

        xmrWalletService.addBalanceListener(balanceListener);
        xmrWalletService.addWalletListener(walletListener);

        amountTextFieldSubscription = EasyBind.subscribe(amountTextField.textProperty(), t -> {
            addressTextField.setAmount(HavenoUtils.parseXmr(t));
            updateQRCode();
        });

        if (tableView.getSelectionModel().getSelectedItem() == null && !sortedList.isEmpty())
            tableView.getSelectionModel().select(0);
    }

    @Override
    protected void deactivate() {
        tableView.getSelectionModel().selectedItemProperty().removeListener(tableViewSelectionListener);
        sortedList.comparatorProperty().unbind();
        observableList.forEach(DepositListItem::cleanup);
        xmrWalletService.removeBalanceListener(balanceListener);
        xmrWalletService.removeWalletListener(walletListener);
        amountTextFieldSubscription.unsubscribe();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void fillForm(String address) {
        titledGroupBg.setVisible(true);
        titledGroupBg.setManaged(true);
        qrCodeImageView.setVisible(true);
        qrCodeImageView.setManaged(true);
        addressTextField.setVisible(true);
        addressTextField.setManaged(true);
        amountTextField.setManaged(true);

        GridPane.setMargin(generateNewAddressButton, new Insets(15, 0, 0, 0));

        addressTextField.setAddress(address);

        updateQRCode();
    }

    private void updateQRCode() {
        if (addressTextField.getAddress() != null && !addressTextField.getAddress().isEmpty()) {
            final byte[] imageBytes = QRCode
                    .from(getPaymentUri())
                    .withSize(300, 300)
                    .to(ImageType.PNG)
                    .stream()
                    .toByteArray();
            Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
            qrCodeImageView.setImage(qrImage);
        }
    }

    private void openBlockExplorer(DepositListItem item) {
        if (item.getAddressString() != null)
            GUIUtil.openWebPage(preferences.getBlockChainExplorer().addressUrl + item.getAddressString(), false);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateList() {
        observableList.forEach(DepositListItem::cleanup);
        observableList.clear();

        // cache incoming txs
        txsWithIncomingOutputs = xmrWalletService.getTxsWithIncomingOutputs();

        // add available address entries and base address
        xmrWalletService.getAddressEntries()
                .forEach(e -> observableList.add(new DepositListItem(e, xmrWalletService, formatter, txsWithIncomingOutputs)));
    }

    private Coin getAmount() {
        return ParsingUtils.parseToCoin(amountTextField.getText(), formatter);
    }

    @NotNull
    private String getPaymentUri() {
        return xmrWalletService.getWallet().getPaymentUri(new MoneroTxConfig()
                .setAddress(addressTextField.getAddress())
                .setAmount(HavenoUtils.coinToAtomicUnits(getAmount()))
                .setNote(paymentLabelString));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // ColumnCellFactories
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setUsageColumnCellFactory() {
        usageColumn.getStyleClass().add("last-column");
        usageColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        usageColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                    DepositListItem> column) {
                return new TableCell<>() {

                    @Override
                    public void updateItem(final DepositListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            setGraphic(new AutoTooltipLabel(item.getUsage()));
                        } else {
                            setGraphic(null);
                        }
                    }
                };
            }
        });
    }

    private void setAddressColumnCellFactory() {
        addressColumn.getStyleClass().add("first-column");
        addressColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));

        addressColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                            DepositListItem> column) {
                        return new TableCell<>() {

                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final DepositListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String address = item.getAddressString();
                                    field = new ExternalHyperlink(address);
                                    field.setOnAction(event -> {
                                        openBlockExplorer(item);
                                        tableView.getSelectionModel().select(item);
                                    });
                                    field.setTooltip(new Tooltip(Res.get("tooltip.openBlockchainForAddress", address)));
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

    private void setBalanceColumnCellFactory() {
        balanceColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        balanceColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                    DepositListItem> column) {
                return new TableCell<>() {

                    @Override
                    public void updateItem(final DepositListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            if (textProperty().isBound())
                                textProperty().unbind();

                            textProperty().bind(item.balanceProperty());
                        } else {
                            textProperty().unbind();
                            setText("");
                        }
                    }
                };
            }
        });
    }


    private void setConfidenceColumnCellFactory() {
        confirmationsColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        confirmationsColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                            DepositListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final DepositListItem item, boolean empty) {
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


