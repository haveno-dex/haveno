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

package bisq.desktop.main.funds.withdrawal;

import bisq.desktop.common.view.ActivatableView;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.AutoTooltipCheckBox;
import bisq.desktop.components.AutoTooltipLabel;
import bisq.desktop.components.ExternalHyperlink;
import bisq.desktop.components.HyperlinkWithIcon;
import bisq.desktop.components.InputTextField;
import bisq.desktop.components.TitledGroupBg;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.main.overlays.windows.TxDetails;
import bisq.desktop.main.overlays.windows.WalletPasswordWindow;
import bisq.desktop.util.GUIUtil;
import bisq.desktop.util.Layout;

import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.provider.fee.FeeService;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.user.DontShowAgainLookup;
import bisq.core.user.Preferences;
import bisq.core.util.FormattingUtils;
import bisq.core.util.ParsingUtils;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.validation.BtcAddressValidator;

import bisq.network.p2p.P2PService;
import bisq.common.util.Tuple2;
import bisq.common.util.Tuple3;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;
import javax.inject.Named;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import org.apache.commons.lang3.StringUtils;

import javafx.fxml.FXML;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import javafx.geometry.Pos;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;

import javafx.util.Callback;


import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static bisq.desktop.util.FormBuilder.*;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;
    @FXML
    TableView<WithdrawalListItem> tableView;
    @FXML
    TableColumn<WithdrawalListItem, WithdrawalListItem> addressColumn, balanceColumn, selectColumn;

    private RadioButton useAllInputsRadioButton, useCustomInputsRadioButton;
    private Label amountLabel;
    private TextField amountTextField, withdrawFromTextField, withdrawToTextField, withdrawMemoTextField;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final CoinFormatter formatter;
    private final Preferences preferences;
    private final ObservableList<WithdrawalListItem> observableList = FXCollections.observableArrayList();
    private final SortedList<WithdrawalListItem> sortedList = new SortedList<>(observableList);
    private final Set<WithdrawalListItem> selectedItems = new HashSet<>();
    private XmrBalanceListener balanceListener;
    private Coin totalAvailableAmountOfSelectedItems = Coin.ZERO;
    private Coin amountAsCoin = Coin.ZERO;
    private ChangeListener<String> amountListener;
    private ChangeListener<Boolean> amountFocusListener;
    private ChangeListener<Toggle> inputsToggleGroupListener;
    private ToggleGroup inputsToggleGroup;
    private final BooleanProperty useAllInputs = new SimpleBooleanProperty(true);
    private int rowIndex = 0;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private WithdrawalView(XmrWalletService xmrWalletService,
                           TradeManager tradeManager,
                           P2PService p2PService,
                           WalletsSetup walletsSetup,
                           @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                           Preferences preferences,
                           BtcAddressValidator btcAddressValidator,
                           WalletPasswordWindow walletPasswordWindow,
                           FeeService feeService) {
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.p2PService = p2PService;
        this.formatter = formatter;
        this.preferences = preferences;
    }

    @Override
    public void initialize() {

        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, rowIndex, 4, Res.get("funds.deposit.withdrawFromWallet"));
        titledGroupBg.getStyleClass().add("last");

        inputsToggleGroup = new ToggleGroup();
        inputsToggleGroupListener = (observable, oldValue, newValue) -> {
            useAllInputs.set(newValue == useAllInputsRadioButton);

            updateInputSelection();
        };

        final Tuple3<Label, RadioButton, RadioButton> labelRadioButtonRadioButtonTuple3 =
                addTopLabelRadioButtonRadioButton(gridPane, rowIndex, inputsToggleGroup,
                        Res.get("funds.withdrawal.inputs"),
                        Res.get("funds.withdrawal.useAllInputs"),
                        Res.get("funds.withdrawal.useCustomInputs"),
                        Layout.FIRST_ROW_DISTANCE);

        useAllInputsRadioButton = labelRadioButtonRadioButtonTuple3.second;
        useCustomInputsRadioButton = labelRadioButtonRadioButtonTuple3.third;

        final Tuple2<Label, InputTextField> feeTuple3 = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.receiverAmount", Res.getBaseCurrencyCode()),
                0);

        amountLabel = feeTuple3.first;
        amountTextField = feeTuple3.second;
        amountTextField.setMinWidth(180);

        withdrawFromTextField = addTopLabelTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.fromLabel", Res.getBaseCurrencyCode())).second;

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

        withdrawMemoTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.memoLabel", Res.getBaseCurrencyCode())).second;

        final Button withdrawButton = addButton(gridPane, ++rowIndex, Res.get("funds.withdrawal.withdrawButton"), 15);

        withdrawButton.setOnAction(event -> onWithdraw());

        addressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        balanceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.balanceWithCur", Res.getBaseCurrencyCode())));
        selectColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.select")));

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setMaxHeight(Double.MAX_VALUE);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("funds.withdrawal.noFundsAvailable")));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        setAddressColumnCellFactory();
        setBalanceColumnCellFactory();
        setSelectColumnCellFactory();

        addressColumn.setComparator(Comparator.comparing(WithdrawalListItem::getAddressString));
        balanceColumn.setComparator(Comparator.comparing(WithdrawalListItem::getBalance));
        balanceColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(balanceColumn);

        balanceListener = new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateList();
            }
        };
        amountListener = (observable, oldValue, newValue) -> {
            if (amountTextField.focusedProperty().get()) {
                try {
                    amountAsCoin = ParsingUtils.parseToCoin(amountTextField.getText(), formatter);
                } catch (Throwable t) {
                    log.error("Error at amountTextField input. " + t.toString());
                }
            }
        };
        amountFocusListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                if (amountAsCoin.isPositive())
                    amountTextField.setText(formatter.formatCoin(amountAsCoin));
                else
                    amountTextField.setText("");
            }
        };
        amountLabel.setText(Res.get("funds.withdrawal.receiverAmount"));
    }

    private void updateInputSelection() {
        observableList.forEach(item -> {
            item.setSelected(useAllInputs.get());
            selectForWithdrawal(item);
        });
        tableView.refresh();
    }

    @Override
    protected void activate() {
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);
        updateList();

        reset();

        amountTextField.textProperty().addListener(amountListener);
        amountTextField.focusedProperty().addListener(amountFocusListener);
        xmrWalletService.addBalanceListener(balanceListener);
        inputsToggleGroup.selectedToggleProperty().addListener(inputsToggleGroupListener);

        if (inputsToggleGroup.getSelectedToggle() == null)
            inputsToggleGroup.selectToggle(useAllInputsRadioButton);

        updateInputSelection();
        GUIUtil.requestFocus(withdrawToTextField);
    }

    @Override
    protected void deactivate() {
        sortedList.comparatorProperty().unbind();
        observableList.forEach(WithdrawalListItem::cleanup);
        xmrWalletService.removeBalanceListener(balanceListener);
        amountTextField.textProperty().removeListener(amountListener);
        amountTextField.focusedProperty().removeListener(amountFocusListener);
        inputsToggleGroup.selectedToggleProperty().removeListener(inputsToggleGroupListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onWithdraw() {
        if (GUIUtil.isReadyForTxBroadcastOrShowPopup(p2PService, xmrWalletService.getConnectionsService())) {
            try {

                // get withdraw address
                final String withdrawToAddress = withdrawToTextField.getText();

                // get receiver amount
                Coin receiverAmount = amountAsCoin;
                if (!receiverAmount.isPositive()) throw new RuntimeException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));

                // create tx
                MoneroTxWallet tx = xmrWalletService.getWallet().createTx(new MoneroTxConfig()
                        .setAccountIndex(0)
                        .setAmount(ParsingUtils.coinToAtomicUnits(receiverAmount)) // TODO: rename to centinerosToAtomicUnits()?
                        .setAddress(withdrawToAddress));

                // create confirmation message
                Coin fee = ParsingUtils.atomicUnitsToCoin(tx.getFee());
                Coin sendersAmount = receiverAmount.add(fee);
                String messageText = Res.get("shared.sendFundsDetailsWithFee",
                        formatter.formatCoinWithCode(sendersAmount),
                        withdrawFromTextField.getText(),
                        withdrawToAddress,
                        formatter.formatCoinWithCode(fee),
                        formatter.formatCoinWithCode(receiverAmount));

                // popup confirmation message
                new Popup().headLine(Res.get("funds.withdrawal.confirmWithdrawalRequest"))
                        .confirmation(messageText)
                        .actionButtonText(Res.get("shared.yes"))
                        .onAction(() -> {
                            
                            // relay tx
                            try {
                                xmrWalletService.getWallet().relayTx(tx);
                                String key = "showTransactionSent";
                                if (DontShowAgainLookup.showAgain(key)) {
                                    new TxDetails(tx.getHash(), withdrawToAddress, formatter.formatCoinWithCode(sendersAmount))
                                            .dontShowAgainId(key)
                                            .show();
                                }
                                log.debug("onWithdraw onSuccess tx ID:{}", tx.getHash());
                                
                                List<Trade> trades = new ArrayList<>(tradeManager.getObservableList());
                                trades.stream()
                                        .filter(Trade::isPayoutPublished)
                                        .forEach(trade -> xmrWalletService.getAddressEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT)
                                                .ifPresent(addressEntry -> {
                                                    if (xmrWalletService.getBalanceForAddress(addressEntry.getAddressString()).isZero())
                                                        tradeManager.onTradeCompleted(trade);
                                                }));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        })
                        .closeButtonText(Res.get("shared.cancel"))
                        .show();
            } catch (Throwable e) {
                if (e.getMessage().contains("enough")) new Popup().warning(Res.get("funds.withdrawal.warn.amountExceeds") + "\n\nError message:\n" + e.getMessage()).show();
                else {
                    e.printStackTrace();
                    log.error(e.toString());
                    new Popup().warning(e.toString()).show();
                }
            }
        }
    }

    private void selectForWithdrawal(WithdrawalListItem item) {
        if (item.isSelected())
            selectedItems.add(item);
        else
            selectedItems.remove(item);

        if (!selectedItems.isEmpty()) {
            totalAvailableAmountOfSelectedItems = Coin.valueOf(selectedItems.stream().mapToLong(e -> e.getBalance().getValue()).sum());
            if (totalAvailableAmountOfSelectedItems.isPositive()) {
                amountAsCoin = totalAvailableAmountOfSelectedItems;
                amountTextField.setText(formatter.formatCoin(amountAsCoin));
            } else {
                amountAsCoin = Coin.ZERO;
                totalAvailableAmountOfSelectedItems = Coin.ZERO;
                amountTextField.setText("");
                withdrawFromTextField.setText("");
            }

            if (selectedItems.size() == 1) {
                withdrawFromTextField.setText(selectedItems.stream().findAny().get().getAddressEntry().getAddressString());
                withdrawFromTextField.setTooltip(null);
            } else {
                int abbr = Math.max(10, 66 / selectedItems.size());
                String addressesShortened = selectedItems.stream()
                        .map(e -> StringUtils.abbreviate(e.getAddressString(), abbr))
                        .collect(Collectors.joining(", "));
                String text = Res.get("funds.withdrawal.withdrawMultipleAddresses", addressesShortened);
                withdrawFromTextField.setText(text);

                String addresses = selectedItems.stream()
                        .map(WithdrawalListItem::getAddressString)
                        .collect(Collectors.joining(",\n"));
                String tooltipText = Res.get("funds.withdrawal.withdrawMultipleAddresses.tooltip", addresses);
                withdrawFromTextField.setTooltip(new Tooltip(tooltipText));
            }
        } else {
            reset();
        }
    }

    private void openBlockExplorer(WithdrawalListItem item) {
        if (item.getAddressString() != null)
            GUIUtil.openWebPage(preferences.getBlockChainExplorer().addressUrl + item.getAddressString(), false);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateList() {
        observableList.forEach(WithdrawalListItem::cleanup);
        observableList.setAll(xmrWalletService.getAddressEntriesForAvailableBalanceStream()
                .map(addressEntry -> new WithdrawalListItem(addressEntry, xmrWalletService, formatter))
                .collect(Collectors.toList()));

        updateInputSelection();
    }

    private void reset() {
        withdrawFromTextField.setText("");
        withdrawFromTextField.setPromptText(Res.get("funds.withdrawal.selectAddress"));
        withdrawFromTextField.setTooltip(null);

        totalAvailableAmountOfSelectedItems = Coin.ZERO;
        amountAsCoin = Coin.ZERO;
        amountTextField.setText("");
        amountTextField.setPromptText(Res.get("funds.withdrawal.setAmount"));

        withdrawToTextField.setText("");
        withdrawToTextField.setPromptText(Res.get("funds.withdrawal.fillDestAddress"));

        withdrawMemoTextField.setText("");
        withdrawMemoTextField.setPromptText(Res.get("funds.withdrawal.memo"));

        selectedItems.clear();
        tableView.getSelectionModel().clearSelection();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // ColumnCellFactories
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setAddressColumnCellFactory() {
        addressColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));

        addressColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<WithdrawalListItem, WithdrawalListItem> call(TableColumn<WithdrawalListItem,
                            WithdrawalListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon hyperlinkWithIcon;

                            @Override
                            public void updateItem(final WithdrawalListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String address = item.getAddressString();
                                    hyperlinkWithIcon = new ExternalHyperlink(address);
                                    hyperlinkWithIcon.setOnAction(event -> openBlockExplorer(item));
                                    hyperlinkWithIcon.setTooltip(new Tooltip(Res.get("tooltip.openBlockchainForAddress", address)));
                                    setAlignment(Pos.CENTER);
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

    private void setBalanceColumnCellFactory() {
        balanceColumn.getStyleClass().add("last-column");
        balanceColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        balanceColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<WithdrawalListItem, WithdrawalListItem> call(TableColumn<WithdrawalListItem,
                            WithdrawalListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final WithdrawalListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic((item != null && !empty) ? item.getBalanceLabel() : null);
                            }
                        };
                    }
                });
    }

    private void setSelectColumnCellFactory() {
        selectColumn.getStyleClass().add("first-column");
        selectColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        selectColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<WithdrawalListItem, WithdrawalListItem> call(TableColumn<WithdrawalListItem,
                            WithdrawalListItem> column) {
                        return new TableCell<>() {

                            CheckBox checkBox = new AutoTooltipCheckBox();

                            @Override
                            public void updateItem(final WithdrawalListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    checkBox.setOnAction(e -> {
                                        item.setSelected(checkBox.isSelected());
                                        selectForWithdrawal(item);

                                        // If all are selected we select useAllInputsRadioButton
                                        if (observableList.size() == selectedItems.size()) {
                                            inputsToggleGroup.selectToggle(useAllInputsRadioButton);
                                        } else {
                                            // We don't want to get deselected all when we activate the useCustomInputsRadioButton
                                            // so we temporarily disable the listener
                                            inputsToggleGroup.selectedToggleProperty().removeListener(inputsToggleGroupListener);
                                            inputsToggleGroup.selectToggle(useCustomInputsRadioButton);
                                            useAllInputs.set(false);
                                            inputsToggleGroup.selectedToggleProperty().addListener(inputsToggleGroupListener);
                                        }
                                    });
                                    setGraphic(checkBox);
                                    checkBox.setSelected(item.isSelected());
                                } else {
                                    checkBox.setOnAction(null);
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }
}


