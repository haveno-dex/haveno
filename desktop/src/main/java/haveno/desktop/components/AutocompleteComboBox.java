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
import haveno.common.util.Utilities;
import haveno.desktop.util.Accessibility;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Rectangle2D;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextInputControlSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Screen;
import javafx.util.Callback;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A searchable, editable combo box: typing filters the dropdown like an autocomplete.
 *
 * Clients populate it with {@link #setAutocompleteItems} instead of {@code setItems}, and observe
 * confirmed changes (a click, [ENTER], or leaving the field with text that names an item)
 * via {@link #setOnChangeConfirmed}, rather than on every keystroke.
 *
 * @param <T> type of the item; may be a plain String
 */
@Slf4j
public class AutocompleteComboBox<T> extends JFXComboBox<T> {

    private static final int MAX_VISIBLE_ROWS = 10;
    private static final double EDITOR_GRAPHIC_GAP = 8; // space between the editor graphic and its text
    private static final double FIT_SLACK = 2; // keeps the caret and the last glyph of a fitted value unclipped
    private static final double FIT_SEARCH_MAX_WIDTH = 180; // most width a typed query widens a fitted control to

    private final JFXComboBoxListViewSkin<T> skin;
    private final ListView<T> popupList; // the dropdown's ListView, exposed by the skin
    private Pane popupMimic;             // hidden popup lookalike; also styles the width-measuring cell

    private List<? extends T> items = List.of();     // full, unfiltered items
    private List<? extends T> searchPool;            // wider pool searched once the user types (optional)
    private List<T> shownItems = new ArrayList<>();  // items currently in the popup
    private Function<String, Predicate<T>> queryFilter; // custom query-to-item matcher (optional)
    private Function<T, Node> editorGraphicFactory;  // graphic before the editor text, e.g. a logo (optional)
    private Node editorGraphic;                      // shown graphic, an unmanaged child laid out in the editor's padding
    private T editorGraphicFor;                      // value the shown graphic was made for
    private final String editorStyle;                // the skin's inline editor style, kept under the graphic padding
    private double fitMinWidth = -1;                 // least width when sized to the chosen value; negative sizes to the popup
    private double fittedWidth;                      // width fitting the chosen value, or the widest text of a search underway

    private T committedValue;                        // last confirmed value, restored on an empty dismissal
    private T valueBeforeSearch;                     // value to return to when a search is canceled with [ESC]
    private boolean canceling;                       // set while [ESC] closes the popup, so the close is not a confirmation
    private EventHandler<Event> changeConfirmedHandler; // run when a value change is confirmed
    private boolean selectingAll;                    // guards the trailing key event after ctrl/cmd+A

    // Measuring the popup width runs the cell factory over every row and lags each open, so it is
    // measured once per unfiltered content set and pinned; -1 means "measure on the next unfiltered open".
    private double pinnedWidth = -1;
    private List<T> pinnedFor;

    private boolean popupAbove;       // popup side chosen at open, kept while showing
    private boolean popupSideDecided; // reset between opens; a press can show the popup before openPopup runs

    public AutocompleteComboBox() {
        this(FXCollections.observableArrayList());
    }

    private AutocompleteComboBox(ObservableList<T> items) {
        super(items);
        setEditable(true);

        // expose the floating prompt to screen readers
        promptTextProperty().addListener((o, oldVal, newVal) -> setAccessibleHelp(newVal));

        // The default skin exposes no handle on the popup ListView; the JFX skin does.
        skin = new JFXComboBoxListViewSkin<>(this);
        setSkin(skin);
        @SuppressWarnings("unchecked")
        ListView<T> content = (ListView<T>) skin.getPopupContent();
        popupList = content;
        editorStyle = getEditor().getStyle();

        setAutocompleteItems(items);
        cellFactoryProperty().addListener((obs, old, factory) -> unpinWidth());
        converterProperty().addListener((obs, old, converter) -> unpinWidth()); // a converter set after the items also changes row width
        matchControlWidthToPopup();
        keepPopupOpenOnEditorClick();
        redecidePopupSideOnHide();
        suppressSpaceKeyReset();
        clearEditorOnFocusGained();
        openPopupOnEditorClick();
        filterAsUserTypes();
        trackCommittedValue();
        cancelSearchOnEscape();
        selectArrowedItemText();
        showEditorGraphic();
        stopCaretOnShownValue();
        refitOnChange();
    }

    // Report non-editable to accessibility on mac: the editable combo maps to an AXComboBox, which
    // VoiceOver never announces (JDK-8087757); the pop-up button role announces each arrowed item.
    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.EDITABLE && Utilities.isOSX()) return false;
        return super.queryAccessibleAttribute(attribute, parameters);
    }

    // In fit mode the control is as wide as the chosen value needs. A search starts at that width and only widens,
    // as far as its text needs, so nothing moves on opening the popup and a value pressed or arrowed to is never cut.
    @Override
    protected double computePrefWidth(double height) {
        if (fitMinWidth < 0) return super.computePrefWidth(height);
        T value = getValue();
        TextField editor = getEditor();
        boolean showsValue = value != null && asString(value).equals(editor.getText());
        boolean searching = isShowing() || editor.isFocused() && !showsValue; // the popup closes while a query matches nothing
        if (searching || showsValue) {
            Text text = new Text(editor.getText());
            text.setFont(editor.getFont());
            Node arrow = lookup(".arrow-button");
            double width = snapSizeX(snappedLeftInset() + editor.getPadding().getLeft() + text.getLayoutBounds().getWidth() + FIT_SLACK
                    + (arrow == null ? 0 : arrow.prefWidth(-1)) + snappedRightInset());
            if (!showsValue) width = Math.min(width, FIT_SEARCH_MAX_WIDTH); // a longer query scrolls
            fittedWidth = searching ? Math.max(fittedWidth, width) : width;
        } else if (value == null && committedValue == null) {
            fittedWidth = 0; // nothing chosen and nothing to restore, so a finished search leaves only the floor
        }
        return Math.max(fitMinWidth, fittedWidth); // the floor alone until a value has been measured
    }

    // Place the editor graphic in the left padding the editor reserves for it.
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (editorGraphic == null) return;
        TextField editor = getEditor();
        editorGraphic.autosize();
        editorGraphic.relocate(snapPositionX(editor.getLayoutX()),
                snapPositionY(editor.getLayoutY() + (editor.getHeight() - editorGraphic.getLayoutBounds().getHeight()) / 2));
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

    /** Override how a typed query matches items (e.g. to search countries or currencies); default matches the display string. */
    public void setQueryFilter(Function<String, Predicate<T>> queryFilter) {
        this.queryFilter = queryFilter;
    }

    /** Size the control to the chosen value's text, at least minWidth wide, rather than to the popup. */
    public void setFitToValue(double minWidth) {
        fitMinWidth = minWidth;
        requestLayout();
    }

    /** Show a graphic before the editor text while it shows a value; the factory may return null for none. */
    public void setEditorGraphic(Function<T, Node> factory) {
        editorGraphicFactory = factory;
        editorGraphicFor = null; // rebuild for the shown value
        updateEditorGraphic();
    }

    /**
     * Run the handler when a value change is <i>confirmed</i> - a dropdown click, [ENTER], or leaving the field
     * with text that names an item - unlike onAction, which fires on every unconfirmed change and so does not
     * suit a searchable combo.
     */
    public final void setOnChangeConfirmed(EventHandler<Event> handler) {
        changeConfirmedHandler = handler;
        setOnHidden(e -> {
            if (canceling) return;
            String text = getEditor().getText();

            // Confirmed when the editor text resolves to the selected item.
            T selected = getSelectionModel().getSelectedItem();
            if (selected != null && selected.equals(getConverter().fromString(text))) {
                confirmChange(handler, e);
                getParent().requestFocus();
                return;
            }

            // An empty editor also confirms (a cleared filter). A click on the editor keeps it blank for
            // typing; any other dismissal restores the committed value.
            if (text.isEmpty()) {
                confirmChange(handler, e);
                if (!getEditor().isHover()) {
                    getParent().requestFocus();
                    UserThread.execute(this::restoreCommittedValue);
                }
            }
        });
    }

    // Run the handler; the value it leaves is the one a canceled search returns to.
    private void confirmChange(EventHandler<Event> handler, Event event) {
        handler.handle(event);
        if (getValue() != null) valueBeforeSearch = getValue();
    }

    // --- focus and click behavior ---

    // A new focus starts a fresh search, so clear the editor. Unfiltered, the rows are already current:
    // open first and refresh them just after painting, since rebuilding before the popup shows delays
    // opening. Filtered, drop the filter to restore the full list.
    private void clearEditorOnFocusGained() {
        getEditor().focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
            if (hadFocus || !hasFocus) return;
            valueBeforeSearch = committedValue;
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
                T value = getValue();
                if (value != null && getItems().contains(value) && asString(value).equals(query)) return; // editor shows the arrowed or committed item
                if (query.isBlank()) removeFilter();
                else filterBy(query);
                openPopup();
            });
        });
    }

    private void filterBy(String query) {
        List<? extends T> pool = searchPool != null && !query.isEmpty() ? searchPool : items;
        String trimmed = query.strip();
        Predicate<T> matchesQuery = queryFilter != null ? queryFilter.apply(trimmed)
                : item -> StringUtils.containsIgnoreCase(asString(item), trimmed);
        shownItems = pool.stream()
                .filter(matchesQuery)
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
            if (focused) return;
            if (!getItems().contains(getConverter().fromString(getEditor().getText())))
                restoreCommittedValue();
            confirmTypedValue(); // before the next field starts its search
        });
    }

    // Leaving the editor commits text that names an item, as [ENTER] does. Confirm that item unless the handler
    // already ran for it, so the view is told about every listed value the control ends up showing.
    private void confirmTypedValue() {
        T value = getValue();
        if (changeConfirmedHandler == null || value == null || getEditor().isFocused()) return;
        if (asString(value).equals(asString(valueBeforeSearch))) return; // the same choice, even if its item was rebuilt meanwhile
        if (items.contains(value) || searchPool != null && searchPool.contains(value))
            confirmChange(changeConfirmedHandler, new ActionEvent());
    }

    // [ESC] cancels a search: close the list without confirming, show the value from before the search and leave the editor.
    // An open popup gets the key before this control and would close as a confirmation, so its list is filtered too.
    private void cancelSearchOnEscape() {
        EventHandler<KeyEvent> cancel = e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            e.consume();
            canceling = true;
            hide();
            canceling = false;
            removeFilter(); // the full list, so the restored text resolves to its item when focus leaves
            committedValue = valueBeforeSearch;
            getSelectionModel().select(valueBeforeSearch);
            getEditor().setText(asString(valueBeforeSearch));
            getParent().requestFocus();
        };
        addEventFilter(KeyEvent.KEY_PRESSED, cancel);
        popupList.addEventFilter(KeyEvent.KEY_PRESSED, cancel);
    }

    private void restoreCommittedValue() {
        getSelectionModel().select(committedValue);
        getEditor().setText(asString(committedValue));
    }

    // --- editor graphic ---

    private void showEditorGraphic() {
        valueProperty().addListener((obs, old, value) -> updateEditorGraphic());
        getEditor().textProperty().addListener((obs, old, text) -> updateEditorGraphic());
    }

    // Show the graphic only while the editor shows the value's text, so typing a search starts from a bare field.
    private void updateEditorGraphic() {
        T value = getValue();
        T shown = editorGraphicFactory != null && value != null && asString(value).equals(getEditor().getText()) ? value : null;
        if (shown == null ? editorGraphic == null : shown.equals(editorGraphicFor)) return;
        getChildren().remove(editorGraphic);
        editorGraphicFor = shown;
        editorGraphic = shown == null ? null : editorGraphicFactory.apply(shown);
        if (editorGraphic != null) {
            editorGraphic.setManaged(false);
            editorGraphic.setMouseTransparent(true); // clicks reach the editor beneath
            Accessibility.mute(editorGraphic); // the editor text already names the value
            getChildren().add(editorGraphic);
        }
        // inline important outranks the stylesheet's important zero editor padding
        getEditor().setStyle(editorGraphic == null ? editorStyle
                : editorStyle + "-fx-padding: 0 0 0 " + (editorGraphic.prefWidth(-1) + EDITOR_GRAPHIC_GAP) + " !important;");
        requestLayout(); // an unmanaged child does not request it, and a same width swap leaves the style unchanged
    }

    // The caret invites typing a query, so stop it when the editor shows a chosen value, as after a row press;
    // editing or a fresh focus restarts it.
    private void stopCaretOnShownValue() {
        InvalidationListener stopCaret = obs -> {
            T value = getValue();
            if (value != null && asString(value).equals(getEditor().getText()) && getEditor().getSkin() instanceof TextInputControlSkin<?> editorSkin)
                editorSkin.setCaretAnimating(false);
        };
        valueProperty().addListener(stopCaret); // the skin leaves matching text untouched, so the text alone can miss a selection
        getEditor().textProperty().addListener(stopCaret);
    }

    // A fitted control is measured from what the editor shows, so measure again when that may have changed.
    private void refitOnChange() {
        InvalidationListener refit = obs -> {
            if (fitMinWidth >= 0) requestLayout();
        };
        valueProperty().addListener(refit);
        getEditor().textProperty().addListener(refit);
        getEditor().focusedProperty().addListener(refit);
        showingProperty().addListener(refit);
        // the skin repositions an open popup when this control resizes; a change listener runs after it and keeps the popup's side
        widthProperty().addListener((obs, old, width) -> realignOpenPopup());
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

    // The popup side is re-decided per open. A mouse press shows the popup before openPopup runs, so
    // isShowing() cannot tell a fresh open from a filter update; track the decision explicitly instead.
    private void redecidePopupSideOnHide() {
        showingProperty().addListener((obs, was, showing) -> {
            if (!showing) popupSideDecided = false;
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

    // macOS VoiceOver ignores the editable combo's value changes (JDK-8087757), so select the arrowed item's text in
    // the popup's key filter, like native mac combos; the screen reader then announces it as a selection change.
    private void selectArrowedItemText() {
        popupList.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode code = e.getCode();
            if (code != KeyCode.UP && code != KeyCode.DOWN && code != KeyCode.KP_UP && code != KeyCode.KP_DOWN) return;
            UserThread.execute(() -> { // select after the skin has put the arrowed item's text in the editor
                if (isShowing()) getEditor().selectAll();
            });
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
        // Floor the popup at the control's width via min (not pref, which feeds the control's own
        // measurement), so a stretched control keeps a full-width popup like the skin's computed sizing.
        popupList.setMinWidth(getWidth());

        // Flush the row count before the popup autosizes, else a stale (smaller) count caps its height
        // and a freshly grown list opens shorter than its max rows.
        popupList.applyCss();
        popupList.layout();
        popupList.autosize();
        fitRowsToScreenSpace();
        show();
        realignOpenPopup(); // after show, so fresh opens and while-open updates land on the same anchor
    }

    // An open popup's window is not re-placed for a new size until the skin's next reconfigure, so a
    // keystroke could leave a shrunken list at a stale anchor; re-anchor it to the kept side's edge.
    private void realignOpenPopup() {
        if (popupList.getScene() == null
                || !(popupList.getScene().getWindow() instanceof PopupWindow popup)
                || !popup.isShowing()) return;
        Bounds field = localToScreen(getBoundsInLocal());
        if (field == null) return;
        double height = popupList.getLayoutBounds().getHeight(); // the card renders at the window anchor on this skin
        popup.setAnchorY(popupAbove ? field.getMinY() - height : field.getMaxY());
    }

    // Stay below the field (the skin flips a too-tall popup above), capped to fit while at least half the rows stay
    // visible, else open toward the roomier side; the side is kept while showing so filtering does not flip the list.
    private void fitRowsToScreenSpace() {
        Bounds field = localToScreen(getBoundsInLocal());
        if (field == null) return;
        Rectangle2D screen = screenVisualBounds(field);
        double gap = popupList.getTranslateY(); // css offsets the card this far from the field
        double prefHeight = popupList.prefHeight(-1);
        double spaceBelow = screen.getMaxY() - field.getMaxY() - gap;

        int desired = getVisibleRowCount();
        double insets = popupList.getInsets().getTop() + popupList.getInsets().getBottom();
        double rowHeight = (prefHeight - insets) / desired;
        int below = (int) ((spaceBelow - insets) / rowHeight);
        int above = (int) Math.min((field.getMinY() - screen.getMinY() - gap - insets) / rowHeight, desired);
        if (!popupSideDecided) {
            popupAbove = prefHeight > spaceBelow && below < (desired + 1) / 2 && above > below;
            popupSideDecided = true;
        }

        int rows = Math.max(1, popupAbove ? above : Math.min(desired, below));
        if (rows != desired) {
            setVisibleRowCount(rows);
            popupList.applyCss();
            popupList.layout();
            popupList.autosize();
        }
    }

    private Rectangle2D screenVisualBounds(Bounds screenBounds) {
        List<Screen> screens = Screen.getScreensForRectangle(
                screenBounds.getMinX(), screenBounds.getMinY(), screenBounds.getWidth(), screenBounds.getHeight());
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
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
