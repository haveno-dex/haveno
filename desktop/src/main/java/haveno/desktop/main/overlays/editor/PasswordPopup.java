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

package haveno.desktop.main.overlays.editor;

import haveno.common.util.Utilities;
import haveno.core.locale.GlobalSettings;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.FormBuilder;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Camera;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.transform.Rotate;
import javafx.stage.Modality;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

import de.jensd.fx.fontawesome.AwesomeIcon;

import static haveno.desktop.util.FormBuilder.addInputTextField;

@Slf4j
public class PasswordPopup extends Overlay<PasswordPopup> {
    private InputTextField inputTextField;
    private static PasswordPopup INSTANCE;
    private Consumer<String> actionHandler;
    private ChangeListener<Boolean> focusListener;
    private EventHandler<KeyEvent> keyEventEventHandler;

    public PasswordPopup() {
        width = 600;
        type = Type.Confirmation;
        if (INSTANCE != null)
            INSTANCE.hide();
        INSTANCE = this;
    }

    public PasswordPopup onAction(Consumer<String> confirmHandler) {
        this.actionHandler = confirmHandler;
        return this;
    }

    @Override
    public void show() {
        actionButtonText("Confirm");
        createGridPane();
        addHeadLine();
        addContent();
        addButtons();
        applyStyles();
        onShow();
    }

    @Override
    protected void onShow() {
        super.display();

        if (stage != null) {
            focusListener = (observable, oldValue, newValue) -> {
                if (!newValue)
                    hide();
            };
            stage.focusedProperty().addListener(focusListener);

            Scene scene = stage.getScene();
            if (scene != null)
                scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }
    }

    @Override
    public void hide() {
        animateHide();
    }

    @Override
    protected void onHidden() {
        INSTANCE = null;

        if (stage != null) {
            if (focusListener != null)
                stage.focusedProperty().removeListener(focusListener);

            Scene scene = stage.getScene();
            if (scene != null)
                scene.removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }
    }

    private void addContent() {
        gridPane.setPadding(new Insets(64));

        inputTextField = addInputTextField(gridPane, ++rowIndex, null, -10d);
        GridPane.setColumnSpan(inputTextField, 2);
        inputTextField.requestFocus();

        keyEventEventHandler = event -> {
            if (Utilities.isAltOrCtrlPressed(KeyCode.R, event)) {
                doClose();
            }
        };
    }

    @Override
    protected void addHeadLine() {
        super.addHeadLine();
        GridPane.setHalignment(headLineLabel, HPos.CENTER);
    }

    protected void setupKeyHandler(Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                doClose();
            }
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                apply();
            }
        });
    }

    @Override
    protected void animateHide(Runnable onFinishedHandler) {
        if (GlobalSettings.getUseAnimations()) {
            double duration = getDuration(300);
            Interpolator interpolator = Interpolator.SPLINE(0.25, 0.1, 0.25, 1);

            gridPane.setRotationAxis(Rotate.X_AXIS);
            Camera camera = gridPane.getScene().getCamera();
            gridPane.getScene().setCamera(new PerspectiveCamera());

            Timeline timeline = new Timeline();
            ObservableList<KeyFrame> keyFrames = timeline.getKeyFrames();
            keyFrames.add(new KeyFrame(Duration.millis(0),
                    new KeyValue(gridPane.rotateProperty(), 0, interpolator),
                    new KeyValue(gridPane.opacityProperty(), 1, interpolator)
            ));
            keyFrames.add(new KeyFrame(Duration.millis(duration),
                    new KeyValue(gridPane.rotateProperty(), -90, interpolator),
                    new KeyValue(gridPane.opacityProperty(), 0, interpolator)
            ));
            timeline.setOnFinished(event -> {
                gridPane.setRotate(0);
                gridPane.setRotationAxis(Rotate.Z_AXIS);
                gridPane.getScene().setCamera(camera);
                onFinishedHandler.run();
            });
            timeline.play();
        } else {
            onFinishedHandler.run();
        }
    }

    @Override
    protected void animateDisplay() {
        if (GlobalSettings.getUseAnimations()) {
            double startY = -160;
            double duration = getDuration(400);
            Interpolator interpolator = Interpolator.SPLINE(0.25, 0.1, 0.25, 1);
            Timeline timeline = new Timeline();
            ObservableList<KeyFrame> keyFrames = timeline.getKeyFrames();
            keyFrames.add(new KeyFrame(Duration.millis(0),
                    new KeyValue(gridPane.opacityProperty(), 0, interpolator),
                    new KeyValue(gridPane.translateYProperty(), startY, interpolator)
            ));

            keyFrames.add(new KeyFrame(Duration.millis(duration),
                    new KeyValue(gridPane.opacityProperty(), 1, interpolator),
                    new KeyValue(gridPane.translateYProperty(), 0, interpolator)
            ));

            timeline.play();
        }
    }

    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.setPadding(new Insets(15, 15, 30, 30));
    }

    @Override
    protected void addButtons() {
        buttonDistance = 10;
        super.addButtons();

        actionButton.setOnAction(event -> apply());
    }

    private void apply() {
        hide();
        if (actionHandler != null && inputTextField != null)
            actionHandler.accept(inputTextField.getText());
    }

    @Override
    protected void applyStyles() {
        super.applyStyles();
        FormBuilder.getIconForLabel(AwesomeIcon.LOCK, headlineIcon, "1.5em");
    }

    @Override
    protected void setModality() {
        stage.initOwner(owner.getScene().getWindow());
        stage.initModality(Modality.NONE);
    }

    @Override
    protected void addEffectToBackground() {
    }

    @Override
    protected void removeEffectFromBackground() {
    }
}
