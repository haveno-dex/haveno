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
import haveno.common.util.Tuple3;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TxWithdrawWindow;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroUtils;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.Arrays;

import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextField;
import static haveno.desktop.util.FormBuilder.addButton;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    private GridPane gridPane;

    private BusyAnimation spinningWheel;


    private StackPane overlayPane;

    private Label amountLabel;
    private TextField amountTextField, withdrawToTextField, withdrawMemoTextField;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final WalletPasswordWindow walletPasswordWindow;
    private XmrBalanceListener balanceListener;
    private BigInteger amount = BigInteger.ZERO;
    private ChangeListener<String> amountListener;
    private ChangeListener<Boolean> amountFocusListener;
    private int rowIndex = 0;
    boolean sendMax = false;

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

        spinningWheel = new BusyAnimation();
        overlayPane = new StackPane();
        overlayPane.setStyle("-fx-background-color: transparent;"); // Adjust opacity as needed
        overlayPane.setVisible(false);
        overlayPane.getChildren().add(spinningWheel);

        // Add overlay pane to root VBox
        root.getChildren().add(overlayPane);

        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, rowIndex, 4, Res.get("funds.deposit.withdrawFromWallet"));
        titledGroupBg.getStyleClass().add("last");

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

        final Tuple3<Label, TextField, HyperlinkWithIcon> feeTuple3 = FormBuilder.addTopLabelTextFieldHyperLink(gridPane, ++rowIndex, "",
                Res.get("funds.withdrawal.receiverAmount", Res.getBaseCurrencyCode()),
                Res.get("funds.withdrawal.sendMax"),
                0);

        amountLabel = feeTuple3.first;
        amountTextField = feeTuple3.second;
        amountTextField.setMinWidth(200);
        HyperlinkWithIcon sendMaxLink = feeTuple3.third;

        withdrawMemoTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.memoLabel", Res.getBaseCurrencyCode())).second;

        final Button withdrawButton = addButton(gridPane, ++rowIndex, Res.get("funds.withdrawal.withdrawButton"), 15);

        withdrawButton.setOnAction(event -> {
            // Show the spinning wheel (progress indicator)
            showLoadingIndicator();

            // Execute onWithdraw() method on a separate thread
            new Thread(() -> {
                // Call the method that performs the withdrawal
                onWithdraw();

                // Hide the spinning wheel (progress indicator) after withdrawal is complete
                Platform.runLater(() -> hideLoadingIndicator());
            }).start();
        });

        sendMaxLink.setOnAction(event -> {
            sendMax = true;
            amount = null; // set amount when tx created
            amountTextField.setText(Res.get("funds.withdrawal.maximum"));
        });

        balanceListener = new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {

            }
        };
        amountListener = (observable, oldValue, newValue) -> {
            if (amountTextField.focusedProperty().get()) {
                sendMax = false; // disable max if amount changed while focused
                try {
                    amount = HavenoUtils.parseXmr(amountTextField.getText());
                } catch (Throwable t) {
                    log.error("Error at amountTextField input. " + t.toString());
                }
            }
        };
        amountFocusListener = (observable, oldValue, newValue) -> {

            // parse amount on focus out unless sending max
            if (oldValue && !newValue && !sendMax) {
                if (amount.compareTo(BigInteger.ZERO) > 0)
                    amountTextField.setText(HavenoUtils.formatXmr(amount));
                else
                    amountTextField.setText("");
            }
        };
        amountLabel.setText(Res.get("funds.withdrawal.receiverAmount"));
    }


    private void showLoadingIndicator() {
        overlayPane.setVisible(true);
        spinningWheel.play();
        root.setDisable(true);
    }

    private void hideLoadingIndicator() {
        overlayPane.setVisible(false);
        spinningWheel.stop();
        root.setDisable(false);
    }

    @Override
    protected void activate() {
        reset();

        amountTextField.textProperty().addListener(amountListener);
        amountTextField.focusedProperty().addListener(amountFocusListener);
        xmrWalletService.addBalanceListener(balanceListener);

        GUIUtil.requestFocus(withdrawToTextField);
    }

    @Override
    protected void deactivate() {
        spinningWheel.stop();
        xmrWalletService.removeBalanceListener(balanceListener);
        amountTextField.textProperty().removeListener(amountListener);
        amountTextField.focusedProperty().removeListener(amountFocusListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onWithdraw() {
        if (GUIUtil.isReadyForTxBroadcastOrShowPopup(xmrWalletService)) {
            try {

                // collect tx fields to local variables
                String withdrawToAddress = withdrawToTextField.getText();
                boolean sendMax = this.sendMax;
                BigInteger amount = this.amount;

                // validate address
                if (!MoneroUtils.isValidAddress(withdrawToAddress, XmrWalletService.getMoneroNetworkType())) {
                    throw new IllegalArgumentException(Res.get("validation.xmr.invalidAddress"));
                }

                // set max amount if requested
                if (sendMax) amount = xmrWalletService.getAvailableBalance();

                // check sufficient available balance
                if (amount.compareTo(BigInteger.ZERO) <= 0) throw new RuntimeException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));

                // create tx
                MoneroTxWallet tx = null;
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrWalletService.getXmrConnectionService().getConnection();
                    try {
                        log.info("Creating withdraw tx");
                        long startTime = System.currentTimeMillis();
                        tx = xmrWalletService.createTx(new MoneroTxConfig()
                                .setAccountIndex(0)
                                .setAmount(amount)
                                .setAddress(withdrawToAddress)
                                .setSubtractFeeFrom(sendMax ? Arrays.asList(0) : null));
                        log.info("Done creating withdraw tx in {} ms", System.currentTimeMillis() - startTime);
                        break;
                    } catch (Exception e) {
                        if (isNotEnoughMoney(e.getMessage())) throw e;
                        log.warn("Error creating creating withdraw tx, attempt={}/{}, error={}", i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        if (xmrWalletService.getXmrConnectionService().isConnected()) xmrWalletService.requestSwitchToNextBestConnection(sourceConnection);
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    }
                }

                // popup confirmation message
                popupConfirmationMessage(tx);
            } catch (Throwable e) {
                e.printStackTrace();
                if (isNotEnoughMoney(e.getMessage())) new Popup().warning(Res.get("funds.withdrawal.notEnoughFunds")).show();
                else new Popup().warning(e.getMessage()).show();
            }
        }
    }

    private static boolean isNotEnoughMoney(String errorMsg) {
        return errorMsg.contains("not enough");
    }

    private void popupConfirmationMessage(MoneroTxWallet tx) {

        // create confirmation message
        String withdrawToAddress = tx.getOutgoingTransfer().getDestinations().get(0).getAddress();
        BigInteger receiverAmount = tx.getOutgoingTransfer().getDestinations().get(0).getAmount();
        BigInteger fee = tx.getFee();
        String messageText = Res.get("shared.sendFundsDetailsWithFee",
                HavenoUtils.formatXmr(receiverAmount, true),
                withdrawToAddress,
                HavenoUtils.formatXmr(fee, true));

        // popup confirmation message
        Popup popup = new Popup();
        popup.headLine(Res.get("funds.withdrawal.confirmWithdrawalRequest"))
                .confirmation(messageText)
                .actionButtonText(Res.get("shared.yes"))
                .onAction(() -> {
                    if (xmrWalletService.isWalletEncrypted()) {
                        walletPasswordWindow.headLine(Res.get("walletPasswordWindow.headline")).onSuccess(() -> {
                            relayTx(tx, withdrawToAddress, receiverAmount, fee);
                        }).onClose(() -> {
                            popup.hide();
                        }).hideForgotPasswordButton().show();
                    } else {
                        relayTx(tx, withdrawToAddress, receiverAmount, fee);
                    }
                })
                .closeButtonText(Res.get("shared.cancel"))
                .show();
    }

    private void relayTx(MoneroTxWallet tx, String withdrawToAddress, BigInteger receiverAmount, BigInteger fee) {
        try {
            xmrWalletService.getWallet().relayTx(tx);
            xmrWalletService.getWallet().setTxNote(tx.getHash(), withdrawMemoTextField.getText()); // TODO (monero-java): tx note does not persist when tx created then relayed
            String key = "showTransactionSent";
            if (DontShowAgainLookup.showAgain(key)) {
                new TxWithdrawWindow(tx.getHash(), withdrawToAddress, HavenoUtils.formatXmr(receiverAmount, true), HavenoUtils.formatXmr(fee, true), xmrWalletService.getWallet().getTxNote(tx.getHash()))
                        .dontShowAgainId(key)
                        .show();
            }
            log.debug("onWithdraw onSuccess tx ID:{}", tx.getHash());
        } catch (Exception e) {
            e.printStackTrace();
            new Popup().warning(e.getMessage()).show();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void reset() {
        sendMax = false;
        amount = BigInteger.ZERO;
        amountTextField.setText("");
        amountTextField.setPromptText(Res.get("funds.withdrawal.setAmount"));

        withdrawToTextField.setText("");
        withdrawToTextField.setPromptText(Res.get("funds.withdrawal.fillDestAddress"));

        withdrawMemoTextField.setText("");
        withdrawMemoTextField.setPromptText(Res.get("funds.withdrawal.memo"));
    }
}


