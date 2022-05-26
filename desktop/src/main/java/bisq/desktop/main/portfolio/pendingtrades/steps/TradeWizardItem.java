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

package bisq.desktop.main.portfolio.pendingtrades.steps;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import javafx.geometry.Pos;

import org.jetbrains.annotations.NotNull;

import static bisq.desktop.util.FormBuilder.getBigIcon;

import bisq.common.UserThread;

public class TradeWizardItem extends Label {
    private final String iconLabel;

    public Class<? extends TradeStepView> getViewClass() {
        return viewClass;
    }

    private final Class<? extends TradeStepView> viewClass;

    public TradeWizardItem(Class<? extends TradeStepView> viewClass, String title, String iconLabel) {
        this.viewClass = viewClass;
        this.iconLabel = iconLabel;

        setMouseTransparent(true);
        setText(title);
//        setPrefHeight(40);
        setPrefWidth(360);
        setAlignment(Pos.CENTER_LEFT);
        setDisabled();
    }

    public void setDisabled() {
        setId("trade-wizard-item-background-disabled");
        UserThread.execute(() -> setGraphic(getStackPane("trade-step-disabled-bg")));
    }


    public void setActive() {
        setId("trade-wizard-item-background-active");
        UserThread.execute(() -> setGraphic(getStackPane("trade-step-active-bg")));
    }

    public void setCompleted() {
        setId("trade-wizard-item-background-active");
        final Text icon = getBigIcon(MaterialDesignIcon.CHECK_CIRCLE);
        icon.getStyleClass().add("trade-step-active-bg");
        UserThread.execute(() -> setGraphic(icon));
    }

    @NotNull
    private StackPane getStackPane(String styleClass) {
        StackPane stackPane = new StackPane();
        final Label label = new Label(iconLabel);
        label.getStyleClass().add("trade-step-label");
        final Text icon = getBigIcon(MaterialDesignIcon.CIRCLE);
        icon.getStyleClass().add(styleClass);
        stackPane.getChildren().addAll(icon, label);
        return stackPane;
    }
}
