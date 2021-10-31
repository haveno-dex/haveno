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

package haveno.desktop.main.funds.withdrawal;

import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.ExternalHyperlink;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;

import haveno.core.btc.listeners.XmrBalanceListener;
import haveno.core.btc.setup.WalletsSetup;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.locale.Res;
import haveno.core.provider.fee.FeeService;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.BtcAddressValidator;

import haveno.network.p2p.P2PService;

import haveno.common.util.Tuple3;
import haveno.common.util.Tuple4;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;
import javax.inject.Named;

import com.google.common.util.concurrent.FutureCallback;

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
import javafx.scene.control.ToggleButton;
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

import org.bouncycastle.crypto.params.KeyParameter;

import java.math.BigInteger;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static haveno.desktop.util.FormBuilder.*;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;
    @FXML
    TableView<WithdrawalListItem> tableView;
    @FXML
    TableColumn<WithdrawalListItem, WithdrawalListItem> addressColumn, balanceColumn, selectColumn;

    private RadioButton useAllInputsRadioButton, useCustomInputsRadioButton, feeExcludedRadioButton, feeIncludedRadioButton;
    private Label amountLabel;
    private TextField amountTextField, withdrawFromTextField, withdrawToTextField, withdrawMemoTextField, transactionFeeInputTextField;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final WalletsSetup walletsSetup;
    private final CoinFormatter formatter;
    private final Preferences preferences;
    private final BtcAddressValidator btcAddressValidator;
    private final WalletPasswordWindow walletPasswordWindow;
    private final ObservableList<WithdrawalListItem> observableList = FXCollections.observableArrayList();
    private final SortedList<WithdrawalListItem> sortedList = new SortedList<>(observableList);
    private final Set<WithdrawalListItem> selectedItems = new HashSet<>();
    private XmrBalanceListener balanceListener;
    private Set<String> fromAddresses = new HashSet<>();
    private Coin totalAvailableAmountOfSelectedItems = Coin.ZERO;
    private Coin amountAsCoin = Coin.ZERO;
    private ChangeListener<String> amountListener;
    private ChangeListener<Boolean> amountFocusListener, useCustomFeeCheckboxListener, transactionFeeFocusedListener;
    private ChangeListener<Toggle> feeToggleGroupListener, inputsToggleGroupListener;
    private ChangeListener<Number> transactionFeeChangeListener;
    private ToggleGroup feeToggleGroup, inputsToggleGroup;
    private ToggleButton useCustomFee;
    private final BooleanProperty useAllInputs = new SimpleBooleanProperty(true);
    private boolean feeExcluded;
    private int rowIndex = 0;
    private final FeeService feeService;


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
//        throw new RuntimeException("WithdrawalView needs updated to use XMR wallet");
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.p2PService = p2PService;
        this.walletsSetup = walletsSetup;
        this.formatter = formatter;
        this.preferences = preferences;
        this.btcAddressValidator = btcAddressValidator;
        this.walletPasswordWindow = walletPasswordWindow;
        this.feeService = feeService;
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

        feeToggleGroup = new ToggleGroup();

        final Tuple4<Label, TextField, RadioButton, RadioButton> feeTuple3 = addTopLabelTextFieldRadioButtonRadioButton(gridPane, ++rowIndex, feeToggleGroup,
                Res.get("funds.withdrawal.receiverAmount", Res.getBaseCurrencyCode()),
                "",
                Res.get("funds.withdrawal.feeExcluded"),
                Res.get("funds.withdrawal.feeIncluded"),
                0);

        amountLabel = feeTuple3.first;
        amountTextField = feeTuple3.second;
        amountTextField.setMinWidth(180);
        feeExcludedRadioButton = feeTuple3.third;
        feeIncludedRadioButton = feeTuple3.fourth;

        withdrawFromTextField = addTopLabelTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.fromLabel", Res.getBaseCurrencyCode())).second;

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

        withdrawMemoTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.memoLabel", Res.getBaseCurrencyCode())).second;

        Tuple3<Label, InputTextField, ToggleButton> customFeeTuple = addTopLabelInputTextFieldSlideToggleButton(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.txFee"), Res.get("funds.withdrawal.useCustomFeeValue"));
        transactionFeeInputTextField = customFeeTuple.second;
        useCustomFee = customFeeTuple.third;

        useCustomFeeCheckboxListener = (observable, oldValue, newValue) -> {
            transactionFeeInputTextField.setEditable(newValue);
            if (!newValue) {
                try {
                    transactionFeeInputTextField.setText(String.valueOf(feeService.getTxFeePerVbyte().value));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        transactionFeeFocusedListener = (o, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                String estimatedFee = String.valueOf(feeService.getTxFeePerVbyte().value);
                try {
                    int withdrawalTxFeePerVbyte = Integer.parseInt(transactionFeeInputTextField.getText());
                    final long minFeePerVbyte = feeService.getMinFeePerVByte();
                    if (withdrawalTxFeePerVbyte < minFeePerVbyte) {
                        new Popup().warning(Res.get("funds.withdrawal.txFeeMin", minFeePerVbyte)).show();
                        transactionFeeInputTextField.setText(estimatedFee);
                    } else if (withdrawalTxFeePerVbyte > 5000) {
                        new Popup().warning(Res.get("funds.withdrawal.txFeeTooLarge")).show();
                        transactionFeeInputTextField.setText(estimatedFee);
                    } else {
                        preferences.setWithdrawalTxFeeInVbytes(withdrawalTxFeePerVbyte);
                    }
                } catch (NumberFormatException t) {
                    log.error(t.toString());
                    t.printStackTrace();
                    new Popup().warning(Res.get("validation.integerOnly")).show();
                    transactionFeeInputTextField.setText(estimatedFee);
                } catch (Throwable t) {
                    log.error(t.toString());
                    t.printStackTrace();
                    new Popup().warning(Res.get("validation.inputError", t.getMessage())).show();
                    transactionFeeInputTextField.setText(estimatedFee);
                }
            }
        };
        transactionFeeChangeListener = (observable, oldValue, newValue) -> transactionFeeInputTextField.setText(String.valueOf(feeService.getTxFeePerVbyte().value));

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
        feeExcludedRadioButton.setToggleGroup(feeToggleGroup);
        feeIncludedRadioButton.setToggleGroup(feeToggleGroup);
        feeToggleGroupListener = (observable, oldValue, newValue) -> {
            feeExcluded = newValue == feeExcludedRadioButton;
            amountLabel.setText(feeExcluded ?
                    Res.get("funds.withdrawal.receiverAmount") :
                    Res.get("funds.withdrawal.senderAmount"));
        };
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
        feeToggleGroup.selectedToggleProperty().addListener(feeToggleGroupListener);
        inputsToggleGroup.selectedToggleProperty().addListener(inputsToggleGroupListener);

        if (feeToggleGroup.getSelectedToggle() == null)
            feeToggleGroup.selectToggle(feeIncludedRadioButton);

        if (inputsToggleGroup.getSelectedToggle() == null)
            inputsToggleGroup.selectToggle(useAllInputsRadioButton);

        useCustomFee.setSelected(false);
        transactionFeeInputTextField.setEditable(false);
        transactionFeeInputTextField.setText(String.valueOf(feeService.getTxFeePerVbyte().value));
        feeService.feeUpdateCounterProperty().addListener(transactionFeeChangeListener);
        useCustomFee.selectedProperty().addListener(useCustomFeeCheckboxListener);
        transactionFeeInputTextField.focusedProperty().addListener(transactionFeeFocusedListener);

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
        feeToggleGroup.selectedToggleProperty().removeListener(feeToggleGroupListener);
        inputsToggleGroup.selectedToggleProperty().removeListener(inputsToggleGroupListener);
        transactionFeeInputTextField.focusedProperty().removeListener(transactionFeeFocusedListener);
        if (transactionFeeChangeListener != null)
            feeService.feeUpdateCounterProperty().removeListener(transactionFeeChangeListener);
        useCustomFee.selectedProperty().removeListener(useCustomFeeCheckboxListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onWithdraw() {
        throw new RuntimeException("WithdrawalView.onWithdraw() not updated to XMR");
//        if (GUIUtil.isReadyForTxBroadcastOrShowPopup(p2PService, walletsSetup)) {
//            try {
//                final String withdrawToAddress = withdrawToTextField.getText();
//                final Coin sendersAmount;
//
//                // We do not know sendersAmount if senderPaysFee is true. We repeat fee calculation after first attempt if senderPaysFee is true.
//                Transaction feeEstimationTransaction = btcWalletService.getFeeEstimationTransactionForMultipleAddresses(fromAddresses, amountAsCoin);
//                if (feeExcluded && feeEstimationTransaction != null) {
//                    feeEstimationTransaction = btcWalletService.getFeeEstimationTransactionForMultipleAddresses(fromAddresses, amountAsCoin.add(feeEstimationTransaction.getFee()));
//                }
//                checkNotNull(feeEstimationTransaction, "feeEstimationTransaction must not be null");
//
//                Coin dust = btcWalletService.getDust(feeEstimationTransaction);
//                Coin fee = feeEstimationTransaction.getFee().add(dust);
//                Coin receiverAmount;
//                // amountAsCoin is what the user typed into the withdrawal field.
//                // this can be interpreted as either the senders amount or receivers amount depending
//                // on a radio button "fee excluded / fee included".
//                // therefore we calculate the actual sendersAmount and receiverAmount as follows:
//                if (feeExcluded) {
//                    receiverAmount = amountAsCoin;
//                    sendersAmount = receiverAmount.add(fee);
//                } else {
//                    sendersAmount = amountAsCoin.add(dust); // sendersAmount bumped up to UTXO size when dust is in play
//                    receiverAmount = sendersAmount.subtract(fee);
//                }
//                if (dust.isPositive()) {
//                    log.info("Dust output ({} satoshi) was detected, the dust amount has been added to the fee (was {}, now {})",
//                            dust.value,
//                            feeEstimationTransaction.getFee(),
//                            fee.value);
//                }
//
//                if (areInputsValid(sendersAmount)) {
//                    int txVsize = feeEstimationTransaction.getVsize();
//                    log.info("Fee for tx with size {}: {} " + Res.getBaseCurrencyCode() + "", txVsize, fee.toPlainString());
//
//                    if (receiverAmount.isPositive()) {
//                        double vkb = txVsize / 1000d;
//
//                        String messageText = Res.get("shared.sendFundsDetailsWithFee",
//                                formatter.formatCoinWithCode(sendersAmount),
//                                withdrawFromTextField.getText(),
//                                withdrawToAddress,
//                                formatter.formatCoinWithCode(fee),
//                                Double.parseDouble(transactionFeeInputTextField.getText()),
//                                vkb,
//                                formatter.formatCoinWithCode(receiverAmount));
//                        if (dust.isPositive()) {
//                            messageText = Res.get("shared.sendFundsDetailsDust",
//                                    dust.value, dust.value > 1 ? "s" : "")
//                                    + messageText;
//                        }
//
//                        new Popup().headLine(Res.get("funds.withdrawal.confirmWithdrawalRequest"))
//                                .confirmation(messageText)
//                                .actionButtonText(Res.get("shared.yes"))
//                                .onAction(() -> doWithdraw(sendersAmount, fee, new FutureCallback<>() {
//                                    @Override
//                                    public void onSuccess(@javax.annotation.Nullable Transaction transaction) {
//                                        if (transaction != null) {
//                                            String key = "showTransactionSent";
//                                            if (DontShowAgainLookup.showAgain(key)) {
//                                                new TxDetails(transaction.getTxId().toString(), withdrawToAddress, formatter.formatCoinWithCode(sendersAmount))
//                                                        .dontShowAgainId(key)
//                                                        .show();
//                                            }
//                                            log.debug("onWithdraw onSuccess tx ID:{}", transaction.getTxId().toString());
//                                        } else {
//                                            log.error("onWithdraw transaction is null");
//                                        }
//
//                                        List<Trade> trades = new ArrayList<>(tradeManager.getObservableList());
//                                        trades.stream()
//                                                .filter(Trade::isPayoutPublished)
//                                                .forEach(trade -> btcWalletService.getAddressEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT)
//                                                        .ifPresent(addressEntry -> {
//                                                            if (btcWalletService.getBalanceForAddress(addressEntry.getAddress()).isZero())
//                                                                tradeManager.onTradeCompleted(trade);
//                                                        }));
//                                    }
//
//                                    @Override
//                                    public void onFailure(@NotNull Throwable t) {
//                                        log.error("onWithdraw onFailure");
//                                    }
//                                }))
//                                .closeButtonText(Res.get("shared.cancel"))
//                                .show();
//                    } else {
//                        new Popup().warning(Res.get("portfolio.pending.step5_buyer.amountTooLow")).show();
//                    }
//                }
//            } catch (InsufficientFundsException e) {
//                new Popup().warning(Res.get("funds.withdrawal.warn.amountExceeds") + "\n\nError message:\n" + e.getMessage()).show();
//            } catch (Throwable e) {
//                e.printStackTrace();
//                log.error(e.toString());
//                new Popup().warning(e.toString()).show();
//            }
//        }
    }

    private void selectForWithdrawal(WithdrawalListItem item) {
        if (item.isSelected())
            selectedItems.add(item);
        else
            selectedItems.remove(item);

        fromAddresses = selectedItems.stream()
                .map(WithdrawalListItem::getAddressString)
                .collect(Collectors.toSet());

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
        //throw new RuntimeException("WithdrawalView.updateList() needs updated to use XMR");
        observableList.forEach(WithdrawalListItem::cleanup);
        observableList.setAll(xmrWalletService.getAddressEntriesForAvailableBalanceStream()
                .map(addressEntry -> new WithdrawalListItem(addressEntry, xmrWalletService, formatter))
                .collect(Collectors.toList()));

        updateInputSelection();
    }

    private void doWithdraw(Coin amount, Coin fee, FutureCallback<Transaction> callback) {
        throw new RuntimeException("WithdrawalView.doWithdraw() not updated to XMR");
//        if (xmrWalletService.isEncrypted()) {
//            UserThread.runAfter(() -> walletPasswordWindow.onAesKey(aesKey ->
//                    sendFunds(amount, fee, aesKey, callback))
//                    .show(), 300, TimeUnit.MILLISECONDS);
//        } else {
//            sendFunds(amount, fee, null, callback);
//        }
    }

    private void sendFunds(Coin amount, Coin fee, KeyParameter aesKey, FutureCallback<Transaction> callback) {
        throw new RuntimeException("WithdrawalView.sendFunds() not updated to XMR");
//        try {
//            String memo = withdrawMemoTextField.getText();
//            if (memo.isEmpty()) {
//                memo = null;
//            }
//            Transaction transaction = btcWalletService.sendFundsForMultipleAddresses(fromAddresses,
//                    withdrawToTextField.getText(),
//                    amount,
//                    fee,
//                    null,
//                    aesKey,
//                    memo,
//                    callback);
//
//            reset();
//            updateList();
//        } catch (AddressFormatException e) {
//            new Popup().warning(Res.get("validation.btc.invalidAddress")).show();
//        } catch (Wallet.DustySendRequested e) {
//            new Popup().warning(Res.get("validation.amountBelowDust",
//                    formatter.formatCoinWithCode(Restrictions.getMinNonDustOutput()))).show();
//        } catch (AddressEntryException e) {
//            new Popup().error(e.getMessage()).show();
//        } catch (InsufficientMoneyException e) {
//            log.warn(e.getMessage());
//            new Popup().warning(Res.get("funds.withdrawal.notEnoughFunds") + "\n\nError message:\n" + e.getMessage()).show();
//        } catch (Throwable e) {
//            log.warn(e.toString());
//            new Popup().warning(e.toString()).show();
//        }
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

        transactionFeeInputTextField.setText("");
        transactionFeeInputTextField.setPromptText(Res.get("funds.withdrawal.useCustomFeeValueInfo"));

        selectedItems.clear();
        tableView.getSelectionModel().clearSelection();
    }

    private boolean areInputsValid(Coin sendersAmount) {
        if (!sendersAmount.isPositive()) {
            new Popup().warning(Res.get("validation.negative")).show();
            return false;
        }

        if (!btcAddressValidator.validate(withdrawToTextField.getText()).isValid) {
            new Popup().warning(Res.get("validation.btc.invalidAddress")).show();
            return false;
        }
        if (!totalAvailableAmountOfSelectedItems.isPositive()) {
            new Popup().warning(Res.get("funds.withdrawal.warn.noSourceAddressSelected")).show();
            return false;
        }

        if (sendersAmount.compareTo(totalAvailableAmountOfSelectedItems) > 0) {
            new Popup().warning(Res.get("funds.withdrawal.warn.amountExceeds")).show();
            return false;
        }

        return true;
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


