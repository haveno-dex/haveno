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

package haveno.desktop.main.overlays.windows;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import haveno.desktop.util.Layout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroWalletListener;

import java.math.BigInteger;
import java.util.function.Function;

/** Success dialog after a withdrawal, showing the sent amount, destination, fee and tx id. */
public class TxWithdrawWindow extends Overlay<TxWithdrawWindow> {

    private final String txId;
    private final String address;
    private final BigInteger amount;
    private final BigInteger fee;
    private final String memo;
    private final Function<BigInteger, String> fiatText; // approximate fiat for an amount, or null while no price
    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;

    private TxConfidenceIndicator confidenceIndicator;
    private Tooltip confidenceTooltip;
    private MoneroWalletListener walletListener;

    public TxWithdrawWindow(String txId, String address, BigInteger amount, BigInteger fee, String memo,
                            Function<BigInteger, String> fiatText, XmrWalletService xmrWalletService, Preferences preferences) {
        type = Type.Confirmation;
        this.txId = txId;
        this.address = address;
        this.amount = amount;
        this.fee = fee;
        this.memo = memo;
        this.fiatText = fiatText;
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
    }

    @Override
    public void show() {
        width = 560;
        createGridPane();
        gridPane.setPadding(new Insets(76, 78, 74, 78)); // popup-bg reserves 44 per side for the dropshadow
        addContent();
        addButtons();
        applyStyles();
        display();
        startConfidenceUpdates();
    }

    @Override
    protected void onHidden() {
        stopConfidenceUpdates();
    }

    private void addContent() {
        // hero: success badge over the sent amount
        Label icon = new Label();
        icon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.CHECK, "1.6em"));
        icon.getStyleClass().add("tx-sent-icon");
        icon.setMinSize(50, 50);
        icon.setMaxSize(50, 50);
        icon.setAlignment(Pos.CENTER);

        Label title = new AutoTooltipLabel(Res.get("funds.withdrawal.sent.headline"));
        title.getStyleClass().add("confirm-send-title");
        Label amountLabel = new AutoTooltipLabel(HavenoUtils.formatXmr(amount, true));
        amountLabel.getStyleClass().add("confirm-send-amount");

        VBox hero = new VBox(icon, title, amountLabel);
        hero.setAlignment(Pos.CENTER);
        VBox.setMargin(title, new Insets(16, 0, 0, 0));
        VBox.setMargin(amountLabel, new Insets(8, 0, 0, 0));
        String amountFiat = fiatText.apply(amount);
        if (amountFiat != null) {
            Label fiatLabel = new AutoTooltipLabel(amountFiat);
            fiatLabel.getStyleClass().add("confirm-send-fiat");
            VBox.setMargin(fiatLabel, new Insets(3, 0, 0, 0));
            hero.getChildren().add(fiatLabel);
        }

        // destination with a copy shortcut; the address wraps in full below
        Region toSpacer = new Region();
        HBox.setHgrow(toSpacer, Priority.ALWAYS);
        HBox toRow = new HBox(rowLabel(Res.get("funds.withdrawal.confirm.to")), toSpacer, copyIcon(address));
        toRow.setAlignment(Pos.CENTER_LEFT);
        Label addressLabel = new Label(address);
        addressLabel.setWrapText(true);
        addressLabel.getStyleClass().add("confirm-send-address");
        VBox toGroup = new VBox(3, toRow, addressLabel);

        HBox feeRow = detailRow(Res.get("funds.withdrawal.confirm.networkFee"), valueLabel(HavenoUtils.formatXmr(fee, true)));

        VBox sheet = new VBox(14, toGroup, divider(), feeRow);
        sheet.getStyleClass().add("confirm-send-sheet");

        if (memo != null && !memo.isEmpty()) {
            Label memoLabel = new Label(memo);
            memoLabel.setWrapText(true);
            memoLabel.getStyleClass().add("confirm-send-row-value");
            VBox memoGroup = new VBox(3, rowLabel(Res.get("funds.withdrawal.sent.note")), memoLabel);
            sheet.getChildren().addAll(divider(), memoGroup);
        }

        // tx id with confirmation progress, block explorer and copy shortcuts
        confidenceIndicator = new TxConfidenceIndicator();
        confidenceIndicator.setFocusTraversable(false);
        confidenceIndicator.setMaxSize(20, 20);
        confidenceIndicator.setId("funds-confidence");
        confidenceIndicator.setProgress(0);
        confidenceTooltip = new Tooltip("-");
        confidenceIndicator.setTooltip(confidenceTooltip);

        Label explorerIcon = new Label();
        explorerIcon.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.EXTERNAL_LINK, "1.0em"));
        explorerIcon.getStyleClass().add("confirm-send-copy");
        explorerIcon.setCursor(Cursor.HAND);
        explorerIcon.setTooltip(new Tooltip(Res.get("txIdTextField.blockExplorerIcon.tooltip")));
        explorerIcon.setOnMouseClicked(e -> openBlockExplorer());

        Region txIdSpacer = new Region();
        HBox.setHgrow(txIdSpacer, Priority.ALWAYS);
        HBox txIdRow = new HBox(8, rowLabel(Res.get("funds.withdrawal.sent.txId")), txIdSpacer, confidenceIndicator, explorerIcon, copyIcon(txId));
        txIdRow.setAlignment(Pos.CENTER_LEFT);
        Label txIdLabel = new Label(txId);
        txIdLabel.setWrapText(true);
        txIdLabel.getStyleClass().add("confirm-send-address");
        txIdLabel.setCursor(Cursor.HAND);
        txIdLabel.setTooltip(new Tooltip(Res.get("txIdTextField.blockExplorerIcon.tooltip")));
        txIdLabel.setOnMouseClicked(e -> openBlockExplorer());
        VBox txIdGroup = new VBox(3, txIdRow, txIdLabel);
        sheet.getChildren().addAll(divider(), txIdGroup);

        VBox content = new VBox(24, hero, sheet);
        content.setFillWidth(true);
        gridPane.add(content, 0, ++rowIndex, 2, 1);
    }

    @Override
    protected void addButtons() {
        actionButton = new AutoTooltipButton(Res.get("funds.withdrawal.sent.done"));
        actionButton.setDefaultButton(true);
        actionButton.getStyleClass().add("action-button");
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.setOnAction(e -> doClose());

        VBox buttons = new VBox(actionButton);
        buttons.setFillWidth(true);
        gridPane.add(buttons, 0, ++rowIndex, 2, 1);
        GridPane.setMargin(buttons, new Insets(26, 0, 0, 0));
    }

    private void openBlockExplorer() {
        if (preferences != null) GUIUtil.openWebPage(preferences.getBlockChainExplorer().txUrl + txId, false);
    }

    private void startConfidenceUpdates() {
        walletListener = new MoneroWalletListener() {
            @Override
            public void onNewBlock(long height) {
                updateConfidence(false);
            }
        };
        xmrWalletService.addWalletListener(walletListener);
        new Thread(() -> updateConfidence(true)).start();
    }

    private void stopConfidenceUpdates() {
        if (walletListener != null) {
            xmrWalletService.removeWalletListener(walletListener);
            walletListener = null;
        }
    }

    private void updateConfidence(boolean useCache) {
        MoneroTx tx = null;
        try {
            tx = useCache ? xmrWalletService.getXmrConnectionService().getTxWithCache(txId) : xmrWalletService.getXmrConnectionService().getTx(txId);
            if (tx.getNumConfirmations() == null) { // TODO: don't set if tx.getNumConfirmations() works reliably on non-local testnet
                tx.setNumConfirmations(tx.isConfirmed() ? xmrWalletService.getXmrConnectionService().getLastInfo().getHeight() - tx.getHeight() : 0L);
            }
        } catch (Exception e) {
            // tx may not be visible to the connected node yet
        }
        MoneroTx finalTx = tx;
        UserThread.execute(() -> {
            GUIUtil.updateConfidence(finalTx, confidenceTooltip, confidenceIndicator);
            if (confidenceIndicator.getProgress() >= 1.0) stopConfidenceUpdates();
        });
    }

    private Label copyIcon(String text) {
        Label icon = new Label();
        icon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.CONTENT_COPY, "1.1em"));
        icon.getStyleClass().add("confirm-send-copy");
        icon.setCursor(Cursor.HAND);
        icon.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
        icon.setOnMouseClicked(e -> {
            Utilities.copyToClipboard(text);
            Tooltip tp = new Tooltip(Res.get("shared.copiedToClipboard"));
            tp.show((Node) e.getSource(), e.getScreenX() + Layout.PADDING, e.getScreenY() + Layout.PADDING);
            UserThread.runAfter(tp::hide, 1);
        });
        return icon;
    }

    private static Label rowLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-label");
        return label;
    }

    private static Label valueLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-value");
        return label;
    }

    private static HBox detailRow(String labelText, Node value) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(rowLabel(labelText), spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("confirm-send-divider");
        return line;
    }
}
