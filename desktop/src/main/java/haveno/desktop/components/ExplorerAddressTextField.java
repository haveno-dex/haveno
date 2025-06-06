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

import com.jfoenix.controls.JFXTextField;
import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

public class ExplorerAddressTextField extends AnchorPane {
    @Setter
    private static Preferences preferences;

    @Getter
    private final TextField textField;
    private final Label copyLabel, missingAddressWarningIcon;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ExplorerAddressTextField() {
        copyLabel = new Label();
        copyLabel.setLayoutY(3);
        copyLabel.getStyleClass().addAll("icon", "highlight");
        copyLabel.setTooltip(new Tooltip(Res.get("explorerAddressTextField.copyToClipboard")));
        copyLabel.setGraphic(GUIUtil.getCopyIcon());
        AnchorPane.setRightAnchor(copyLabel, 30.0);

        Tooltip tooltip = new Tooltip(Res.get("explorerAddressTextField.blockExplorerIcon.tooltip"));

        missingAddressWarningIcon = new Label();
        missingAddressWarningIcon.getStyleClass().addAll("icon", "error-icon");
        AwesomeDude.setIcon(missingAddressWarningIcon, AwesomeIcon.WARNING_SIGN);
        missingAddressWarningIcon.setTooltip(new Tooltip(Res.get("explorerAddressTextField.missingTx.warning.tooltip")));
        missingAddressWarningIcon.setMinWidth(20);
        AnchorPane.setRightAnchor(missingAddressWarningIcon, 52.0);
        AnchorPane.setTopAnchor(missingAddressWarningIcon, 4.0);
        missingAddressWarningIcon.setVisible(false);
        missingAddressWarningIcon.setManaged(false);

        textField = new JFXTextField();
        textField.setId("address-text-field");
        textField.setEditable(false);
        textField.setTooltip(tooltip);
        AnchorPane.setRightAnchor(textField, 80.0);
        AnchorPane.setLeftAnchor(textField, 0.0);
        textField.focusTraversableProperty().set(focusTraversableProperty().get());
        getChildren().addAll(textField, missingAddressWarningIcon, copyLabel);
    }

    public void setup(@Nullable String address) {
        if (address == null) {
            textField.setText(Res.get("shared.na"));
            textField.setId("address-text-field-error");
            copyLabel.setVisible(false);
            copyLabel.setManaged(false);
            missingAddressWarningIcon.setVisible(true);
            missingAddressWarningIcon.setManaged(true);
            return;
        }

        textField.setText(address);
        copyLabel.setOnMouseClicked(e -> Utilities.copyToClipboard(address));
    }

    public void cleanup() {
        textField.setOnMouseClicked(null);
        copyLabel.setOnMouseClicked(null);
        textField.setText("");
    }
}
