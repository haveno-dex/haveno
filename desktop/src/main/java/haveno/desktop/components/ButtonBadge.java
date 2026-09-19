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

import com.jfoenix.controls.JFXBadge;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;

public class ButtonBadge extends JFXBadge {

    private final Button button;

    public ButtonBadge(Button button) {
        super(button);
        this.button = button;
        getStyleClass().add("button-badge");
        setCursor(Cursor.HAND);
    }

    @Override
    public void refreshBadge() {
        super.refreshBadge();
        lookupAll(".badge-pane").forEach(node -> node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress() &&
                    !e.isShiftDown() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                button.fire();
                e.consume();
            }
        }));
    }
}
