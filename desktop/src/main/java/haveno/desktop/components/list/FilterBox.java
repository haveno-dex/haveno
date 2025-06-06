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

package haveno.desktop.components.list;

import haveno.desktop.components.InputTextField;
import haveno.desktop.util.filtering.FilterableListItem;
import javafx.beans.value.ChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;

public class FilterBox extends HBox {
    private final InputTextField textField;
    private FilteredList<? extends FilterableListItem> filteredList;

    private ChangeListener<String> listener;

    public FilterBox() {
        super();
        setSpacing(5.0);

        textField = new InputTextField();
        textField.setMinWidth(500);

        getChildren().addAll(textField);
    }

    public void initialize(FilteredList<? extends FilterableListItem> filteredList,
                           TableView<? extends FilterableListItem> tableView) {
        this.filteredList = filteredList;
        listener = (observable, oldValue, newValue) -> {
            tableView.getSelectionModel().clearSelection();
            applyFilteredListPredicate(textField.getText());
        };
    }

    public void activate() {
        textField.textProperty().addListener(listener);
        applyFilteredListPredicate(textField.getText());
    }

    public void deactivate() {
        textField.textProperty().removeListener(listener);
    }

    private void applyFilteredListPredicate(String filterString) {
        filteredList.setPredicate(item -> item.match(filterString));
    }

    public void setPromptText(String promptText) {
        textField.setPromptText(promptText);
    }
}
