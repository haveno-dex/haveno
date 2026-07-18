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
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIconView;
import haveno.common.UserThread;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.user.Preferences;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TxWithdrawWindow;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroUtils;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;

@FxmlView
public class WithdrawalView extends ActivatableView<StackPane, Void> {

    private static final double HERO_TOP_BIAS = 0.3; // seat the card's top in the upper third of the free space
    private static final double HERO_TOP_MAX = 130; // cap the top gap so the card anchors high on tall windows
    private static final double NOMINAL_CARD_HEIGHT = 490; // anchor against this fixed height so errors grow the card downward without shifting it

    private BusyAnimation spinningWheel;
    private StackPane overlayPane;

    private Label balanceAmountLabel, balanceFiatLabel, amountFeedbackLabel, addressErrorLabel;
    private InputTextField addressField, amountField;
    private HavenoTextArea noteArea;
    private MaterialDesignIconView addressCheck;
    private Button sendButton;

    private final XmrWalletService xmrWalletService;
    private final TradeManager tradeManager;
    private final P2PService p2PService;
    private final WalletPasswordWindow walletPasswordWindow;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private final DecimalFormat fiatFormat;

    private XmrBalanceListener balanceListener;
    private ChangeListener<Number> priceChangeListener;
    private ChangeListener<String> addressListener, amountListener;
    private ChangeListener<Boolean> addressFocusListener, amountFocusListener;
    private BigInteger amount = BigInteger.ZERO;
    private boolean sendMax = false;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private WithdrawalView(XmrWalletService xmrWalletService,
                           TradeManager tradeManager,
                           P2PService p2PService,
                           WalletsSetup walletsSetup,
                           BtcAddressValidator btcAddressValidator,
                           WalletPasswordWindow walletPasswordWindow,
                           Preferences preferences,
                           PriceFeedService priceFeedService) {
        this.xmrWalletService = xmrWalletService;
        this.tradeManager = tradeManager;
        this.p2PService = p2PService;
        this.walletPasswordWindow = walletPasswordWindow;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;

        fiatFormat = (DecimalFormat) NumberFormat.getNumberInstance(GlobalSettings.getLocale());
        fiatFormat.setMinimumFractionDigits(2);
        fiatFormat.setMaximumFractionDigits(2);
    }

    @Override
    public void initialize() {

        Label heading = new AutoTooltipLabel(Res.get("funds.withdrawal.sendFunds"));
        heading.getStyleClass().add("send-hero-title");

        // available balance with a fiat approximation
        Label availableLabel = new AutoTooltipLabel(Res.get("funds.withdrawal.availableToSend"));
        availableLabel.getStyleClass().add("send-balance-label");
        balanceAmountLabel = new AutoTooltipLabel();
        balanceAmountLabel.getStyleClass().add("send-balance-amount");
        balanceFiatLabel = new AutoTooltipLabel();
        balanceFiatLabel.getStyleClass().add("send-balance-fiat");
        VBox balanceBox = new VBox(1, availableLabel, balanceAmountLabel, balanceFiatLabel);
        balanceBox.setAlignment(Pos.CENTER);
        VBox.setMargin(balanceBox, new Insets(2, 0, 6, 0));

        // destination address: single line, monospace, with a green check once it validates
        Label addressLabel = fieldLabel(Res.get("funds.withdrawal.destinationAddress"));
        addressField = new InputTextField();
        addressField.setLabelFloat(false);
        addressField.getStyleClass().add("send-address-field");
        addressCheck = new MaterialDesignIconView(MaterialDesignIcon.CHECK, "1.25em");
        addressCheck.getStyleClass().add("send-valid-check");
        addressCheck.setMouseTransparent(true);
        addressCheck.setVisible(false);
        StackPane addressStack = new StackPane(addressField, addressCheck);
        StackPane.setAlignment(addressCheck, Pos.CENTER_RIGHT);
        StackPane.setMargin(addressCheck, new Insets(0, 12, 0, 0));
        addressErrorLabel = new AutoTooltipLabel();
        addressErrorLabel.getStyleClass().addAll("send-feedback", "send-feedback-error");
        addressErrorLabel.setVisible(false);
        addressErrorLabel.setManaged(false);
        VBox addressGroup = new VBox(5, addressLabel, addressStack, addressErrorLabel);

        // amount with a max shortcut and a fiat approximation
        Label amountLabel = fieldLabel(Res.get("funds.withdrawal.amount"));
        amountField = new InputTextField();
        amountField.setLabelFloat(false);
        HBox.setHgrow(amountField, Priority.ALWAYS);
        Label maxLink = new AutoTooltipLabel(Res.get("funds.withdrawal.max"));
        maxLink.getStyleClass().add("send-max-link");
        maxLink.setCursor(Cursor.HAND);
        maxLink.setOnMouseClicked(e -> onMax());
        HBox amountRow = new HBox(10, amountField, maxLink);
        amountRow.setAlignment(Pos.CENTER_LEFT);
        amountFeedbackLabel = new AutoTooltipLabel();
        amountFeedbackLabel.getStyleClass().add("send-feedback");
        amountFeedbackLabel.setVisible(false);
        amountFeedbackLabel.setManaged(false);
        VBox amountGroup = new VBox(5, amountLabel, amountRow, amountFeedbackLabel);

        // optional private note
        Label noteLabel = fieldLabel(Res.get("funds.withdrawal.note"));
        noteArea = new HavenoTextArea();
        noteArea.setLabelFloat(false);
        noteArea.setWrapText(true);
        noteArea.setPrefRowCount(3); // shows two full lines (the skin clips the last ~half row)
        VBox noteGroup = new VBox(5, noteLabel, noteArea);

        // send action
        sendButton = new AutoTooltipButton(Res.get("funds.withdrawal.send"));
        sendButton.getStyleClass().add("action-button");
        sendButton.setDefaultButton(true);
        sendButton.setMaxWidth(Double.MAX_VALUE);
        sendButton.setDisable(true);
        sendButton.setOnAction(event -> onSend());
        VBox.setMargin(sendButton, new Insets(10, 0, 0, 0));

        VBox card = new VBox(16, heading, balanceBox, addressGroup, amountGroup, noteGroup, sendButton);
        card.getStyleClass().add("send-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setFillWidth(true);
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        // root the card at a window-height-based top offset, like the receive tab
        Region topSpacer = new Region();
        Region bottomSpacer = new Region();
        topSpacer.setMinHeight(14);
        VBox.setVgrow(bottomSpacer, Priority.ALWAYS);
        VBox layoutBox = new VBox(topSpacer, card, bottomSpacer);
        layoutBox.setAlignment(Pos.TOP_CENTER);
        layoutBox.setFillWidth(true);
        topSpacer.prefHeightProperty().bind(Bindings.createDoubleBinding(() ->
                Math.min(HERO_TOP_MAX, Math.max(0, (layoutBox.getHeight() - NOMINAL_CARD_HEIGHT) * HERO_TOP_BIAS)),
                layoutBox.heightProperty()));

        spinningWheel = new BusyAnimation(false);
        overlayPane = new StackPane(spinningWheel);
        overlayPane.setVisible(false);

        root.getChildren().addAll(layoutBox, overlayPane);

        balanceListener = new XmrBalanceListener() {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                UserThread.execute(() -> {
                    updateBalanceDisplay();
                    updateAmountFeedback();
                    updateSendButtonState();
                });
            }
        };
        priceChangeListener = (observable, oldValue, newValue) -> {
            updateBalanceDisplay();
            updateAmountFeedback();
        };
        addressListener = (observable, oldValue, newValue) -> {
            updateAddressFeedback(false);
            updateSendButtonState();
        };
        addressFocusListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue) updateAddressFeedback(true); // flag an invalid address only on focus out
        };
        amountListener = (observable, oldValue, newValue) -> {
            if (amountField.isFocused()) {
                sendMax = false; // disable max if amount changed while focused
                amount = safeParse(newValue);
            }
            updateAmountFeedback();
            updateSendButtonState();
        };
        amountFocusListener = (observable, oldValue, newValue) -> {
            // reformat amount on focus out unless sending max
            if (oldValue && !newValue && !sendMax) {
                if (amount.compareTo(BigInteger.ZERO) > 0) amountField.setText(HavenoUtils.formatXmr(amount));
                else amountField.setText("");
            }
        };
    }

    @Override
    protected void activate() {
        reset();
        updateBalanceDisplay();

        addressField.textProperty().addListener(addressListener);
        addressField.focusedProperty().addListener(addressFocusListener);
        amountField.textProperty().addListener(amountListener);
        amountField.focusedProperty().addListener(amountFocusListener);
        xmrWalletService.addBalanceListener(balanceListener);
        priceFeedService.updateCounterProperty().addListener(priceChangeListener);

        GUIUtil.requestFocus(addressField);
    }

    @Override
    protected void deactivate() {
        spinningWheel.stop();
        addressField.textProperty().removeListener(addressListener);
        addressField.focusedProperty().removeListener(addressFocusListener);
        amountField.textProperty().removeListener(amountListener);
        amountField.focusedProperty().removeListener(amountFocusListener);
        xmrWalletService.removeBalanceListener(balanceListener);
        priceFeedService.updateCounterProperty().removeListener(priceChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onMax() {
        sendMax = true;
        amount = null; // set amount when tx created
        amountField.setText(HavenoUtils.formatXmr(xmrWalletService.getAvailableBalance()));
        updateAmountFeedback();
        updateSendButtonState();
    }

    private void onSend() {
        showLoadingIndicator();
        new Thread(() -> {
            onWithdraw();
            Platform.runLater(this::hideLoadingIndicator);
        }).start();
    }

    private void onWithdraw() {
        if (GUIUtil.isReadyForTxBroadcastOrShowPopup(xmrWalletService)) {
            try {

                // collect tx fields to local variables
                String withdrawToAddress = strippedAddress();
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
                        if (HavenoUtils.isInvalidTx(e)) throw e;
                        log.warn("Error creating withdraw tx, attempt={}/{}, error={}", i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        if (xmrWalletService.getXmrConnectionService().isConnected()) xmrWalletService.requestConnectionSwitchSynchronous(sourceConnection); // TODO: use xmrWalletService.handleMainWalletError instead and make this private?
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    }
                }

                // popup confirmation message
                popupConfirmationMessage(tx);
            } catch (Throwable e) {
                log.warn("Error creating withdraw tx", e);
                if (HavenoUtils.isNotEnoughMoney(e)) new Popup().warning(Res.get("funds.withdrawal.notEnoughFunds")).show();
                else new Popup().warning(HavenoUtils.capitalizeFirstLetter(e.getMessage())).show();
            }
        }
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
            synchronized (xmrWalletService.getWalletLock()) {
                xmrWalletService.getWallet().relayTx(tx);
                xmrWalletService.getWallet().setTxNote(tx.getHash(), noteArea.getText()); // TODO (monero-java): tx note does not persist when tx created then relayed
                new TxWithdrawWindow(tx.getHash(), withdrawToAddress, HavenoUtils.formatXmr(receiverAmount, true), HavenoUtils.formatXmr(fee, true), xmrWalletService.getWallet().getTxNote(tx.getHash()))
                        .show();
                log.debug("onWithdraw onSuccess tx ID:{}", tx.getHash());
            }
        } catch (Exception e) {
            e.printStackTrace();
            new Popup().warning(e.getMessage()).show();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

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

    private void updateBalanceDisplay() {
        BigInteger available = xmrWalletService.getAvailableBalance();
        balanceAmountLabel.setText(HavenoUtils.formatXmr(available, true));
        String fiat = getFiatText(available);
        balanceFiatLabel.setText(fiat == null ? "" : fiat);
        balanceFiatLabel.setVisible(fiat != null);
        balanceFiatLabel.setManaged(fiat != null);
    }

    // show the fiat estimate or an over-balance error below the amount; partial input parses to 0 and shows nothing
    private void updateAmountFeedback() {
        BigInteger available = xmrWalletService.getAvailableBalance();
        BigInteger parsed = sendMax ? available : safeParse(amountField.getText());
        boolean overBalance = !sendMax && parsed.compareTo(available) > 0;
        String content = overBalance ? Res.get("funds.withdrawal.notEnoughFunds") : getFiatText(parsed);

        amountFeedbackLabel.getStyleClass().remove("send-feedback-error");
        if (overBalance) amountFeedbackLabel.getStyleClass().add("send-feedback-error");
        amountFeedbackLabel.setText(content == null ? "" : content);
        amountFeedbackLabel.setVisible(content != null);
        amountFeedbackLabel.setManaged(content != null);
    }

    private void updateSendButtonState() {
        BigInteger available = xmrWalletService.getAvailableBalance();
        BigInteger parsed = safeParse(amountField.getText());
        boolean amountValid = sendMax
                ? available.signum() > 0
                : parsed.signum() > 0 && parsed.compareTo(available) <= 0;
        sendButton.setDisable(!(isAddressValid() && amountValid));
    }

    // sync the green check and the error label; the error appears when flagged (focus out) and stays until valid
    private void updateAddressFeedback(boolean flagInvalid) {
        boolean valid = isAddressValid();
        addressCheck.setVisible(valid);
        boolean showError = !valid && !strippedAddress().isEmpty() && (flagInvalid || addressErrorLabel.isVisible());
        addressErrorLabel.setText(showError ? Res.get("validation.xmr.invalidAddress") : "");
        addressErrorLabel.setVisible(showError);
        addressErrorLabel.setManaged(showError);
    }

    private boolean isAddressValid() {
        String address = strippedAddress();
        return !address.isEmpty() && MoneroUtils.isValidAddress(address, XmrWalletService.getMoneroNetworkType());
    }

    private String strippedAddress() {
        String text = addressField.getText();
        return text == null ? "" : text.replaceAll("\\s", "");
    }

    private static Label fieldLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("send-field-label");
        return label;
    }

    // the amount approximated in the user's preferred currency, or null while no price is available
    private String getFiatText(BigInteger atomicAmount) {
        if (atomicAmount == null || atomicAmount.compareTo(BigInteger.ZERO) <= 0) return null;
        TradeCurrency currency = preferences.getPreferredTradeCurrency();
        if (currency == null) return null;
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currency.getCode());
        if (marketPrice == null || !marketPrice.isPriceAvailable()) return null;
        double fiatValue = HavenoUtils.atomicUnitsToXmr(atomicAmount) * marketPrice.getPrice();
        return "≈ " + fiatFormat.format(fiatValue) + " " + currency.getCode();
    }

    // parseXmr returns 0 for empty or unparseable input
    private static BigInteger safeParse(String input) {
        return HavenoUtils.parseXmr(input == null ? "" : input.trim());
    }

    private void reset() {
        sendMax = false;
        amount = BigInteger.ZERO;
        addressField.setText("");
        amountField.setText("");
        noteArea.setText("");
        updateAddressFeedback(false);
        updateAmountFeedback();
        updateSendButtonState();
    }
}
