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

import com.google.inject.Inject;
import haveno.common.util.Tuple4;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TxDetails;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.util.FormBuilder;
import static haveno.desktop.util.FormBuilder.addButton;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextField;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;

    private Label amountLabel;
    private TextField amountTextField, withdrawToTextField, withdrawMemoTextField;
    private RadioButton feeExcludedRadioButton, feeIncludedRadioButton;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final WalletPasswordWindow walletPasswordWindow;
    private XmrBalanceListener balanceListener;
    private BigInteger amount = BigInteger.ZERO;
    private ChangeListener<String> amountListener;
    private ChangeListener<Boolean> amountFocusListener;
    private ChangeListener<Toggle> feeToggleGroupListener;
    private ToggleGroup feeToggleGroup;
    private boolean feeExcluded;
    private int rowIndex = 0;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private WithdrawalView(XmrWalletService xmrWalletService,
                           TradeManager tradeManager,
                           P2PService p2PService,
                           WalletsSetup walletsSetup,
                           BtcAddressValidator btcAddressValidator,
                           WalletPasswordWindow walletPasswordWindow) {
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.p2PService = p2PService;
        this.walletPasswordWindow = walletPasswordWindow;
    }

    @Override
    public void initialize() {

        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, rowIndex, 4, Res.get("funds.deposit.withdrawFromWallet"));
        titledGroupBg.getStyleClass().add("last");

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

        feeToggleGroup = new ToggleGroup();

        final Tuple4<Label, TextField, RadioButton, RadioButton> feeTuple3 = FormBuilder.addTopLabelTextFieldRadioButtonRadioButton(gridPane, ++rowIndex, feeToggleGroup,
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

        withdrawMemoTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.memoLabel", Res.getBaseCurrencyCode())).second;

        final Button withdrawButton = addButton(gridPane, ++rowIndex, Res.get("funds.withdrawal.withdrawButton"), 15);

        withdrawButton.setOnAction(event -> onWithdraw());

        balanceListener = new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {

            }
        };
        amountListener = (observable, oldValue, newValue) -> {
            if (amountTextField.focusedProperty().get()) {
                try {
                    amount = HavenoUtils.parseXmr(amountTextField.getText());
                } catch (Throwable t) {
                    log.error("Error at amountTextField input. " + t.toString());
                }
            }
        };
        amountFocusListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                if (amount.compareTo(BigInteger.ZERO) > 0)
                    amountTextField.setText(HavenoUtils.formatXmr(amount));
                else
                    amountTextField.setText("");
            }
        };
        amountLabel.setText(Res.get("funds.withdrawal.receiverAmount"));
        feeExcludedRadioButton.setToggleGroup(feeToggleGroup);
        feeIncludedRadioButton.setToggleGroup(feeToggleGroup);
        feeToggleGroupListener = (observable, oldValue, newValue) -> {
            feeExcluded = newValue == feeExcludedRadioButton;
            amountLabel.setText(feeExcluded ?
                    Res.get("funds.withdrawal.receiverAmount") :
                    Res.get("funds.withdrawal.senderAmount"));
        };
    }

    @Override
    protected void activate() {
        reset();

        amountTextField.textProperty().addListener(amountListener);
        amountTextField.focusedProperty().addListener(amountFocusListener);
        xmrWalletService.addBalanceListener(balanceListener);
        feeToggleGroup.selectedToggleProperty().addListener(feeToggleGroupListener);

        if (feeToggleGroup.getSelectedToggle() == null) feeToggleGroup.selectToggle(feeExcludedRadioButton);

        GUIUtil.requestFocus(withdrawToTextField);
    }

    @Override
    protected void deactivate() {
        xmrWalletService.removeBalanceListener(balanceListener);
        amountTextField.textProperty().removeListener(amountListener);
        amountTextField.focusedProperty().removeListener(amountFocusListener);
        feeToggleGroup.selectedToggleProperty().removeListener(feeToggleGroupListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onWithdraw() {
        if (GUIUtil.isReadyForTxBroadcastOrShowPopup(xmrWalletService)) {
            try {

                // get withdraw address
                final String withdrawToAddress = withdrawToTextField.getText();

                // create tx
                if (amount.compareTo(BigInteger.ZERO) <= 0) throw new RuntimeException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));
                MoneroTxWallet tx = xmrWalletService.getWallet().createTx(new MoneroTxConfig()
                        .setAccountIndex(0)
                        .setAmount(amount)
                        .setAddress(withdrawToAddress)
                        .setSubtractFeeFrom(feeExcluded ? null : Arrays.asList(0)));

                // create confirmation message
                BigInteger receiverAmount = tx.getOutgoingTransfer().getDestinations().get(0).getAmount();
                BigInteger fee = tx.getFee();
                String messageText = Res.get("shared.sendFundsDetailsWithFee",
                        HavenoUtils.formatXmr(amount, true),
                        withdrawToAddress,
                        HavenoUtils.formatXmr(fee, true),
                        HavenoUtils.formatXmr(receiverAmount, true));

                // popup confirmation message
                Popup popup = new Popup();
                popup.headLine(Res.get("funds.withdrawal.confirmWithdrawalRequest"))
                        .confirmation(messageText)
                        .actionButtonText(Res.get("shared.yes"))
                        .onAction(() -> {
                            if (xmrWalletService.isWalletEncrypted()) {
                                walletPasswordWindow.headLine(Res.get("walletPasswordWindow.headline")).onSuccess(() -> {
                                    relayTx(tx, withdrawToAddress, amount, fee);
                                }).onClose(() -> {
                                    popup.hide();
                                }).hideForgotPasswordButton().show();
                            } else {
                                relayTx(tx, withdrawToAddress, amount, fee);
                            }
                        })
                        .closeButtonText(Res.get("shared.cancel"))
                        .show();
            } catch (Throwable e) {
                if (e.getMessage().contains("enough")) new Popup().warning(Res.get("funds.withdrawal.warn.amountExceeds")).show();
                else {
                    e.printStackTrace();
                    new Popup().warning(e.getMessage()).show();
                }
            }
        }
    }

    private void relayTx(MoneroTxWallet tx, String withdrawToAddress, BigInteger receiverAmount, BigInteger fee) {
        try {
            xmrWalletService.getWallet().relayTx(tx);
            xmrWalletService.getWallet().setTxNote(tx.getHash(), withdrawMemoTextField.getText()); // TODO (monero-java): tx note does not persist when tx created then relayed
            String key = "showTransactionSent";
            if (DontShowAgainLookup.showAgain(key)) {
                new TxDetails(tx.getHash(), withdrawToAddress, HavenoUtils.formatXmr(receiverAmount, true), HavenoUtils.formatXmr(fee, true), xmrWalletService.getWallet().getTxNote(tx.getHash()))
                        .dontShowAgainId(key)
                        .show();
            }
            log.debug("onWithdraw onSuccess tx ID:{}", tx.getHash());

            // TODO: remove this?
            List<Trade> trades = new ArrayList<>(tradeManager.getObservableList());
            trades.stream()
                    .filter(Trade::isPayoutPublished)
                    .forEach(trade -> xmrWalletService.getAddressEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT)
                            .ifPresent(addressEntry -> {
                                if (xmrWalletService.getBalanceForAddress(addressEntry.getAddressString()).compareTo(BigInteger.ZERO) == 0)
                                    tradeManager.onTradeCompleted(trade);
                            }));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void reset() {
        amount = BigInteger.ZERO;
        amountTextField.setText("");
        amountTextField.setPromptText(Res.get("funds.withdrawal.setAmount"));

        withdrawToTextField.setText("");
        withdrawToTextField.setPromptText(Res.get("funds.withdrawal.fillDestAddress"));

        withdrawMemoTextField.setText("");
        withdrawMemoTextField.setPromptText(Res.get("funds.withdrawal.memo"));
    }
}


