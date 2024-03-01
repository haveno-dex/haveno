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

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.common.util.Tuple2;
import haveno.core.alert.Alert;
import haveno.core.alert.AlertManager;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.FormBuilder;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addLabelCheckBox;
import static haveno.desktop.util.FormBuilder.addRadioButton;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

public class SendAlertMessageWindow extends Overlay<SendAlertMessageWindow> {
    private final AlertManager alertManager;
    private final boolean useDevPrivilegeKeys;

    @Inject
    public SendAlertMessageWindow(AlertManager alertManager,
                                  @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        this.alertManager = alertManager;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
        type = Type.Attention;
    }

    public void show() {
        if (headLine == null)
            headLine = Res.get("sendAlertMessageWindow.headline");

        width = 968;
        createGridPane();
        addHeadLine();
        addContent();
        applyStyles();
        display();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    private void addContent() {
        gridPane.getColumnConstraints().get(0).setHalignment(HPos.LEFT);
        gridPane.getColumnConstraints().remove(1);

        InputTextField keyInputTextField = addInputTextField(gridPane, ++rowIndex,
                Res.get("shared.unlock"), 10);
        if (useDevPrivilegeKeys)
            keyInputTextField.setText(DevEnv.DEV_PRIVILEGE_PRIV_KEY);

        Tuple2<Label, TextArea> labelTextAreaTuple2 = addTopLabelTextArea(gridPane, ++rowIndex,
                Res.get("sendAlertMessageWindow.alertMsg"),
                Res.get("sendAlertMessageWindow.enterMsg"));
        TextArea alertMessageTextArea = labelTextAreaTuple2.second;
        Label first = labelTextAreaTuple2.first;
        first.setMinWidth(150);
        CheckBox isSoftwareUpdateCheckBox = addLabelCheckBox(gridPane, ++rowIndex,
                Res.get("sendAlertMessageWindow.isSoftwareUpdate"));
        HBox hBoxRelease = new HBox();
        hBoxRelease.setSpacing(10);
        GridPane.setRowIndex(hBoxRelease, ++rowIndex);

        ToggleGroup toggleGroup = new ToggleGroup();
        RadioButton isUpdateCheckBox = addRadioButton(gridPane, rowIndex, toggleGroup, Res.get("sendAlertMessageWindow.isUpdate"));
        RadioButton isPreReleaseCheckBox = addRadioButton(gridPane, rowIndex, toggleGroup, Res.get("sendAlertMessageWindow.isPreRelease"));
        hBoxRelease.getChildren().addAll(new Label(""), isUpdateCheckBox, isPreReleaseCheckBox);
        gridPane.getChildren().add(hBoxRelease);

        isSoftwareUpdateCheckBox.setSelected(true);
        isUpdateCheckBox.setSelected(true);

        InputTextField versionInputTextField = FormBuilder.addInputTextField(gridPane, ++rowIndex,
                Res.get("sendAlertMessageWindow.version"));
        versionInputTextField.disableProperty().bind(isSoftwareUpdateCheckBox.selectedProperty().not());
        isUpdateCheckBox.disableProperty().bind(isSoftwareUpdateCheckBox.selectedProperty().not());
        isPreReleaseCheckBox.disableProperty().bind(isSoftwareUpdateCheckBox.selectedProperty().not());

        Button sendButton = new AutoTooltipButton(Res.get("sendAlertMessageWindow.send"));
        sendButton.getStyleClass().add("action-button");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> {
            final String version = versionInputTextField.getText();
            boolean versionOK = false;
            final boolean isUpdate = (isSoftwareUpdateCheckBox.isSelected() && isUpdateCheckBox.isSelected());
            final boolean isPreRelease = (isSoftwareUpdateCheckBox.isSelected() && isPreReleaseCheckBox.isSelected());
            if (isUpdate || isPreRelease) {
                final String[] split = version.split("\\.");
                versionOK = split.length == 3;
                if (!versionOK) // Do not translate as only used by devs
                    new Popup().warning("Version number must be in semantic version format (contain 2 '.'). version=" + version)
                            .onClose(this::blurAgain)
                            .show();
            }
            if (!isSoftwareUpdateCheckBox.isSelected() || versionOK) {
                if (alertMessageTextArea.getText().length() > 0 && keyInputTextField.getText().length() > 0) {
                    if (alertManager.addAlertMessageIfKeyIsValid(
                            new Alert(alertMessageTextArea.getText(), isUpdate, isPreRelease, version),
                            keyInputTextField.getText())
                    )
                        hide();
                    else
                        new Popup().warning(Res.get("shared.invalidKey")).width(300).onClose(this::blurAgain).show();
                }
            }
        });

        Button removeAlertMessageButton = new AutoTooltipButton(Res.get("sendAlertMessageWindow.remove"));
        removeAlertMessageButton.setOnAction(e -> {
            if (keyInputTextField.getText().length() > 0) {
                if (alertManager.removeAlertMessageIfKeyIsValid(keyInputTextField.getText()))
                    hide();
                else
                    new Popup().warning(Res.get("shared.invalidKey")).width(300).onClose(this::blurAgain).show();
            }
        });

        closeButton = new AutoTooltipButton(Res.get("shared.close"));
        closeButton.setOnAction(e -> {
            hide();
            closeHandlerOptional.ifPresent(Runnable::run);
        });

        HBox hBox = new HBox();
        hBox.setSpacing(10);
        GridPane.setRowIndex(hBox, ++rowIndex);
        hBox.getChildren().addAll(sendButton, removeAlertMessageButton, closeButton);
        gridPane.getChildren().add(hBox);
        GridPane.setMargin(hBox, new Insets(10, 0, 0, 0));
    }
}
