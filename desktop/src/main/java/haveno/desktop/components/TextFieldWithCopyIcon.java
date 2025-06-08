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

import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;

public class TextFieldWithCopyIcon extends AnchorPane {

    private final StringProperty text = new SimpleStringProperty();
    private final TextField textField;
    private boolean copyWithoutCurrencyPostFix;
    private boolean copyTextAfterDelimiter;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TextFieldWithCopyIcon() {
        this(null);
    }

    public TextFieldWithCopyIcon(String customStyleClass) {
        Label copyLabel = new Label();
        copyLabel.setLayoutY(Layout.FLOATING_ICON_Y);
        copyLabel.getStyleClass().addAll("icon", "highlight");
        if (customStyleClass != null) copyLabel.getStyleClass().add(customStyleClass + "-icon");
        copyLabel.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
        copyLabel.setGraphic(GUIUtil.getCopyIcon());
        copyLabel.setOnMouseClicked(e -> {
            String text = getText();
            if (text != null && text.length() > 0) {
                String copyText;
                if (copyWithoutCurrencyPostFix) {
                    String[] strings = text.split(" ");
                    if (strings.length > 1)
                        copyText = strings[0]; // exclude the BTC postfix
                    else
                        copyText = text;
                } else if (copyTextAfterDelimiter) {
                    String[] strings = text.split(" ");
                    if (strings.length > 1)
                        copyText = strings[2]; // exclude the part before / (slash included)
                    else
                        copyText = text;
                } else {
                    copyText = text;
                }
                Utilities.copyToClipboard(copyText);
                Tooltip tp = new Tooltip(Res.get("shared.copiedToClipboard"));
                Node node = (Node) e.getSource();
                UserThread.runAfter(() -> tp.hide(), 1);
                tp.show(node, e.getScreenX() + Layout.PADDING, e.getScreenY() + Layout.PADDING);
            }
        });
        textField = new JFXTextField();
        textField.setEditable(false);
        if (customStyleClass != null) textField.getStyleClass().add(customStyleClass);
        textField.textProperty().bindBidirectional(text);
        AnchorPane.setRightAnchor(copyLabel, 5.0);
        AnchorPane.setRightAnchor(textField, 30.0);
        AnchorPane.setLeftAnchor(textField, 0.0);
        AnchorPane.setTopAnchor(copyLabel, 0.0);
        AnchorPane.setBottomAnchor(copyLabel, 0.0);
        AnchorPane.setTopAnchor(textField, 0.0);
        AnchorPane.setBottomAnchor(textField, 0.0);
        textField.focusTraversableProperty().set(focusTraversableProperty().get());
        getChildren().addAll(textField, copyLabel);
    }

    public void setPromptText(String value) {
        textField.setPromptText(value);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter/Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getText() {
        return text.get();
    }

    public StringProperty textProperty() {
        return text;
    }

    public void setText(String text) {
        this.text.set(text);
    }

    public void setTooltip(Tooltip toolTip) {
        textField.setTooltip(toolTip);
    }

    public void setCopyWithoutCurrencyPostFix(boolean copyWithoutCurrencyPostFix) {
        this.copyWithoutCurrencyPostFix = copyWithoutCurrencyPostFix;
    }

    public void setCopyTextAfterDelimiter(boolean copyTextAfterDelimiter) {
        this.copyTextAfterDelimiter = copyTextAfterDelimiter;
    }

}
