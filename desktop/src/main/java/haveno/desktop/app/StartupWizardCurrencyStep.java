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

import com.jfoenix.controls.JFXComboBox;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.desktop.components.AutoTooltipLabel;
import java.util.ArrayList;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Wizard step to choose the preferred display currency, preselected from the system locale.
 */
public class StartupWizardCurrencyStep implements StartupWizard.Step {

    private final VBox content;
    private final ComboBox<TradeCurrency> currencyComboBox = new JFXComboBox<>();

    public StartupWizardCurrencyStep() {
        currencyComboBox.setItems(FXCollections.observableArrayList(new ArrayList<>(CurrencyUtil.getAllSortedTraditionalCurrencies())));
        currencyComboBox.setVisibleRowCount(12);
        currencyComboBox.setMinWidth(340);
        currencyComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TradeCurrency currency) {
                return currency == null ? "" : currency.getName() + " (" + currency.getCode() + ")";
            }

            @Override
            public TradeCurrency fromString(String string) {
                return null;
            }
        });

        // preselect the currency of the system locale's country
        TradeCurrency defaultCurrency = CurrencyUtil.getCurrencyByCountryCode("US");
        try {
            defaultCurrency = CurrencyUtil.getCurrencyByCountryCode(CountryUtil.getDefaultCountry().code);
        } catch (IllegalArgumentException e) {
        }
        currencyComboBox.getSelectionModel().select(defaultCurrency);

        Label info = new AutoTooltipLabel(Res.get("startupWizard.currency.info"));
        info.getStyleClass().add("startup-wizard-footer-label");
        info.setWrapText(true);
        info.setMaxWidth(544);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.EARTH,
                        Res.get("startupWizard.currency.headline"),
                        Res.get("startupWizard.currency.subtitle")),
                currencyComboBox,
                info);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(currencyComboBox, new Insets(8, 0, 0, 0));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        resultHandler.accept(true);
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.finish");
    }

    public TradeCurrency getSelectedCurrency() {
        return currencyComboBox.getSelectionModel().getSelectedItem();
    }
}
