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

package haveno.desktop.app;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.desktop.components.AutoTooltipLabel;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * Wizard step to choose the preferred display currency from a searchable list, preselected
 * from the system locale. Skipped on a quick start, which keeps the preselected currency.
 */
public class StartupWizardCurrencyStep implements StartupWizard.Step {

    private static final double LIST_WIDTH = 400;

    private final BooleanSupplier quickStart;
    private final VBox content;
    private final TextField searchField = new TextField();
    private final ListView<TradeCurrency> currencyListView = new ListView<>();
    private final FilteredList<TradeCurrency> filteredCurrencies;
    // survives filtering that temporarily hides the selected row
    private TradeCurrency selectedCurrency;
    // the selection model shifts rows while the predicate changes; ignore those transient selections
    private boolean applyingFilter;

    public StartupWizardCurrencyStep(BooleanSupplier quickStart) {
        this.quickStart = quickStart;

        filteredCurrencies = new FilteredList<>(FXCollections.observableArrayList(new ArrayList<>(CurrencyUtil.getAllSortedTraditionalCurrencies())));
        currencyListView.setItems(filteredCurrencies);
        currencyListView.getStyleClass().add("wizard-currency-list");
        currencyListView.setPrefHeight(240);
        currencyListView.setMinHeight(160);
        currencyListView.setMaxWidth(LIST_WIDTH);
        currencyListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(TradeCurrency currency, boolean empty) {
                super.updateItem(currency, empty);
                setText(empty || currency == null ? null : currency.getName() + " (" + currency.getCode() + ")");
            }
        });
        currencyListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!applyingFilter && newValue != null) selectedCurrency = newValue;
        });

        Label noMatches = new AutoTooltipLabel(Res.get("startupWizard.currency.noMatch"));
        noMatches.getStyleClass().add("startup-wizard-footer-label");
        currencyListView.setPlaceholder(noMatches);

        // preselect the currency of the system locale's country, or USD if unavailable
        TradeCurrency defaultCurrency = new TraditionalCurrency("USD");
        try {
            TradeCurrency localeCurrency = CurrencyUtil.getCurrencyByCountryCode(CountryUtil.getDefaultCountry().code);
            if (filteredCurrencies.contains(localeCurrency)) defaultCurrency = localeCurrency;
        } catch (IllegalArgumentException e) {
        }
        currencyListView.getSelectionModel().select(defaultCurrency);

        searchField.setPromptText(Res.get("startupWizard.currency.search"));
        searchField.getStyleClass().add("login-password-field");
        searchField.setMaxWidth(LIST_WIDTH);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter(newValue));
        // let arrow keys move the list selection while typing in the search field
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int delta = event.getCode() == KeyCode.DOWN ? 1 : event.getCode() == KeyCode.UP ? -1 : 0;
            if (delta == 0) return;
            if (!filteredCurrencies.isEmpty()) {
                int index = Math.min(Math.max(currencyListView.getSelectionModel().getSelectedIndex() + delta, 0), filteredCurrencies.size() - 1);
                currencyListView.getSelectionModel().select(index);
                currencyListView.scrollTo(Math.max(0, index - 2));
            }
            event.consume();
        });

        Text searchIcon = StartupWizard.createIcon(MaterialDesignIcon.MAGNIFY, "1.1em", "wizard-search-icon");
        StackPane searchBox = new StackPane(searchField, searchIcon);
        searchBox.setMaxWidth(LIST_WIDTH);
        StackPane.setAlignment(searchIcon, Pos.CENTER_RIGHT);
        StackPane.setMargin(searchIcon, new Insets(0, 12, 0, 0));

        Label info = new AutoTooltipLabel(Res.get("startupWizard.currency.info"));
        info.getStyleClass().add("startup-wizard-footer-label");
        info.setWrapText(true);
        info.setMaxWidth(544);
        info.setAlignment(Pos.CENTER);
        info.setTextAlignment(TextAlignment.CENTER);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.EARTH,
                        Res.get("startupWizard.currency.headline"),
                        Res.get("startupWizard.currency.subtitle")),
                searchBox,
                currencyListView,
                info);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(searchBox, new Insets(8, 0, 0, 0));
    }

    // filter by name or code; keep the selection if it still matches, else move it to the first match
    private void applyFilter(String text) {
        String filter = text == null ? "" : text.trim().toLowerCase();
        applyingFilter = true;
        filteredCurrencies.setPredicate(currency -> filter.isEmpty()
                || currency.getName().toLowerCase().contains(filter)
                || currency.getCode().toLowerCase().contains(filter));
        applyingFilter = false;
        if (filteredCurrencies.contains(selectedCurrency)) {
            currencyListView.getSelectionModel().select(selectedCurrency);
        } else if (!filteredCurrencies.isEmpty()) {
            currencyListView.getSelectionModel().select(0);
        }
        currencyListView.scrollTo(Math.max(0, currencyListView.getSelectionModel().getSelectedIndex() - 2));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public boolean isSkipped() {
        return quickStart.getAsBoolean();
    }

    @Override
    public void onShown() {
        scrollSelectionIntoView();
        searchField.requestFocus();
    }

    // scroll now and again after the pulse's layout: the first scroll runs before the cells
    // are sized, so it lands short until repeated with real cell heights
    private void scrollSelectionIntoView() {
        currencyListView.applyCss(); // create the list's skin so scrollTo is not dropped on first show
        scrollToSelection();
        Scene scene = currencyListView.getScene();
        if (scene == null) return;
        Runnable[] scrollOnce = new Runnable[1];
        scrollOnce[0] = () -> {
            scene.removePostLayoutPulseListener(scrollOnce[0]);
            scrollToSelection();
        };
        scene.addPostLayoutPulseListener(scrollOnce[0]);
    }

    private void scrollToSelection() {
        currencyListView.scrollTo(Math.max(0, currencyListView.getSelectionModel().getSelectedIndex() - 2));
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        resultHandler.accept(true);
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.next");
    }

    public TradeCurrency getSelectedCurrency() {
        return selectedCurrency;
    }
}
