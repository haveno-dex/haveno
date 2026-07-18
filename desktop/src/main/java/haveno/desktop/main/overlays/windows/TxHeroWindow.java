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

/** Base for tx popups: a badge and hero amount over an inset detail sheet. */
public abstract class TxHeroWindow<T extends TxHeroWindow<T>> extends Overlay<T> {

    private TxConfidenceIndicator confidenceIndicator;
    private Tooltip confidenceTooltip;
    private MoneroWalletListener walletListener;
    private XmrWalletService xmrWalletService;
    private String confidenceTxId;

    protected TxHeroWindow() {
        type = Type.Confirmation;
    }

    protected abstract void addContent();

    protected void showHeroWindow() {
        rowIndex = -1; // reused windows would otherwise accumulate empty leading rows
        width = 560;
        createGridPane();
        gridPane.setPadding(new Insets(76, 78, 74, 78)); // popup-bg reserves 44 per side for the dropshadow
        addContent();
        addButtons();
        applyStyles();
        display();
    }

    // full-width primary close; confirmation-style windows override with their own buttons
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

    @Override
    protected void onHidden() {
        stopConfidenceUpdates();
    }

    protected void addHeroContent(Node... blocks) {
        VBox content = new VBox(24, blocks);
        content.setFillWidth(true);
        gridPane.add(content, 0, ++rowIndex, 2, 1);
    }

    protected static VBox hero(MaterialDesignIcon glyph, String badgeStyle, String titleText, BigInteger amount, String fiat) {
        Label badge = new Label();
        badge.setGraphic(GlyphsDude.createIcon(glyph, "1.6em"));
        badge.getStyleClass().add(badgeStyle);
        badge.setMinSize(50, 50);
        badge.setMaxSize(50, 50);
        badge.setAlignment(Pos.CENTER);

        Label title = new AutoTooltipLabel(titleText);
        title.getStyleClass().add("confirm-send-title");
        Label amountLabel = new AutoTooltipLabel(HavenoUtils.formatXmr(amount, true));
        amountLabel.getStyleClass().add("confirm-send-amount");

        VBox hero = new VBox(badge, title, amountLabel);
        hero.setAlignment(Pos.CENTER);
        VBox.setMargin(title, new Insets(16, 0, 0, 0));
        VBox.setMargin(amountLabel, new Insets(8, 0, 0, 0));
        if (fiat != null) {
            Label fiatLabel = new AutoTooltipLabel(fiat);
            fiatLabel.getStyleClass().add("confirm-send-fiat");
            VBox.setMargin(fiatLabel, new Insets(3, 0, 0, 0));
            hero.getChildren().add(fiatLabel);
        }
        return hero;
    }

    // groups joined by hairline dividers
    protected static VBox sheet(Node... groups) {
        VBox sheet = new VBox(14);
        for (Node group : groups) {
            if (!sheet.getChildren().isEmpty()) sheet.getChildren().add(divider());
            sheet.getChildren().add(group);
        }
        sheet.getStyleClass().add("confirm-send-sheet");
        return sheet;
    }

    // a label row with trailing icons and the value wrapping in full below
    protected static VBox sheetGroup(String labelText, Label value, Node... icons) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, rowLabel(labelText), spacer);
        header.getChildren().addAll(icons);
        header.setAlignment(Pos.CENTER_LEFT);
        return new VBox(3, header, value);
    }

    protected static HBox detailRow(String labelText, Node value) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(rowLabel(labelText), spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    protected static Label rowLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-label");
        return label;
    }

    protected static Label valueLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-value");
        return label;
    }

    protected static Label wrappedLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(styleClass);
        return label;
    }

    protected static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("confirm-send-divider");
        return line;
    }

    protected static Label copyIcon(String text) {
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

    // tx id with confirmation progress, block explorer and copy shortcuts; updates until fully confirmed or hidden
    protected VBox txIdGroup(String txId, XmrWalletService xmrWalletService, Preferences preferences) {
        this.xmrWalletService = xmrWalletService;
        this.confidenceTxId = txId;

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
        explorerIcon.setOnMouseClicked(e -> openBlockExplorer(txId, preferences));

        Label txIdLabel = wrappedLabel(txId, "confirm-send-address");
        txIdLabel.setCursor(Cursor.HAND);
        txIdLabel.setTooltip(new Tooltip(Res.get("txIdTextField.blockExplorerIcon.tooltip")));
        txIdLabel.setOnMouseClicked(e -> openBlockExplorer(txId, preferences));

        startConfidenceUpdates();
        return sheetGroup(Res.get("funds.withdrawal.sent.txId"), txIdLabel, confidenceIndicator, explorerIcon, copyIcon(txId));
    }

    private static void openBlockExplorer(String txId, Preferences preferences) {
        if (preferences != null) GUIUtil.openWebPage(preferences.getBlockChainExplorer().txUrl + txId, false);
    }

    private void startConfidenceUpdates() {
        if (xmrWalletService == null) return;
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
            tx = useCache ? xmrWalletService.getXmrConnectionService().getTxWithCache(confidenceTxId) : xmrWalletService.getXmrConnectionService().getTx(confidenceTxId);
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
}
