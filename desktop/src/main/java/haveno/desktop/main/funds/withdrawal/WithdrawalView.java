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

import static haveno.desktop.util.FormBuilder.*;

import haveno.common.util.Tuple2;
import haveno.core.btc.listeners.XmrBalanceListener;
import haveno.core.btc.model.XmrAddressEntry;
import haveno.core.btc.setup.WalletsSetup;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TxDetails;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import haveno.network.p2p.P2PService;
import javax.inject.Inject;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import javafx.fxml.FXML;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import javafx.beans.value.ChangeListener;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;

    private Label amountLabel;
    private TextField amountTextField, withdrawToTextField, withdrawMemoTextField;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private XmrBalanceListener balanceListener;
    private BigInteger amount = BigInteger.valueOf(0);
    private ChangeListener<String> amountListener;
    private ChangeListener<Boolean> amountFocusListener;
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
    }

    @Override
    public void initialize() {

        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, rowIndex, 4, Res.get("funds.deposit.withdrawFromWallet"));
        titledGroupBg.getStyleClass().add("last");

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
        Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

        final Tuple2<Label, InputTextField> amountTuple3 = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.receiverAmount", Res.getBaseCurrencyCode()),
                Layout.COMPACT_FIRST_ROW_DISTANCE);

        amountLabel = amountTuple3.first;
        amountTextField = amountTuple3.second;
        amountTextField.setMinWidth(180);

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
                if (amount.compareTo(BigInteger.valueOf(0)) > 0)
                    amountTextField.setText(HavenoUtils.formatToXmr(amount));
                else
                    amountTextField.setText("");
            }
        };
        amountLabel.setText(Res.get("funds.withdrawal.receiverAmount"));
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
        xmrWalletService.removeBalanceListener(balanceListener);
        amountTextField.textProperty().removeListener(amountListener);
        amountTextField.focusedProperty().removeListener(amountFocusListener);
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
                BigInteger receiverAmount = amount;
                if (receiverAmount.compareTo(BigInteger.valueOf(0)) <= 0) throw new RuntimeException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));

                // create tx
                MoneroTxWallet tx = xmrWalletService.getWallet().createTx(new MoneroTxConfig()
                        .setAccountIndex(0)
                        .setAmount(receiverAmount)
                        .setAddress(withdrawToAddress));

                // create confirmation message
                BigInteger sendersAmount = receiverAmount;
                BigInteger fee = tx.getFee();
                String messageText = Res.get("shared.sendFundsDetailsWithFee",
                        HavenoUtils.formatToXmrWithCode(sendersAmount),
                        withdrawToAddress,
                        HavenoUtils.formatToXmrWithCode(fee),
                        HavenoUtils.formatToXmrWithCode(receiverAmount));

                // popup confirmation message
                new Popup().headLine(Res.get("funds.withdrawal.confirmWithdrawalRequest"))
                        .confirmation(messageText)
                        .actionButtonText(Res.get("shared.yes"))
                        .onAction(() -> {
                            
                            // relay tx
                            try {
                                xmrWalletService.getWallet().relayTx(tx);
                                xmrWalletService.getWallet().setTxNote(tx.getHash(), withdrawMemoTextField.getText()); // TODO (monero-java): tx note does not persist when tx created then relayed
                                String key = "showTransactionSent";
                                if (DontShowAgainLookup.showAgain(key)) {
                                    new TxDetails(tx.getHash(), withdrawToAddress, HavenoUtils.formatToXmrWithCode(sendersAmount), HavenoUtils.formatToXmrWithCode(fee), xmrWalletService.getWallet().getTxNote(tx.getHash()))
                                            .dontShowAgainId(key)
                                            .show();
                                }
                                log.debug("onWithdraw onSuccess tx ID:{}", tx.getHash());
                                
                                List<Trade> trades = new ArrayList<>(tradeManager.getObservableList());
                                trades.stream()
                                        .filter(Trade::isPayoutPublished)
                                        .forEach(trade -> xmrWalletService.getAddressEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT)
                                                .ifPresent(addressEntry -> {
                                                    if (xmrWalletService.getBalanceForAddress(addressEntry.getAddressString()).compareTo(BigInteger.valueOf(0)) == 0)
                                                        tradeManager.onTradeCompleted(trade);
                                                }));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        })
                        .closeButtonText(Res.get("shared.cancel"))
                        .show();
            } catch (Throwable e) {
                if (e.getMessage().contains("enough")) new Popup().warning(Res.get("funds.withdrawal.warn.amountExceeds")).show();
                else {
                    e.printStackTrace();
                    log.error(e.toString());
                    new Popup().warning(e.toString()).show();
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void reset() {
        amount = BigInteger.valueOf(0);
        amountTextField.setText("");
        amountTextField.setPromptText(Res.get("funds.withdrawal.setAmount"));

        withdrawToTextField.setText("");
        withdrawToTextField.setPromptText(Res.get("funds.withdrawal.fillDestAddress"));

        withdrawMemoTextField.setText("");
        withdrawMemoTextField.setPromptText(Res.get("funds.withdrawal.memo"));
    }
}


