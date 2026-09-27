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

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

public class AddressWithIconAndDirection extends HBox {

    public AddressWithIconAndDirection(String text, String address, boolean received) {
        Label directionIcon = new Label();
        directionIcon.getStyleClass().add("icon");
        directionIcon.getStyleClass().add(received ? "received-funds-icon" : "sent-funds-icon");
        GlyphsDude.setIcon(directionIcon, received ? FontAwesomeIcon.SIGN_IN : FontAwesomeIcon.SIGN_OUT);
        if (received)
            directionIcon.setRotate(180);
        directionIcon.setMouseTransparent(true);

        setAlignment(Pos.CENTER_LEFT);
        Label label = new AutoTooltipLabel(text);
        label.setMouseTransparent(true);
        HBox.setMargin(directionIcon, new Insets(0, 3, 0, 0));
        HBox.setMargin(label, new Insets(0, 3, 0, 0));

        Label addressLabel = new AutoTooltipLabel(address);
        HBox.setHgrow(addressLabel, Priority.ALWAYS);
        addressLabel.setMinWidth(0);
        addressLabel.setMaxWidth(Double.MAX_VALUE);
        addressLabel.setPrefWidth(0);
        getChildren().addAll(directionIcon, label, addressLabel);

        if (address != null && !address.isBlank() && !"unavailable".equals(address)) {
            Label copyLabel = new Label();
            copyLabel.getStyleClass().addAll("icon", "transaction-address-copy");
            GUIUtil.configureCopyIcon(copyLabel, () -> address);
            HBox.setMargin(copyLabel, new Insets(0, 12, 0, 6));
            getChildren().add(copyLabel);
        }
    }
}
