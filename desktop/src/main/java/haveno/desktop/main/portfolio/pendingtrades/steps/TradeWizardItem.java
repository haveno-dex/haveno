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

package haveno.desktop.main.portfolio.pendingtrades.steps;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.desktop.util.FormBuilder;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

// one step of the horizontal trade timeline: numbered circle plus title, check mark when completed
public class TradeWizardItem extends HBox {

    public enum State {
        DISABLED,
        ACTIVE,
        COMPLETED
    }

    private final Class<? extends TradeStepView> viewClass;
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.DISABLED);
    private final StackPane circle;
    private final Label numberLabel;
    private final Label titleLabel;

    public TradeWizardItem(Class<? extends TradeStepView> viewClass, String title, String iconLabel) {
        this.viewClass = viewClass;

        numberLabel = new Label(iconLabel);
        numberLabel.getStyleClass().add("trade-step-number");
        circle = new StackPane(numberLabel);
        circle.getStyleClass().add("trade-step-circle");

        titleLabel = new Label(title);
        titleLabel.getStyleClass().add("trade-step-title");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(170);

        getStyleClass().add("trade-step-item");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setMouseTransparent(true);
        getChildren().addAll(circle, titleLabel);
    }

    public Class<? extends TradeStepView> getViewClass() {
        return viewClass;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state;
    }

    public void setDisabled() {
        UserThread.execute(() -> applyState(State.DISABLED));
    }

    public void setActive() {
        UserThread.execute(() -> applyState(State.ACTIVE));
    }

    public void setCompleted() {
        UserThread.execute(() -> applyState(State.COMPLETED));
    }

    private void applyState(State newState) {
        getStyleClass().removeAll("active", "completed");
        if (newState == State.ACTIVE) {
            getStyleClass().add("active");
            circle.getChildren().setAll(numberLabel);
        } else if (newState == State.COMPLETED) {
            getStyleClass().add("completed");
            Text checkIcon = FormBuilder.getIcon(MaterialDesignIcon.CHECK, "1em");
            checkIcon.getStyleClass().add("trade-step-check");
            circle.getChildren().setAll(checkIcon);
        } else {
            circle.getChildren().setAll(numberLabel);
        }
        state.set(newState);
    }
}
