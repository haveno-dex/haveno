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
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.user.DontShowAgainLookup;
import bisq.core.util.FormattingUtils;
import bisq.core.util.ParsingUtils;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.validation.BtcAddressValidator;

import bisq.network.p2p.P2PService;
import bisq.common.util.Tuple2;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;
import javax.inject.Named;
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

import static bisq.desktop.util.FormBuilder.*;

@FxmlView
public class WithdrawalView extends ActivatableView<VBox, Void> {

    @FXML
    GridPane gridPane;

    private Label amountLabel;
    private TextField amountTextField, withdrawToTextField, withdrawMemoTextField;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final CoinFormatter formatter;
    private XmrBalanceListener balanceListener;
    private Coin amountAsCoin = Coin.ZERO;
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
                           @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                           BtcAddressValidator btcAddressValidator,
                           WalletPasswordWindow walletPasswordWindow) {
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.p2PService = p2PService;
        this.formatter = formatter;
    }

    @Override
    public void initialize() {

        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, rowIndex, 4, Res.get("funds.deposit.withdrawFromWallet"));
        titledGroupBg.getStyleClass().add("last");

        final Tuple2<Label, InputTextField> amountTuple3 = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.receiverAmount", Res.getBaseCurrencyCode()),
                Layout.COMPACT_FIRST_ROW_DISTANCE);

        amountLabel = amountTuple3.first;
        amountTextField = amountTuple3.second;
        amountTextField.setMinWidth(180);

        withdrawToTextField = addTopLabelInputTextField(gridPane, ++rowIndex,
                Res.get("funds.withdrawal.toLabel", Res.getBaseCurrencyCode())).second;

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
                Coin receiverAmount = amountAsCoin;
                if (!receiverAmount.isPositive()) throw new RuntimeException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));

                // create tx
                MoneroTxWallet tx = xmrWalletService.getWallet().createTx(new MoneroTxConfig()
                        .setAccountIndex(0)
                        .setAmount(HavenoUtils.coinToAtomicUnits(receiverAmount)) // TODO: rename to centinerosToAtomicUnits()?
                        .setAddress(withdrawToAddress)
                        .setNote(withdrawMemoTextField.getText()));

                // create confirmation message
                Coin fee = HavenoUtils.atomicUnitsToCoin(tx.getFee());
                Coin sendersAmount = receiverAmount.add(fee);
                String messageText = Res.get("shared.sendFundsDetailsWithFee",
                        formatter.formatCoinWithCode(sendersAmount),
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
        amountAsCoin = Coin.ZERO;
        amountTextField.setText("");
        amountTextField.setPromptText(Res.get("funds.withdrawal.setAmount"));

        withdrawToTextField.setText("");
        withdrawToTextField.setPromptText(Res.get("funds.withdrawal.fillDestAddress"));

        withdrawMemoTextField.setText("");
        withdrawMemoTextField.setPromptText(Res.get("funds.withdrawal.memo"));
    }
}


