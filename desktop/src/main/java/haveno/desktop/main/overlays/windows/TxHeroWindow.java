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
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import haveno.desktop.util.Layout;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.text.TextAlignment;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
    private Label confidenceLabel;
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
        onShow();
    }

    @Override
    protected void onShow() {
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
        return hero(glyph, badgeStyle, titleText, HavenoUtils.formatXmr(amount, true), fiat);
    }

    protected static VBox hero(MaterialDesignIcon glyph, String badgeStyle, String titleText, String amountText, String fiat) {
        Label amountLabel = new AutoTooltipLabel(amountText);
        amountLabel.getStyleClass().add("confirm-send-amount");
        VBox hero = hero(glyph, badgeStyle, titleText, amountLabel, fiat);
        VBox.setMargin(amountLabel, new Insets(8, 0, 0, 0));
        return hero;
    }

    protected static VBox hero(MaterialDesignIcon glyph, String badgeStyle, String titleText, Node amount, String fiat) {
        Label badge = new Label();
        badge.setGraphic(GlyphsDude.createIcon(glyph, "1.6em"));
        badge.getStyleClass().add(badgeStyle);
        badge.setMinSize(50, 50);
        badge.setMaxSize(50, 50);
        badge.setAlignment(Pos.CENTER);

        Label title = new AutoTooltipLabel(titleText);
        title.getStyleClass().add("confirm-send-title");
        title.setWrapText(true);
        title.setTextAlignment(TextAlignment.CENTER);
        VBox hero = new VBox(badge, title);
        if (amount != null) hero.getChildren().add(amount);
        hero.setAlignment(Pos.CENTER);
        VBox.setMargin(title, new Insets(16, 0, 0, 0));
        if (amount != null) VBox.setMargin(amount, new Insets(6, 0, 0, 0));
        if (fiat != null) {
            Label fiatLabel = new AutoTooltipLabel(fiat);
            fiatLabel.getStyleClass().add("confirm-send-fiat");
            fiatLabel.setWrapText(true);
            fiatLabel.setTextAlignment(TextAlignment.CENTER);
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
    protected static VBox sheetGroup(String labelText, Node value, Node... icons) {
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

    // single-line counterpart of selectableLabel; hugs its text so it stays flush right in a detail row
    protected static TextField selectableValue(String text) {
        TextField field = new TextField(text);
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setAlignment(Pos.CENTER_RIGHT);
        field.setMaxWidth(Region.USE_PREF_SIZE); // keep the click target hugging the text when a parent would stretch it
        field.getStyleClass().addAll("selectable-label", "confirm-send-row-value");
        field.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            Node textNode = field.lookup(".text");
            if (textNode == null) return;
            field.prefWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.ceil(textNode.getLayoutBounds().getWidth()) + field.snappedLeftInset() + field.snappedRightInset() + 2,
                    textNode.layoutBoundsProperty()));
        });
        return field;
    }

    // read-only text area styled as a plain label so the value text can be selected
    protected static TextArea selectableLabel(String text, String styleClass) {
        return selectableLabel(text, styleClass, false);
    }

    protected static TextArea selectableLabel(String text, String styleClass, boolean compact) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setFocusTraversable(false);
        area.getStyleClass().addAll("selectable-label", styleClass);
        GUIUtil.adjustHeightAutomatically(area, null, !compact, compact ? 0d : null);
        return area;
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
        Accessibility.asButton(icon, Res.get("shared.copyToClipboard"));
        return icon;
    }

    // status updates until fully confirmed or hidden
    protected HBox txStatusRow(String txId, XmrWalletService xmrWalletService) {
        this.xmrWalletService = xmrWalletService;
        this.confidenceTxId = txId;
        confidenceIndicator = new TxConfidenceIndicator(0);
        confidenceIndicator.setMinSize(15, 15);
        confidenceIndicator.setMaxSize(15, 15);
        confidenceLabel = new Label(Res.get("confidence.unknown"), confidenceIndicator);
        confidenceLabel.getStyleClass().add("tx-status");
        startConfidenceUpdates();
        return detailRow(Res.get("txDetailsWindow.status"), confidenceLabel);
    }

    protected VBox txIdGroup(String txId, Preferences preferences) {
        Label explorerIcon = new Label();
        explorerIcon.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.EXTERNAL_LINK, "1.0em"));
        explorerIcon.getStyleClass().add("confirm-send-copy");
        explorerIcon.setCursor(Cursor.HAND);
        explorerIcon.setTooltip(new Tooltip(Res.get("txIdTextField.blockExplorerIcon.tooltip")));
        explorerIcon.setOnMouseClicked(e -> openBlockExplorer(txId, preferences));
        Accessibility.asButton(explorerIcon, Res.get("txIdTextField.blockExplorerIcon.tooltip"));

        return sheetGroup(Res.get("funds.withdrawal.sent.txId"), selectableLabel(txId, "confirm-send-address"),
                explorerIcon, copyIcon(txId));
    }

    private static void openBlockExplorer(String txId, Preferences preferences) {
        if (preferences != null) GUIUtil.openWebPage(preferences.getBlockChainExplorer().txUrl + txId, false);
    }

    private void startConfidenceUpdates() {
        if (xmrWalletService == null) return;
        stopConfidenceUpdates(); // content can be rebuilt before the window is hidden
        MoneroWalletListener listener = new MoneroWalletListener() {
            @Override
            public void onNewBlock(long height) {
                updateConfidence(false, this);
            }
        };
        walletListener = listener;
        xmrWalletService.addWalletListener(listener);
        new Thread(() -> updateConfidence(true, listener)).start();
    }

    private void stopConfidenceUpdates() {
        if (walletListener != null) {
            xmrWalletService.removeWalletListener(walletListener);
            walletListener = null;
        }
    }

    private void updateConfidence(boolean useCache, MoneroWalletListener listener) {
        MoneroTx tx = null;
        try {
            tx = useCache ? xmrWalletService.getXmrConnectionService().getTxWithCache(confidenceTxId) : xmrWalletService.getXmrConnectionService().getTx(confidenceTxId);
            if (tx != null && tx.getNumConfirmations() == null) { // TODO: don't set if tx.getNumConfirmations() works reliably on non-local testnet
                tx.setNumConfirmations(Boolean.TRUE.equals(tx.isConfirmed()) ? xmrWalletService.getXmrConnectionService().getLastInfo().getHeight() - tx.getHeight() : 0L);
            }
        } catch (Exception e) {
            // tx may not be visible to the connected node yet
        }
        MoneroTx finalTx = tx;
        UserThread.execute(() -> {
            if (walletListener != listener) return; // superseded by rebuilt content or hidden
            updateStatus(finalTx);
        });
    }

    private void updateStatus(MoneroTx tx) {
        boolean failed = tx != null && Boolean.TRUE.equals(tx.isFailed());
        boolean known = tx != null && tx.getNumConfirmations() != null && Boolean.TRUE.equals(tx.isRelayed());
        long confirmations = known && Boolean.TRUE.equals(tx.isConfirmed()) ? Math.max(0, tx.getNumConfirmations()) : 0;
        boolean confirmed = !failed && known && confirmations >= XmrWalletService.NUM_BLOCKS_UNLOCK;
        confidenceLabel.setText(failed ? Res.get("confidence.invalid")
                : !known ? Res.get("confidence.unknown")
                : confirmed ? Res.get("txDetailsWindow.status.confirmed")
                : Res.get("txDetailsWindow.status.confirmations", confirmations, XmrWalletService.NUM_BLOCKS_UNLOCK));
        confidenceLabel.pseudoClassStateChanged(PseudoClass.getPseudoClass("confirmed"), confirmed);
        confidenceLabel.pseudoClassStateChanged(PseudoClass.getPseudoClass("failed"), failed);
        confidenceIndicator.setProgress((double) confirmations / XmrWalletService.NUM_BLOCKS_UNLOCK);
        confidenceLabel.setGraphic(confirmed ? GlyphsDude.createIcon(MaterialDesignIcon.CHECK_CIRCLE, "1.1em") : confidenceIndicator);
        if (confirmed) stopConfidenceUpdates();
    }
}
