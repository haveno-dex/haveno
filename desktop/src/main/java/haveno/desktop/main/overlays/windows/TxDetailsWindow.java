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

import com.google.inject.Inject;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.funds.transactions.TransactionsListItem;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Details of a wallet transaction, shown as a sent or received hero amount. */
@Slf4j
public class TxDetailsWindow extends TxHeroWindow<TxDetailsWindow> {

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private final Map<String, MemoEditor> pendingMemos = new HashMap<>();
    private TransactionsListItem item;
    private Runnable onMemoSaved;
    private MemoEditor memoEditor;

    @Inject
    public TxDetailsWindow(XmrWalletService xmrWalletService, Preferences preferences, PriceFeedService priceFeedService) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
    }

    public void show(TransactionsListItem item, Runnable onMemoSaved) {
        this.item = item;
        this.onMemoSaved = onMemoSaved;
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        MoneroTxWallet tx = item.getTx();
        boolean isOutgoing = tx.getOutgoingTransfer() != null;
        BigInteger amount = isOutgoing ? tx.getOutgoingAmount() : tx.getIncomingAmount();
        String txKey = null;
        if (isOutgoing) {
            try {
                synchronized (xmrWalletService.getWalletLock()) {
                    txKey = xmrWalletService.getWallet().getTxKey(tx.getHash());
                }
            } catch (Exception e) {
                // TODO (monero-java): wallet.getTxKey() should return null if key does not exist instead of throwing exception
            }
        }

        List<Node> groups = new ArrayList<>();
        groups.add(txStatusRow(tx.getHash(), xmrWalletService));
        groups.add(detailRow(Res.get("shared.dateTime"), selectableValue(item.getDateString())));
        groups.add(sheetGroup(Res.get(isOutgoing ? "txDetailsWindow.sentTo" : "txDetailsWindow.receivedWith"),
                selectableLabel(item.getAddressString(), "confirm-send-address"), copyIcon(item.getAddressString())));
        if (isOutgoing) groups.add(detailRow(Res.get("funds.withdrawal.confirm.networkFee"), selectableValue(HavenoUtils.formatXmr(tx.getFee(), true))));
        if (txKey != null && !txKey.isEmpty()) groups.add(sheetGroup(Res.get("txDetailsWindow.txKey"), selectableLabel(txKey, "confirm-send-address"), copyIcon(txKey)));
        groups.add(txIdGroup(tx.getHash(), preferences));
        MoneroTxWallet cachedTx = xmrWalletService.getTx(tx.getHash());
        memoEditor = new MemoEditor(tx.getHash(), cachedTx == null ? tx.getNote() : cachedTx.getNote(), onMemoSaved);
        groups.add(memoEditor);

        String fiat = GUIUtil.getFiatText(amount, priceFeedService, preferences);
        addHeroContent(
                isOutgoing
                        ? hero(MaterialDesignIcon.SEND, "confirm-send-icon", Res.get("funds.withdrawal.sent.headline"), amount, fiat)
                        : hero(MaterialDesignIcon.ARROW_DOWN, "tx-sent-icon", Res.get("txDetailsWindow.received"), amount, fiat),
                sheet(groups.toArray(new Node[0])));
    }

    @Override
    protected void addButtons() {
        super.addButtons();
        actionButton.disableProperty().bind(memoEditor.saving);
        actionButton.textProperty().bind(Bindings.when(memoEditor.saving)
                .then(Res.get("txDetailsWindow.note.saving"))
                .otherwise(Res.get("funds.withdrawal.sent.done")));
        gridPane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!memoEditor.editing.get()) return;
            Node target = (Node) event.getTarget();
            while (target != null) {
                if (target == memoEditor || target == actionButton) return;
                target = target.getParent();
            }
            memoEditor.save(() -> {});
        });
        gridPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && memoEditor.editing.get() && !memoEditor.saving.get()) {
                event.consume();
                memoEditor.cancel();
            }
        });
    }

    @Override
    protected void doClose() {
        if (memoEditor != null) memoEditor.save(() -> super.doClose());
        else super.doClose();
    }

    @Override
    protected void onHidden() {
        super.onHidden();
        memoEditor = null;
    }

    private class MemoEditor extends VBox {
        private final String txId;
        private final Runnable onSaved;
        private final SimpleStringProperty savedMemo;
        private final SimpleBooleanProperty editing = new SimpleBooleanProperty();
        private final SimpleBooleanProperty saving;
        private final TextArea memoArea = new TextArea();
        private final Label errorLabel = new AutoTooltipLabel();
        private final AutoTooltipButton addButton = new AutoTooltipButton(Res.get("txDetailsWindow.note.add"));
        private final Label editIcon = new Label();

        private MemoEditor(String txId, String memo, Runnable onSaved) {
            this.txId = txId;
            this.onSaved = onSaved;
            // reopened details share the pending write so they cannot edit a stale memo
            MemoEditor pending = pendingMemos.get(txId);
            savedMemo = pending == null ? new SimpleStringProperty(memo == null ? "" : memo) : pending.savedMemo;
            saving = pending == null ? new SimpleBooleanProperty() : pending.saving;
            disableProperty().bind(saving);

            addButton.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.PLUS, "1.1em"));
            addButton.getStyleClass().add("tx-note-add");
            addButton.setAlignment(Pos.CENTER_LEFT);
            addButton.setMaxWidth(Double.MAX_VALUE);
            VBox.setMargin(addButton, new Insets(-14, -18, -16, -18));
            addButton.setOnAction(event -> edit());

            StringBinding displayedMemo = Bindings.createStringBinding(() -> savedMemo.get().strip(), savedMemo);
            TextArea memoText = selectableLabel(displayedMemo.get(), "confirm-send-row-value");
            memoText.textProperty().bind(displayedMemo);
            editIcon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.PENCIL, "1.1em"));
            editIcon.getStyleClass().add("confirm-send-copy");
            editIcon.setCursor(Cursor.HAND);
            editIcon.setTooltip(new Tooltip(Res.get("shared.edit")));
            editIcon.setOnMouseClicked(event -> edit());
            Accessibility.asButton(editIcon, Res.get("shared.edit"));
            VBox saved = sheetGroup(Res.get("funds.withdrawal.sent.note"), memoText, editIcon);

            memoArea.setWrapText(true);
            memoArea.getStyleClass().addAll("wizard-text-area", "tx-note-editor");
            // use the same font and padding as the automatic height, capped at five lines
            Text fiveLines = new Text("\n\n\n\n");
            fiveLines.fontProperty().bind(memoArea.fontProperty());
            memoArea.maxHeightProperty().bind(Bindings.createDoubleBinding(() ->
                            fiveLines.getLayoutBounds().getHeight() + memoArea.getFont().getSize()
                                    + memoArea.getInsets().getTop() + memoArea.getInsets().getBottom(),
                    fiveLines.layoutBoundsProperty(), memoArea.insetsProperty()));
            GUIUtil.adjustHeightAutomatically(memoArea, null, false);
            memoArea.minHeightProperty().bind(Bindings.min(memoArea.prefHeightProperty(), memoArea.maxHeightProperty()));
            Accessibility.setHelp(memoArea, Res.get("funds.withdrawal.sent.note"));

            AutoTooltipButton saveButton = new AutoTooltipButton(Res.get("shared.save"));
            saveButton.getStyleClass().addAll("action-button", "tx-note-save");
            saveButton.setOnAction(event -> save(() -> {}));
            AutoTooltipButton cancelButton = new AutoTooltipButton(Res.get("shared.cancel"));
            cancelButton.getStyleClass().add("confirm-send-cancel");
            cancelButton.setOnAction(event -> cancel());
            HBox actions = new HBox(6, cancelButton, saveButton);
            actions.setAlignment(Pos.CENTER_RIGHT);
            errorLabel.setWrapText(true);
            errorLabel.getStyleClass().add("error-text");
            errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
            errorLabel.managedProperty().bind(errorLabel.visibleProperty());
            VBox editor = sheetGroup(Res.get("funds.withdrawal.sent.note"), new VBox(10, memoArea, actions, errorLabel));

            addButton.visibleProperty().bind(editing.not().and(displayedMemo.isEmpty()));
            saved.visibleProperty().bind(editing.not().and(displayedMemo.isNotEmpty()));
            editor.visibleProperty().bind(editing);
            for (Node node : List.of(addButton, saved, editor)) node.managedProperty().bind(node.visibleProperty());
            getChildren().addAll(addButton, saved, editor);
        }

        private void edit() {
            memoArea.setText(savedMemo.get());
            errorLabel.setText("");
            editing.set(true);
            memoArea.requestFocus();
            memoArea.end();
        }

        private void cancel() {
            if (saving.get()) return;
            editing.set(false);
            errorLabel.setText("");
            if (memoEditor == this && isDisplayed) (savedMemo.get().isBlank() ? addButton : editIcon).requestFocus();
        }

        private void save(Runnable afterSave) {
            if (saving.get()) return;
            String draft = memoArea.getText();
            String note = draft.strip();
            if (!editing.get() || draft.equals(savedMemo.get()) || note.equals(savedMemo.get())) {
                cancel();
                afterSave.run();
                return;
            }
            saving.set(true);
            pendingMemos.put(txId, this);
            errorLabel.setText("");
            ThreadUtils.submitToPool(() -> {
                try {
                    xmrWalletService.setTxNote(txId, note);
                } catch (Exception e) {
                    log.warn("Could not save transaction memo", e);
                    UserThread.execute(() -> {
                        pendingMemos.remove(txId);
                        saving.set(false);
                        if (memoEditor == this && isDisplayed) {
                            errorLabel.setText(Res.get("funds.tx.memo.saveFailed"));
                            memoArea.requestFocus();
                        } else {
                            new Popup().warning(Res.get("funds.tx.memo.saveFailedForTx", txId)).show();
                        }
                    });
                    return;
                }
                UserThread.execute(() -> {
                    pendingMemos.remove(txId);
                    savedMemo.set(note);
                    saving.set(false);
                    cancel();
                    onSaved.run();
                    if (memoEditor == this && isDisplayed) afterSave.run();
                });
            });
        }
    }
}
