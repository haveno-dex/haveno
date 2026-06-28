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
import com.google.inject.Singleton;
import haveno.common.UserThread;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple4;
import haveno.common.util.Utilities;
import haveno.core.app.TorSetup;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import static haveno.desktop.util.FormBuilder.addButtonBusyAnimationLabelAfterGroup;
import static haveno.desktop.util.FormBuilder.addLabel;
import static haveno.desktop.util.FormBuilder.addRadioButton;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import haveno.desktop.util.Layout;
import haveno.network.p2p.network.DefaultPluggableTransports;
import haveno.network.p2p.network.NetworkNode;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TorNetworkSettingsWindow extends Overlay<TorNetworkSettingsWindow> {

    // Persisted as PreferencesPayload.bridgeOptionOrdinal. Ordinals must stay stable: NONE=0 and
    // CUSTOM=2 keep their historical values, while the defunct legacy PROVIDED slot (1) is reused by
    // SNOWFLAKE. A pre-existing "provided bridges" selection therefore loads as SNOWFLAKE, and opening
    // this window replaces its dead bundled bridges with the Snowflake config (see applyToggleSelection).
    public enum BridgeOption {
        NONE,
        SNOWFLAKE,
        CUSTOM
    }

    private final Preferences preferences;
    private final NetworkNode networkNode;
    private final TorSetup torSetup;
    private Label enterBridgeLabel;
    private TextArea bridgeEntriesTextArea;
    private Label bridgeValidationLabel;
    private BridgeOption selectedBridgeOption = BridgeOption.NONE;
    private String customBridges = "";

    @Inject
    public TorNetworkSettingsWindow(Preferences preferences,
                                    NetworkNode networkNode,
                                    TorSetup torSetup) {
        this.preferences = preferences;
        this.networkNode = networkNode;
        this.torSetup = torSetup;

        type = Type.Attention;

        useShutDownButton();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void show() {
        if (!isDisplayed) {
            if (headLine == null)
                headLine = Res.get("torNetworkSettingWindow.header");

            width = 1068;
            rowIndex = 0;
            createGridPane();
            gridPane.getColumnConstraints().get(0).setHalignment(HPos.LEFT);

            addContent();
            addButtons();
            applyStyles();
            display();
        }
    }

    protected void addButtons() {
        closeButton = new AutoTooltipButton(closeButtonText == null ? Res.get("shared.close") : closeButtonText);
        closeButton.setOnAction(event -> doClose());

        if (actionHandlerOptional.isPresent()) {
            actionButton = new AutoTooltipButton(Res.get("shared.shutDown"));
            actionButton.setDefaultButton(true);
            //TODO app wide focus
            //actionButton.requestFocus();
            actionButton.setOnAction(event -> saveAndShutDown());

            Button urlButton = new AutoTooltipButton(Res.get("torNetworkSettingWindow.openTorWebPage"));
            urlButton.setOnAction(event -> {
                try {
                    Utilities.openURI(URI.create("https://bridges.torproject.org"));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            Pane spacer = new Pane();
            HBox hBox = new HBox();
            hBox.setSpacing(10);
            hBox.getChildren().addAll(spacer, urlButton, closeButton, actionButton);
            HBox.setHgrow(spacer, Priority.ALWAYS);

            GridPane.setHalignment(hBox, HPos.RIGHT);
            GridPane.setRowIndex(hBox, ++rowIndex);
            GridPane.setColumnSpan(hBox, 2);
            GridPane.setMargin(hBox, new Insets(buttonDistance, 0, 0, 0));
            gridPane.getChildren().add(hBox);
        } else if (!hideCloseButton) {
            closeButton.setDefaultButton(true);
            GridPane.setHalignment(closeButton, HPos.RIGHT);
            GridPane.setMargin(closeButton, new Insets(buttonDistance, 0, 0, 0));
            GridPane.setRowIndex(closeButton, rowIndex);
            GridPane.setColumnIndex(closeButton, 1);
            gridPane.getChildren().add(closeButton);
        }
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
                } else if (e.getCode() == KeyCode.ENTER) {
                    e.consume();
                    saveAndShutDown();
                }
            });
        }
    }

    @Override
    protected void applyStyles() {
        super.applyStyles();
        gridPane.setId("popup-grid-pane-bg");
    }

    private void addContent() {
        addTitledGroupBg(gridPane, ++rowIndex, 2, Res.get("torNetworkSettingWindow.deleteFiles.header"));

        Label deleteFilesLabel = addLabel(gridPane, rowIndex, Res.get("torNetworkSettingWindow.deleteFiles.info"), Layout.TWICE_FIRST_ROW_DISTANCE);
        deleteFilesLabel.setWrapText(true);
        GridPane.setColumnIndex(deleteFilesLabel, 0);
        GridPane.setColumnSpan(deleteFilesLabel, 2);
        GridPane.setHalignment(deleteFilesLabel, HPos.LEFT);
        GridPane.setValignment(deleteFilesLabel, VPos.TOP);

        Tuple4<Button, BusyAnimation, Label, HBox> tuple = addButtonBusyAnimationLabelAfterGroup(gridPane, ++rowIndex, Res.get("torNetworkSettingWindow.deleteFiles.button"));
        Button deleteFilesButton = tuple.first;
        deleteFilesButton.getStyleClass().remove("action-button");
        deleteFilesButton.setOnAction(e -> {
            tuple.second.play();
            tuple.third.setText(Res.get("torNetworkSettingWindow.deleteFiles.progress"));
            gridPane.setMouseTransparent(true);
            deleteFilesButton.setDisable(true);
            cleanTorDir(() -> {
                tuple.second.stop();
                tuple.third.setText("");
                new Popup().feedback(Res.get("torNetworkSettingWindow.deleteFiles.success"))
                        .useShutDownButton()
                        .hideCloseButton()
                        .show();
            });
        });


        final TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, ++rowIndex, 8, Res.get("torNetworkSettingWindow.bridges.header"), Layout.GROUP_DISTANCE);
        titledGroupBg.getStyleClass().add("last");

        Label bridgesLabel = addLabel(gridPane, rowIndex, Res.get("torNetworkSettingWindow.bridges.info"), Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);
        bridgesLabel.setWrapText(true);
        GridPane.setColumnIndex(bridgesLabel, 0);
        GridPane.setColumnSpan(bridgesLabel, 2);
        GridPane.setHalignment(bridgesLabel, HPos.LEFT);
        GridPane.setValignment(bridgesLabel, VPos.TOP);

        ToggleGroup toggleGroup = new ToggleGroup();

        // noBridges
        RadioButton noBridgesRadioButton = addRadioButton(gridPane, ++rowIndex, toggleGroup, Res.get("torNetworkSettingWindow.noBridges"));
        noBridgesRadioButton.setUserData(BridgeOption.NONE);
        GridPane.setMargin(noBridgesRadioButton, new Insets(20, 0, 0, 0));

        // snowflake (the only bundled transport: a single self-renewing config, nothing to maintain)
        RadioButton snowflakeRadioButton = addRadioButton(gridPane, ++rowIndex, toggleGroup, Res.get("torNetworkSettingWindow.snowflake"));
        snowflakeRadioButton.setUserData(BridgeOption.SNOWFLAKE);

        // customBridges
        RadioButton customBridgesRadioButton = addRadioButton(gridPane, ++rowIndex, toggleGroup, Res.get("torNetworkSettingWindow.customBridges"));
        customBridgesRadioButton.setUserData(BridgeOption.CUSTOM);

        final Tuple2<Label, TextArea> labelTextAreaTuple2 = addTopLabelTextArea(gridPane, ++rowIndex, Res.get("torNetworkSettingWindow.enterBridge"), Res.get("torNetworkSettingWindow.enterBridgePrompt"));
        enterBridgeLabel = labelTextAreaTuple2.first;
        bridgeEntriesTextArea = labelTextAreaTuple2.second;
        bridgeEntriesTextArea.setPrefHeight(60);

        bridgeValidationLabel = addLabel(gridPane, ++rowIndex, "");
        bridgeValidationLabel.setWrapText(true);
        GridPane.setColumnSpan(bridgeValidationLabel, 2);
        GridPane.setHalignment(bridgeValidationLabel, HPos.LEFT);

        Label label2 = addLabel(gridPane, ++rowIndex, Res.get("torNetworkSettingWindow.restartInfo"));
        label2.setWrapText(true);
        GridPane.setColumnSpan(label2, 2);
        GridPane.setHalignment(label2, HPos.LEFT);
        GridPane.setValignment(label2, VPos.TOP);
        GridPane.setMargin(label2, new Insets(10, 10, 20, 0));

        // init persisted values (set the text before adding listeners so they don't fire during setup)
        customBridges = preferences.getCustomBridges();
        bridgeEntriesTextArea.setText(customBridges == null ? "" : customBridges);

        int persistedOrdinal = preferences.getBridgeOptionOrdinal();
        selectedBridgeOption = persistedOrdinal >= 0 && persistedOrdinal < BridgeOption.values().length
                ? BridgeOption.values()[persistedOrdinal]
                : BridgeOption.NONE;
        switch (selectedBridgeOption) {
            case SNOWFLAKE:
                toggleGroup.selectToggle(snowflakeRadioButton);
                break;
            case CUSTOM:
                toggleGroup.selectToggle(customBridgesRadioButton);
                break;
            default:
            case NONE:
                toggleGroup.selectToggle(noBridgesRadioButton);
                break;
        }
        applyToggleSelection();

        toggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            selectedBridgeOption = (BridgeOption) newValue.getUserData();
            preferences.setBridgeOptionOrdinal(selectedBridgeOption.ordinal());
            applyToggleSelection();
        });
        bridgeEntriesTextArea.textProperty().addListener((observable, oldValue, newValue) -> {
            customBridges = newValue;
            preferences.setCustomBridges(customBridges);
            if (selectedBridgeOption == BridgeOption.CUSTOM) setBridgeAddressesByCustomBridges();
        });
    }

    private void cleanTorDir(Runnable resultHandler) {
        // We shut down Tor to be able to delete locked files (Windows locks files used by a process)
        networkNode.shutDown(() -> {
            // We give it a bit extra time to be sure that OS locks are removed
            UserThread.runAfter(() -> {
                torSetup.cleanupTorFiles(resultHandler, errorMessage -> new Popup().error(errorMessage).show());
            }, 3);
        });
    }

    private void applyToggleSelection() {
        switch (selectedBridgeOption) {
            case SNOWFLAKE:
                enterBridgeLabel.setDisable(true);
                bridgeEntriesTextArea.setDisable(true);

                preferences.setBridgeAddresses(DefaultPluggableTransports.SNOWFLAKE);
                updateBridgeValidationFeedback();
                break;
            case CUSTOM:
                enterBridgeLabel.setDisable(false);
                bridgeEntriesTextArea.setDisable(false);

                setBridgeAddressesByCustomBridges();
                break;
            default:
            case NONE:
                enterBridgeLabel.setDisable(true);
                bridgeEntriesTextArea.setDisable(true);

                preferences.setBridgeAddresses(null);
                updateBridgeValidationFeedback();
                break;
        }
    }

    private void setBridgeAddressesByCustomBridges() {
        // Only pass bridges the bundled Tor can actually run. A single unsupported line (e.g. obfs3
        // or meek) makes Tor reject the whole config, so unsupported lines are excluded here while
        // the user's raw text is preserved via Preferences.setCustomBridges().
        List<String> usableBridges = getEnteredBridgeLines().stream()
                .filter(DefaultPluggableTransports::isSupportedBridge)
                .collect(Collectors.toList());
        preferences.setBridgeAddresses(usableBridges.isEmpty() ? null : usableBridges);
        updateBridgeValidationFeedback();
    }

    private List<String> getEnteredBridgeLines() {
        if (customBridges == null) return new ArrayList<>();
        return Arrays.stream(customBridges.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toList());
    }

    private void updateBridgeValidationFeedback() {
        if (bridgeValidationLabel == null) return;

        String message = "";
        if (selectedBridgeOption == BridgeOption.CUSTOM) {
            List<String> unsupportedTransports = getEnteredBridgeLines().stream()
                    .filter(line -> !DefaultPluggableTransports.isSupportedBridge(line))
                    .map(DefaultPluggableTransports::transportOf)
                    .distinct()
                    .collect(Collectors.toList());
            if (!unsupportedTransports.isEmpty()) {
                String supported = String.join(", ", DefaultPluggableTransports.SUPPORTED_TRANSPORTS);
                message = Res.get("torNetworkSettingWindow.bridges.unsupported",
                        String.join(", ", unsupportedTransports), supported);
            }
        }

        bridgeValidationLabel.setText(message);
        if (!message.isEmpty()) {
            if (!bridgeValidationLabel.getStyleClass().contains("error-text")) bridgeValidationLabel.getStyleClass().add("error-text");
        } else {
            bridgeValidationLabel.getStyleClass().remove("error-text");
        }
    }

    private void saveAndShutDown() {
        UserThread.runAfter(() -> actionHandlerOptional.ifPresent(Runnable::run), 500, TimeUnit.MILLISECONDS);
        hide();
    }
}
