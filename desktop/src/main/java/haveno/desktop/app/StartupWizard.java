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

package haveno.desktop.app;

import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import javax.annotation.Nullable;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.NumberBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * First-run setup wizard hosted in the {@link StartupShell} content slot: pages through the
 * given steps with back/next navigation and reports completion or the user quitting.
 */
public class StartupWizard {

    /** A single wizard page; the wizard owns navigation, the step owns its content and validation. */
    public interface Step {
        Region getContent();

        /** Called on next; the step displays its own errors and returns false to block navigation. */
        boolean validate();

        String getNextButtonText();

        default String getQuitButtonText() {
            return Res.get("shared.shutDown");
        }

        default void onShown() {
        }

        /** Optional live gate: the next button stays disabled while this is true (e.g. until input validates). */
        @Nullable
        default ObservableBooleanValue nextBlocked() {
            return null;
        }
    }

    public static final double PAGE_WIDTH = 800;
    private static final double PAGE_HEIGHT = 430;

    private final List<Step> steps;
    private final Runnable onComplete;
    private final VBox root;
    private final StackPane contentSlot = new StackPane();
    private final Button backButton, nextButton, quitButton;
    private final Label footerLabel = new AutoTooltipLabel();
    // combined with the current step's own gate to drive the next button's disable binding
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private int stepIndex;

    public StartupWizard(List<Step> steps, Runnable onComplete, Runnable onQuit) {
        this.steps = steps;
        this.onComplete = onComplete;

        contentSlot.setAlignment(Pos.TOP_CENTER);
        contentSlot.setMinHeight(PAGE_HEIGHT);
        contentSlot.setPrefHeight(PAGE_HEIGHT);
        contentSlot.setMaxWidth(PAGE_WIDTH);

        backButton = new AutoTooltipButton("< " + Res.get("tacWindow.legal.back"));
        nextButton = new AutoTooltipButton();
        nextButton.setDefaultButton(true);
        nextButton.getStyleClass().add("action-button");
        quitButton = new AutoTooltipButton();

        // size all buttons to the widest label so they match, adapting to whatever the translated text is
        NumberBinding buttonWidth = Bindings.max(backButton.widthProperty(),
                Bindings.max(nextButton.widthProperty(), quitButton.widthProperty()));
        backButton.minWidthProperty().bind(buttonWidth);
        nextButton.minWidthProperty().bind(buttonWidth);
        quitButton.minWidthProperty().bind(buttonWidth);

        backButton.setOnAction(event -> showStep(stepIndex - 1));
        nextButton.setOnAction(event -> onNext());
        quitButton.setOnAction(event -> {
            setControlsDisabled(true);
            footerLabel.setText(Res.get("password.startup.shuttingDown"));
            onQuit.run();
        });

        HBox buttonBox = new HBox(10, backButton, quitButton, nextButton);
        buttonBox.setAlignment(Pos.CENTER);

        footerLabel.getStyleClass().add("startup-wizard-footer-label");
        footerLabel.setMinHeight(24);
        footerLabel.setAlignment(Pos.CENTER);

        root = new VBox(15, contentSlot, buttonBox, footerLabel);
        root.setAlignment(Pos.TOP_CENTER);
        root.setMaxWidth(PAGE_WIDTH);
        VBox.setMargin(buttonBox, new Insets(15, 0, 0, 0));

        showStep(0);
    }

    public Region getRoot() {
        return root;
    }

    private void showStep(int index) {
        stepIndex = index;
        Step step = steps.get(index);
        contentSlot.getChildren().setAll(step.getContent());
        backButton.setVisible(index > 0);
        backButton.setManaged(index > 0);
        nextButton.setText(step.getNextButtonText());
        quitButton.setText(step.getQuitButtonText());
        footerLabel.setText(steps.size() > 1 ? Res.get("startupWizard.step", index + 1, steps.size()) : "");
        ObservableBooleanValue blocked = step.nextBlocked();
        nextButton.disableProperty().unbind();
        nextButton.disableProperty().bind(blocked == null ? busy : busy.or(blocked));
        step.onShown();
    }

    private void onNext() {
        if (!steps.get(stepIndex).validate()) return;
        if (stepIndex < steps.size() - 1) {
            showStep(stepIndex + 1);
        } else {
            // keep the working state until app startup replaces this screen
            setControlsDisabled(true);
            footerLabel.setText(Res.get("startupWizard.finishing"));
            onComplete.run();
        }
    }

    private void setControlsDisabled(boolean disabled) {
        backButton.setDisable(disabled);
        busy.set(disabled);
        quitButton.setDisable(disabled);
    }
}
