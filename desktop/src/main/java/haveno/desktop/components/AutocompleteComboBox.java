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

package haveno.desktop.components;

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.skins.JFXComboBoxListViewSkin;
import haveno.common.UserThread;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.apache.commons.lang3.StringUtils;

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
public class AutocompleteComboBox<T> extends JFXComboBox<T> {
    private List<? extends T> list;
    private List<? extends T> extendedList;
    private List<T> matchingList;
    private JFXComboBoxListViewSkin<T> comboBoxListViewSkin;

    public AutocompleteComboBox() {
        this(FXCollections.observableArrayList());
    }

    private AutocompleteComboBox(ObservableList<T> items) {
        super(items);
        setEditable(true);
        clearOnFocus();
        setEmptySkinToGetMoreControlOverListView();
        fixSpaceKey();
        setAutocompleteItems(items);
        reactToQueryChanges();
    }

    /**
     * Set the complete list of ComboBox items. Use this instead of setItems().
     */
    public void setAutocompleteItems(List<? extends T> items, List<? extends T> allItems) {
        list = items;
        extendedList = allItems;
        matchingList = new ArrayList<>(list);
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

            // Case 2: fire if the text is empty to support special "show all" case
            if (inputText.isEmpty()) {
                eh.handle(e);
                getParent().requestFocus();
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
                removeFilter();
                forceRedraw();
            }
        });
    }

    // The ComboBox API does not provide enough control over the underlying
    // ListView that is used as a dropdown. The only way to get this control
    // is to set custom ListViewSkin. The default skin is null and so useless.
    private void setEmptySkinToGetMoreControlOverListView() {
        comboBoxListViewSkin = new JFXComboBoxListViewSkin<>(this);
        setSkin(comboBoxListViewSkin);
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
            comboBoxListViewSkin.getPopupContent().autosize();
            show();
        } else {
            hide();
        }
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
