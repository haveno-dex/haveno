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

package haveno.desktop.main.portfolio.pendingtrades.steps;

import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.user.BlockChainExplorer;
import haveno.core.user.Preferences;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import monero.wallet.model.MoneroTxWallet;

public class TradeDepositView extends VBox {
    private final Preferences preferences;
    private final Label confirmations = new Label();
    private final TextArea transactionId = new TextArea();
    private final Hyperlink copy = new Hyperlink(Res.get("portfolio.pending.tradeView.copyId"));
    private final Hyperlink explorer = new Hyperlink(Res.get("portfolio.pending.tradeView.openExplorer"));
    private final PauseTransition copyFeedback = new PauseTransition(Duration.seconds(1));
    private String txId;

    public TradeDepositView(String title, Preferences preferences) {
        this(title, preferences, true);
    }

    public TradeDepositView(String title, Preferences preferences, boolean showConfirmations) {
        this.preferences = preferences;
        getStyleClass().add("trade-deposit");
        setSpacing(10);
        setMinWidth(0);
        Label heading = new Label(title);
        heading.setWrapText(true);
        heading.getStyleClass().add("trade-field-label");
        confirmations.getStyleClass().add("trade-field-label");
        confirmations.setWrapText(true);
        confirmations.setAlignment(Pos.CENTER_RIGHT);
        confirmations.setVisible(showConfirmations);
        confirmations.setManaged(showConfirmations);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, heading, spacer, confirmations);
        header.setAlignment(Pos.TOP_LEFT);
        transactionId.setEditable(false);
        transactionId.setWrapText(true);
        transactionId.setPrefRowCount(2);
        transactionId.setMinHeight(0);
        transactionId.getStyleClass().addAll("selectable-label", "trade-deposit-id");
        GUIUtil.adjustHeightAutomatically(transactionId, null, false);
        copyFeedback.setOnFinished(event -> copy.setText(Res.get("portfolio.pending.tradeView.copyId")));
        copy.setOnAction(event -> {
            if (txId == null || txId.isEmpty()) return;
            Utilities.copyToClipboard(txId);
            copy.setText(Res.get("portfolio.pending.tradeView.copied"));
            copyFeedback.playFromStart();
        });
        explorer.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.EXTERNAL_LINK, "10"));
        explorer.getGraphic().getStyleClass().add("trade-copy-icon");
        explorer.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        explorer.setOnAction(event -> {
            BlockChainExplorer selectedExplorer = preferences.getBlockChainExplorer();
            if (txId != null && !txId.isEmpty() && selectedExplorer != null)
                GUIUtil.openWebPage(selectedExplorer.txUrl + txId, false);
        });
        HBox actions = new HBox(16, copy, explorer);
        getChildren().addAll(header, transactionId, actions);
        update(null, null, false);
    }

    public void update(MoneroTxWallet transaction, String hash, boolean unlocked) {
        txId = transaction != null && transaction.getHash() != null ? transaction.getHash() : hash;
        String text = txId == null ? "" : txId;
        if (!text.equals(transactionId.getText())) transactionId.setText(text);
        transactionId.setPromptText(Res.get("shared.na"));
        copy.setDisable(txId == null || txId.isEmpty());
        boolean hasExplorer = preferences.getBlockChainExplorer() != null;
        explorer.setVisible(hasExplorer);
        explorer.setManaged(hasExplorer);
        explorer.setDisable(copy.isDisabled());
        boolean invalid = transaction != null && Boolean.TRUE.equals(transaction.isFailed());
        confirmations.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
        confirmations.pseudoClassStateChanged(PseudoClass.getPseudoClass("confirmed"), !invalid &&
                (unlocked || transaction != null && Boolean.TRUE.equals(transaction.isConfirmed())));
        Long count = getNumConfirmations(transaction);
        if (invalid) confirmations.setText(Res.get("confidence.invalid"));
        else if (count == null)
            confirmations.setText(unlocked ? Res.get("confidence.confirmed", ">=10") : Res.get("confidence.unknown"));
        else confirmations.setText(Res.get("portfolio.pending.tradeView.depositCount", count));
    }

    static Long getNumConfirmations(MoneroTxWallet transaction) {
        if (transaction == null || Boolean.TRUE.equals(transaction.isFailed()) ||
                transaction.getNumConfirmations() == null || !Boolean.TRUE.equals(transaction.isRelayed())) return null;
        return Boolean.TRUE.equals(transaction.isConfirmed()) ? transaction.getNumConfirmations() : 0L;
    }

    public void cleanup() {
        copyFeedback.stop();
        copy.setText(Res.get("portfolio.pending.tradeView.copyId"));
    }
}
