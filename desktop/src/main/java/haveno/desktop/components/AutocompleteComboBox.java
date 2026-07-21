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

package haveno.desktop.components;

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.skins.JFXComboBoxListViewSkin;
import haveno.common.UserThread;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.PopupWindow;
import javafx.util.Callback;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A searchable, editable combo box: typing filters the dropdown like an autocomplete.
 *
 * Clients populate it with {@link #setAutocompleteItems} instead of {@code setItems}, and observe
 * confirmed changes (a click or [ENTER], not every keystroke) via {@link #setOnChangeConfirmed}.
 *
 * @param <T> type of the item; may be a plain String
 */
@Slf4j
public class AutocompleteComboBox<T> extends JFXComboBox<T> {

    private static final int MAX_VISIBLE_ROWS = 10;

    private final JFXComboBoxListViewSkin<T> skin;
    private final ListView<T> popupList; // the dropdown's ListView, exposed by the skin
    private Pane popupMimic;             // hidden popup lookalike; also styles the width-measuring cell

    private List<? extends T> items = List.of();     // full, unfiltered items
    private List<? extends T> searchPool;            // wider pool searched once the user types (optional)
    private List<T> shownItems = new ArrayList<>();  // items currently in the popup

    private T committedValue;                        // last confirmed value, restored on an empty dismissal
    private boolean selectingAll;                    // guards the trailing key event after ctrl/cmd+A

    // Measuring the popup width runs the cell factory over every row and lags each open, so it is
    // measured once per unfiltered content set and pinned; -1 means "measure on the next unfiltered open".
    private double pinnedWidth = -1;
    private List<T> pinnedFor;

    public AutocompleteComboBox() {
        this(FXCollections.observableArrayList());
    }

    private AutocompleteComboBox(ObservableList<T> items) {
        super(items);
        setEditable(true);

        // The default skin exposes no handle on the popup ListView; the JFX skin does.
        skin = new JFXComboBoxListViewSkin<>(this);
        setSkin(skin);
        @SuppressWarnings("unchecked")
        ListView<T> content = (ListView<T>) skin.getPopupContent();
        popupList = content;

        setAutocompleteItems(items);
        cellFactoryProperty().addListener((obs, old, factory) -> unpinWidth());
        matchControlWidthToPopup();
        keepPopupOpenOnEditorClick();
        suppressSpaceKeyReset();
        clearEditorOnFocusGained();
        openPopupOnEditorClick();
        filterAsUserTypes();
        trackCommittedValue();
    }

    // --- public API ---

    /** Set the full item list (optionally with a wider pool to search while typing). Use instead of setItems(). */
    public void setAutocompleteItems(List<? extends T> items, List<? extends T> searchPool) {
        this.items = items;
        this.searchPool = searchPool;
        this.shownItems = new ArrayList<>(items);
        resetSelection();
        setItems(FXCollections.observableList(shownItems));
        getEditor().setText("");
        pinWidth(); // new content: re-fit the popup and control so they grow or shrink to match
    }

    public void setAutocompleteItems(List<? extends T> items) {
        setAutocompleteItems(items, null);
    }

    /**
     * Run the handler when a value change is <i>confirmed</i> - a dropdown click or [ENTER] - unlike
     * onAction, which fires on every unconfirmed change and so does not suit a searchable combo.
     */
    public final void setOnChangeConfirmed(EventHandler<Event> handler) {
        setOnHidden(e -> {
            String text = getEditor().getText();

            // Confirmed when the editor text resolves to the selected item.
            T selected = getSelectionModel().getSelectedItem();
            if (selected != null && selected.equals(getConverter().fromString(text))) {
                handler.handle(e);
                getParent().requestFocus();
                return;
            }

            // An empty editor also confirms (a cleared filter). A click on the editor keeps it blank for
            // typing; any other dismissal restores the committed value.
            if (text.isEmpty()) {
                handler.handle(e);
                if (!getEditor().isHover()) {
                    getParent().requestFocus();
                    restoreCommittedValue();
                }
            }
        });
    }

    // --- focus and click behavior ---

    // A new focus starts a fresh search, so clear the editor. Unfiltered, the rows are already current:
    // open first and refresh them just after painting, since rebuilding before the popup shows delays
    // opening. Filtered, drop the filter to restore the full list.
    private void clearEditorOnFocusGained() {
        getEditor().focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
            if (hadFocus || !hasFocus) return;
            if (isUnfiltered()) {
                resetSelection();
                getEditor().setText("");
                popupList.scrollTo(0);
                openPopup();
                UserThread.execute(this::refreshShownRows);
            } else {
                removeFilter();
                openPopup();
            }
        });
    }

    // Re-set the current items so the visible rows rebuild with the latest dynamic content (e.g. offer counts).
    private void refreshShownRows() {
        if (!isShowing()) return;
        shownItems = new ArrayList<>(items);
        setItems(FXCollections.observableList(shownItems));
    }

    // The editor does not toggle the popup itself; open it on a click when closed. The popup owner node
    // stops an editor click from auto-hiding an open popup, so this only opens it from closed.
    private void openPopupOnEditorClick() {
        getEditor().addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && !isShowing() && !shownItems.isEmpty())
                show();
        });
    }

    // --- filtering ---

    private void filterAsUserTypes() {
        getEditor().addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            KeyCode code = e.getCode();
            if (code == KeyCode.CONTROL || code == KeyCode.COMMAND || code == KeyCode.META) {
                e.consume();
                return;
            }

            // ctrl/cmd+A selects all; swallow the standalone 'A' that can follow the modifier.
            if (code == KeyCode.A && (e.isControlDown() || e.isMetaDown())) {
                getEditor().selectAll();
                selectingAll = true;
                e.consume();
                return;
            }
            if (code == KeyCode.A && selectingAll) {
                selectingAll = false;
                e.consume();
                return;
            }

            UserThread.execute(() -> {
                String query = getEditor().getText();
                if (items.stream().anyMatch(item -> asString(item).equalsIgnoreCase(query))) return;
                if (query.isEmpty()) removeFilter();
                else filterBy(query);
                openPopup();
            });
        });
    }

    private void filterBy(String query) {
        List<? extends T> pool = searchPool != null && !query.isEmpty() ? searchPool : items;
        shownItems = pool.stream()
                .filter(item -> StringUtils.containsIgnoreCase(asString(item), query))
                .collect(Collectors.toList());
        resetSelection();
        setItems(FXCollections.observableList(shownItems));
        int caret = Math.min(getEditor().getCaretPosition(), query.length()); // read before setText resets it
        getEditor().setText(query);
        getEditor().positionCaret(caret);
    }

    private void removeFilter() {
        shownItems = new ArrayList<>(items);
        resetSelection();
        setItems(FXCollections.observableList(shownItems));
        getEditor().setText("");
    }

    // --- committed value ---

    // Remember the last confirmed value and restore it when the editor loses focus with unmatched text.
    private void trackCommittedValue() {
        valueProperty().addListener((obs, old, value) -> {
            if (value != null) committedValue = value;
        });
        getEditor().focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && !getItems().contains(getConverter().fromString(getEditor().getText())))
                restoreCommittedValue();
        });
    }

    private void restoreCommittedValue() {
        UserThread.execute(() -> {
            getSelectionModel().select(committedValue);
            getEditor().setText(asString(committedValue));
        });
    }

    // --- popup plumbing ---

    // Before the popup first shows, the skin keeps its ListView as a plain hidden child of this control
    // and sizes the control to it; without the popup's ".combo-box-popup" styling the measured width
    // differs, so the control would resize on first open. Nest the ListView in a hidden mimic of the
    // popup so it always measures as it will render; the popup reparents it out on first show.
    private void matchControlWidthToPopup() {
        popupMimic = new Pane(popupList);
        popupMimic.getStyleClass().add("combo-box-popup");
        popupMimic.setManaged(false);
        popupMimic.setVisible(false);
        getChildren().add(popupMimic);
    }

    // The skin shows the popup owned only by its window, so JavaFX auto-hides it on any press outside
    // the window - including in the editor, which blinks the list shut then open. Make this combo the
    // popup's owner node so presses inside the combo skip auto-hide while outside presses still dismiss it.
    private void keepPopupOpenOnEditorClick() {
        popupList.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null && scene.getWindow() instanceof PopupWindow popup && popup.getOwnerNode() == null)
                setPopupOwnerNode(popup);
        });
    }

    // PopupWindow.ownerNode is read-only except through the Node-owner show() overload the skin never uses.
    private void setPopupOwnerNode(PopupWindow popup) {
        try {
            Field field = PopupWindow.class.getDeclaredField("ownerNode");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ReadOnlyObjectWrapper<Node> ownerNode = (ReadOnlyObjectWrapper<Node>) field.get(popup);
            ownerNode.set(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Could not set dropdown popup owner node; list may blink on editor click", e);
        }
    }

    // SPACE inside the popup otherwise resets the editor text; swallow it.
    private void suppressSpaceKeyReset() {
        popupList.addEventFilter(KeyEvent.ANY, e -> {
            if (e.getCode() == KeyCode.SPACE) e.consume();
        });
    }

    // --- popup sizing and open ---

    // Open the popup at the right size, reusing the pinned unfiltered width to avoid re-measuring every
    // open; filtered rows can be wider, so they size freely.
    private void openPopup() {
        setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, shownItems.size()));
        if (shownItems.isEmpty()) {
            hide();
            return;
        }

        boolean unfiltered = isUnfiltered();
        if (unfiltered) pinWidth(); // re-measures only if the content changed since the last pin
        setPopupPrefWidth(unfiltered && pinnedWidth > 0 ? pinnedWidth : Region.USE_COMPUTED_SIZE);

        // Flush the row count before the popup autosizes, else a stale (smaller) count caps its height
        // and a freshly grown list opens shorter than its max rows.
        popupList.applyCss();
        popupList.layout();
        popupList.autosize();
        show();
    }

    // Pin the popup to its measured content width, not the rendered width the skin ratchets up.
    private void pinWidth() {
        if (shownItems.equals(pinnedFor)) return;
        Callback<ListView<T>, ListCell<T>> cellFactory = popupList.getCellFactory();
        if (getScene() == null || cellFactory == null) { // not styleable yet; measure on the first open
            unpinWidth();
            return;
        }
        pinnedWidth = measureContentWidth(cellFactory);
        pinnedFor = new ArrayList<>(shownItems);
        setPopupPrefWidth(pinnedWidth);
    }

    private void unpinWidth() {
        pinnedWidth = -1;
        pinnedFor = null;
        setPopupPrefWidth(Region.USE_COMPUTED_SIZE);
    }

    // Widest row via a scratch cell in the popup mimic (skin formula: max cell + 30, floor 50), plus the scrollbar breadth when it scrolls.
    private double measureContentWidth(Callback<ListView<T>, ListCell<T>> cellFactory) {
        ListCell<T> cell = cellFactory.call(popupList);
        cell.updateListView(popupList);
        popupMimic.getChildren().add(cell);
        try {
            double width = 0;
            for (int i = 0; i < popupList.getItems().size(); i++) {
                cell.updateIndex(i);
                cell.applyCss();
                width = Math.max(width, cell.prefWidth(-1));
            }
            if (popupList.getItems().size() > MAX_VISIBLE_ROWS) width += scrollBarBreadth();
            return Math.max(50, width + 30);
        } finally {
            popupMimic.getChildren().remove(cell);
        }
    }

    private double scrollBarBreadth() {
        for (Node node : popupList.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL)
                return bar.prefWidth(-1);
        }
        return 0;
    }

    private void setPopupPrefWidth(double width) {
        popupList.setPrefWidth(width);
        requestLayout(); // the popup adopts the ListView out of this control's tree, so re-measure explicitly
    }

    // --- small helpers ---

    private void resetSelection() {
        setValue(null);
        getSelectionModel().clearSelection();
    }

    private boolean isUnfiltered() {
        return shownItems.equals(items);
    }

    private String asString(T item) {
        return getConverter().toString(item);
    }
}
