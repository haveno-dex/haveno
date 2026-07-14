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
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.PopupWindow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implements searchable dropdown (an autocomplete like experience).
 *
 * Clients must use setAutocompleteItems() instead of setItems().
 *
 * @param <T>  type of the ComboBox item; in the simplest case this can be a String
 */
@Slf4j
public class AutocompleteComboBox<T> extends JFXComboBox<T> {
    private List<? extends T> list;
    private List<? extends T> extendedList;
    private List<T> matchingList;
    private JFXComboBoxListViewSkin<T> comboBoxListViewSkin;
    private boolean selectAllShortcut = false;
    private T lastCommittedValue;
    private double unfilteredPopupWidth = -1; // popup width measured from the full item list
    private List<T> measuredList; // unfiltered content the pinned width was measured for

    public AutocompleteComboBox() {
        this(FXCollections.observableArrayList());
    }

    private AutocompleteComboBox(ObservableList<T> items) {
        super(items);
        setEditable(true);
        clearOnFocus();
        showOnEditorClick();
        setEmptySkinToGetMoreControlOverListView();
        fixSpaceKey();
        setAutocompleteItems(items);
        mimicPopupStyleBeforeFirstShow();
        linkPopupOwnerToCombo();
        reactToQueryChanges();

        // Store last committed value so we can restore it if nothing selected
        valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null)
                lastCommittedValue = newVal;
        });

        // Restore last committed value when editor loses focus if no matches
        getEditor().focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                String input = getEditor().getText();
                T matched = getConverter().fromString(input);

                boolean matchFound = getItems().stream()
                    .anyMatch(item -> item.equals(matched));

                if (!matchFound) {
                    UserThread.execute(() -> {
                        getSelectionModel().select(lastCommittedValue);
                        getEditor().setText(asString(lastCommittedValue));
                    });
                }
            }
        });
    }

    /**
     * Set the complete list of ComboBox items. Use this instead of setItems().
     */
    public void setAutocompleteItems(List<? extends T> items, List<? extends T> allItems) {
        list = items;
        extendedList = allItems;
        matchingList = new ArrayList<>(list);
        unfilteredPopupWidth = -1;
        measuredList = null;
        unpinPopupWidth(); // new items: measure anew so the popup can resize
        setValue(null);
        getSelectionModel().clearSelection();
        setItems(FXCollections.observableList(matchingList));
        getEditor().setText("");
    }

    public void setAutocompleteItems(List<? extends T> items) {
        setAutocompleteItems(items, null);
    }

    /**
     * Triggered when value change is *confirmed*. In practical terms
     * this is when user clicks item on the dropdown or hits [ENTER]
     * while typing in the text.
     *
     * This is in contrast to onAction event that is triggered
     * on every (unconfirmed) value change. The onAction is not really
     * suitable for the search enabled ComboBox.
     */
    public final void setOnChangeConfirmed(EventHandler<Event> eh) {
        setOnHidden(e -> {
            var inputText = getEditor().getText();

            // Case 1: fire if input text selects (matches) an item
            var selectedItem = getSelectionModel().getSelectedItem();
            var inputTextItem = getConverter().fromString(inputText);
            if (selectedItem != null && selectedItem.equals(inputTextItem)) {
                eh.handle(e);
                getParent().requestFocus();
                return;
            }

            // Case 2: fire if the text is empty. A click on the editor itself keeps the
            // blank editor focused for typing; any other dismissal restores the committed value.
            if (inputText.isEmpty()) {
                eh.handle(e);
                if (!getEditor().isHover()) {
                    getParent().requestFocus();
                    UserThread.execute(() -> {
                        getSelectionModel().select(lastCommittedValue);
                        getEditor().setText(asString(lastCommittedValue));
                    });
                }
            }
        });
    }

    // Clear selection and query when ComboBox gets new focus. This is usually what user
    // wants - to have a blank slate for a new search. The primary motivation though
    // was to work around UX glitches related to (starting) editing text when combobox
    // had specific item selected.
    private void clearOnFocus() {
        getEditor().focusedProperty().addListener((observableValue, hadFocus, hasFocus) -> {
            if (!hadFocus && hasFocus) {
                // When no filter is applied the rows are already current, so keep them: replacing
                // the items rebuilds every visible row before the popup can paint, which delays
                // opening noticeably. Refresh the rows just after showing instead, so dynamic
                // content (e.g. offer counts) stays as fresh as a rebuilt list.
                if (matchingList.equals(list)) {
                    setValue(null);
                    getSelectionModel().clearSelection();
                    getEditor().setText("");
                    if (comboBoxListViewSkin.getPopupContent() instanceof ListView<?> listView)
                        listView.scrollTo(0);
                    forceRedraw();
                    UserThread.execute(this::refreshShowingRows);
                } else {
                    removeFilter();
                    forceRedraw();
                }
            }
        });
    }

    // re-set the items so the visible rows rebuild with current dynamic content
    private void refreshShowingRows() {
        if (!isShowing()) return;
        matchingList = new ArrayList<>(list);
        setItems(FXCollections.observableList(matchingList));
    }

    // Clicking the search editor while the list is closed should open it (the editor does not
    // toggle the popup by itself). With the popup owner node set (linkPopupOwnerToCombo) an
    // editor click no longer auto-hides an open list, so this only opens it from closed.
    private void showOnEditorClick() {
        getEditor().addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && !isShowing() && matchingListSize() > 0)
                show();
        });
    }

    // The skin shows the popup with only a Window owner and no owner node, so JavaFX auto-hides
    // it on any press outside the popup window - including clicks in the search editor, which
    // then blink the list closed and open. Set this combo as the popup's owner node once it is
    // created: presses inside the combo (editor, arrow) are then owner-node events and skip
    // auto-hide, while presses elsewhere still hide the list as before.
    private void linkPopupOwnerToCombo() {
        comboBoxListViewSkin.getPopupContent().sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null && scene.getWindow() instanceof PopupWindow popup && popup.getOwnerNode() == null)
                setPopupOwnerNode(popup);
        });
    }

    // PopupWindow.ownerNode is externally read-only and only set by the Node-owner show()
    // overload, which the combo skin never uses; set it reflectively.
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

    // The ComboBox API does not provide enough control over the underlying
    // ListView that is used as a dropdown. The only way to get this control
    // is to set custom ListViewSkin. The default skin is null and so useless.
    private void setEmptySkinToGetMoreControlOverListView() {
        comboBoxListViewSkin = new JFXComboBoxListViewSkin<>(this);
        setSkin(comboBoxListViewSkin);
    }

    // The skin sizes the control to the popup ListView, which it keeps as a hidden child of
    // this control until the popup adopts it on first show. Until then it is styled as a plain
    // child instead of with the popup's ".combo-box-popup" rules, so its measured width differs
    // and the control resizes on first open. Wrap it in a hidden pane mimicking the popup, so
    // it always measures exactly as it will render in the popup.
    private void mimicPopupStyleBeforeFirstShow() {
        Pane popupMimic = new Pane(comboBoxListViewSkin.getPopupContent()); // reparents the ListView
        popupMimic.getStyleClass().add("combo-box-popup");
        popupMimic.setManaged(false);
        popupMimic.setVisible(false);
        getChildren().add(popupMimic);
    }

    // By default pressing [SPACE] caused editor text to reset. The solution
    // is to suppress relevant event on the underlying ListViewSkin.
    private void fixSpaceKey() {
        comboBoxListViewSkin.getPopupContent().addEventFilter(KeyEvent.ANY, (KeyEvent event) -> {
            if (event.getCode() == KeyCode.SPACE)
                event.consume();
        });
    }

    private void filterBy(String query) {
        matchingList = (extendedList != null && query.length() > 0 ? extendedList : list)
                .stream()
                .filter(item -> StringUtils.containsIgnoreCase(asString(item), query))
                .collect(Collectors.toList());

        setValue(null);
        getSelectionModel().clearSelection();
        setItems(FXCollections.observableList(matchingList));
        int pos = getEditor().getCaretPosition();
        if (pos > query.length()) pos = query.length();
        getEditor().setText(query);
        getEditor().positionCaret(pos);
    }

    private void reactToQueryChanges() {
        getEditor().addEventHandler(KeyEvent.KEY_RELEASED, (KeyEvent event) -> {

            // ignore ctrl and command keys
            if (event.getCode() == KeyCode.CONTROL || event.getCode() == KeyCode.COMMAND || event.getCode() == KeyCode.META) {
                event.consume();
                return;
            }

            // handle select all
            boolean isSelectAll = event.getCode() == KeyCode.A && (event.isControlDown() || event.isMetaDown());
            if (isSelectAll) {
                getEditor().selectAll();
                selectAllShortcut = true;
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.A && selectAllShortcut) { // 'A' can be received after ctrl/cmd
                selectAllShortcut = false;
                event.consume();
                return;
            }

            UserThread.execute(() -> {
                String query = getEditor().getText();
                var exactMatch = list.stream().anyMatch(item -> asString(item).equalsIgnoreCase(query));
                if (!exactMatch) {
                    if (query.isEmpty())
                        removeFilter();
                    else
                        filterBy(query);
                    forceRedraw();
                }
            });
        });
    }

    private void removeFilter() {
        matchingList = new ArrayList<>(list);
        setValue(null);
        getSelectionModel().clearSelection();
        setItems(FXCollections.observableList(matchingList));
        getEditor().setText("");
    }

    private void forceRedraw() {
        adjustVisibleRowCount();
        if (matchingListSize() > 0) {
            boolean unfiltered = matchingList.equals(list);
            // Drop a pin measured for stale content (e.g. changed offer counts or an added
            // currency) so a grown list re-measures instead of overflowing into a scrollbar.
            if (unfiltered && !matchingList.equals(measuredList)) unfilteredPopupWidth = -1;
            // Sizing the popup width runs the cell factory over the whole list several times per
            // open, which lags the popup visibly. Reuse the width measured on the first unfiltered
            // open while its content is unchanged; filtered rows can be wider, so those measure anew.
            if (unfiltered && unfilteredPopupWidth > 0) setPopupPrefWidth(unfilteredPopupWidth);
            else unpinPopupWidth();
            // Flush the popup ListView's item count before measuring, else a stale (smaller) count
            // caps its preferred height and a grown list (e.g. rapidly cleared filter) leaves the
            // popup shorter than its max rows.
            if (comboBoxListViewSkin.getPopupContent() instanceof ListView<?> listView) {
                listView.applyCss();
                listView.layout();
            }
            comboBoxListViewSkin.getPopupContent().autosize();
            show();
            if (comboBoxListViewSkin.getPopupContent() instanceof ListView<?> listView) {
                listView.applyCss();
                listView.layout();
                if (unfiltered && unfilteredPopupWidth <= 0 && listView.getWidth() > 0) {
                    unfilteredPopupWidth = listView.getWidth();
                    measuredList = new ArrayList<>(matchingList);
                    setPopupPrefWidth(unfilteredPopupWidth);
                }
            }
        } else {
            hide();
        }
    }

    private void setPopupPrefWidth(double width) {
        if (comboBoxListViewSkin.getPopupContent() instanceof ListView<?> listView)
            listView.setPrefWidth(width);
    }

    private void unpinPopupWidth() {
        if (comboBoxListViewSkin != null) setPopupPrefWidth(Region.USE_COMPUTED_SIZE);
    }

    private void adjustVisibleRowCount() {
        setVisibleRowCount(Math.min(10, matchingListSize()));
    }

    private String asString(T item) {
        return getConverter().toString(item);
    }

    private int matchingListSize() {
        return matchingList.size();
    }
}
