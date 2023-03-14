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

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;

public class TitledGroupBg extends Pane {

    private final HBox box;
    private final Label label;
    private final StringProperty text = new SimpleStringProperty();
    private Text helpIcon;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TitledGroupBg() {
        GridPane.setMargin(this, new Insets(-10, -10, -10, -10));
        GridPane.setColumnSpan(this, 2);

        box = new HBox();
        box.setSpacing(4);
        box.setLayoutX(4);
        box.setLayoutY(-8);
        box.setPadding(new Insets(0, 7, 0, 5));
        box.setAlignment(Pos.CENTER_LEFT);

        label = new AutoTooltipLabel();
        label.textProperty().bind(text);
        setActive();
        box.getChildren().add(label);
        getChildren().add(box);
    }

    public void setInactive() {
        resetStyles();
        getStyleClass().add("titled-group-bg");
        label.getStyleClass().add("titled-group-bg-label");
    }

    private void resetStyles() {
        getStyleClass().removeAll("titled-group-bg", "titled-group-bg-active");
        label.getStyleClass().removeAll("titled-group-bg-label", "titled-group-bg-label-active");
    }

    private void setActive() {
        resetStyles();
        getStyleClass().add("titled-group-bg-active");
        label.getStyleClass().add("titled-group-bg-label-active");
    }

    public StringProperty textProperty() {
        return text;
    }

    public void setText(String text) {
        this.text.set(text);
    }

    public void setHelpUrl(String helpUrl) {
        if (helpIcon == null) {
            helpIcon = FormBuilder.getIcon(MaterialDesignIcon.HELP_CIRCLE_OUTLINE, "1em");
            helpIcon.getStyleClass().addAll("icon", "link-icon");
            box.getChildren().add(helpIcon);
        }

        helpIcon.setOnMouseClicked(e -> GUIUtil.openWebPage(helpUrl));
    }
}
