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

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.user.Preferences;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.util.FormBuilder;
import java.time.LocalDate;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * First-run setup wizard hosted in the {@link StartupShell} content slot: pages through the
 * given steps with back/next navigation and reports completion or the user quitting.
 * Steps may exclude themselves from the current path via {@link Step#isSkipped()}.
 */
public class StartupWizard {

    /** A single wizard page; the wizard owns navigation, the step owns its content and validation. */
    public interface Step {
        Region getContent();

        /**
         * Validate on next and report whether to advance; the step displays its own errors.
         * May report asynchronously from any thread (e.g. for slow validations off the JavaFX thread).
         */
        void validate(Consumer<Boolean> resultHandler);

        String getNextButtonText();

        /** Optional label for an explicit quit action on this step; when null, only the first step offers quitting. */
        @Nullable
        default String getQuitButtonText() {
            return null;
        }

        /** True to leave this step out of the current path (e.g. optional setup on a quick start). */
        default boolean isSkipped() {
            return false;
        }

        default void onShown() {
        }

        /** Optional live gate: the next button stays disabled while this is true (e.g. until input validates). */
        @Nullable
        default ObservableBooleanValue nextBlocked() {
            return null;
        }
    }

    /** The user's choices, reported on completion. */
    @Value
    public static class Result {
        @Nullable
        String walletSeed;
        @Nullable
        Long walletRestoreHeight;
        @Nullable
        LocalDate walletRestoreDate;
        @Nullable
        String password;
        @Nullable
        TradeCurrency preferredTradeCurrency;
        @Nullable
        String customMoneroNodes;
        @Nullable
        Preferences.UseTorForXmr useTorForXmr;
    }

    public static final double PAGE_WIDTH = 800;
    private static final double PAGE_HEIGHT = 430;
    // the page slot may compress to this so the branding and bottom bar stay visible at small window heights
    private static final double MIN_PAGE_HEIGHT = 385;

    private final List<Step> steps;
    private final Runnable onComplete;
    private final VBox root;
    private final StackPane contentSlot = new StackPane();
    private final HBox progressDots = new HBox(7);
    private final Button backButton, nextButton, quitButton;
    // combined with the current step's own gate to drive the next button's disable binding
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private int stepIndex;

    public StartupWizard(List<Step> steps, Runnable onComplete, Runnable onQuit) {
        this.steps = steps;
        this.onComplete = onComplete;

        contentSlot.setAlignment(Pos.TOP_CENTER);
        contentSlot.setMinHeight(MIN_PAGE_HEIGHT);
        contentSlot.setPrefHeight(PAGE_HEIGHT);
        // fixed page width so the card keeps the same size on steps with narrow content
        contentSlot.setPrefWidth(PAGE_WIDTH);
        contentSlot.setMaxWidth(PAGE_WIDTH);

        backButton = new AutoTooltipButton("< " + Res.get("tacWindow.legal.back"));
        backButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-back-button");
        nextButton = new AutoTooltipButton();
        nextButton.setDefaultButton(true);
        nextButton.getStyleClass().addAll("action-button", "tac-agreement-action-button");
        quitButton = new AutoTooltipButton(Res.get("shared.shutDown"));
        quitButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-reject-button");

        // fixed min widths like TacWindow so the action buttons hold one size across steps
        nextButton.setMinWidth(190);
        quitButton.setMinWidth(190);
        backButton.setMinWidth(110);

        backButton.setOnAction(event -> showStep(previousIndex(), false));
        nextButton.setOnAction(event -> onNext());
        quitButton.setOnAction(event -> {
            setControlsDisabled(true);
            // the disabled button itself reports the state, keeping the area below the dots clear
            quitButton.setText(Res.get("password.startup.shuttingDown"));
            onQuit.run();
        });

        // modal-style footer: back at the left, quit/next at the right
        HBox actionButtons = new HBox(12, quitButton, nextButton);
        actionButtons.setAlignment(Pos.CENTER_RIGHT);
        actionButtons.setMaxWidth(Region.USE_PREF_SIZE);
        actionButtons.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane footer = new StackPane(backButton, actionButtons);
        StackPane.setAlignment(backButton, Pos.CENTER_LEFT);
        StackPane.setAlignment(actionButtons, Pos.CENTER_RIGHT);

        Region footerSeparator = new Region();
        footerSeparator.getStyleClass().add("tac-agreement-footer-separator");

        // the pages and footer share one floating card so the wizard reads as a contained dialog
        VBox card = new VBox(12, contentSlot, footerSeparator, footer);
        card.getStyleClass().add("wizard-card");
        card.setMaxWidth(Region.USE_PREF_SIZE);

        // the dots ride below the card so the footer buttons can never crowd them
        progressDots.setAlignment(Pos.CENTER);
        progressDots.setMaxWidth(Region.USE_PREF_SIZE);
        progressDots.setMaxHeight(Region.USE_PREF_SIZE);

        root = new VBox(12, card, progressDots);
        root.setAlignment(Pos.TOP_CENTER);

        showStep(0, false);
    }

    public Region getRoot() {
        return root;
    }

    /** Re-read the current step's navigation state (button text, path dots) after a step-internal change. */
    public void refreshNavigation() {
        Step step = steps.get(stepIndex);
        // the last step on the path launches the app, so it gets the start label instead of its own
        nextButton.setText(nextIndex() < 0 ? Res.get("startupWizard.start") : step.getNextButtonText());
        updateProgressDots();
    }

    /** A page header shared by the wizard steps and the user agreement content: icon, title, subtitle, divider. */
    public static VBox createHeaderSection(MaterialDesignIcon icon, String titleText, String subtitleText) {
        HBox header = new HBox(14);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setFillHeight(false);

        StackPane iconBox = new StackPane(createIcon(icon, "1.9em", "wizard-header-icon"));
        iconBox.getStyleClass().add("wizard-header-icon-box");
        iconBox.setMinWidth(56);
        iconBox.setMaxWidth(56);

        Label title = new Label(titleText);
        title.getStyleClass().add("wizard-header-title");
        title.setWrapText(true);

        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("wizard-header-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(3, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        header.getChildren().addAll(iconBox, titleBox);

        Region divider = new Region();
        divider.getStyleClass().add("wizard-header-divider");

        return new VBox(10, header, divider);
    }

    public static Text createIcon(MaterialDesignIcon icon, String size, String styleClass) {
        Text textIcon = FormBuilder.getIcon(icon, size);
        textIcon.getStyleClass().add(styleClass);
        textIcon.setMouseTransparent(true);
        return textIcon;
    }

    /** A selectable option card: icon, title, optional badge, and description; hosts wire exclusive selection. */
    public static class ChoiceCard extends StackPane {

        private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");

        private final Text checkMark;
        private boolean selected;
        private Runnable onSelect;

        public ChoiceCard(MaterialDesignIcon icon, String titleText, @Nullable String badgeText, String bodyText) {
            getStyleClass().add("wizard-choice-card");

            StackPane iconBox = new StackPane(createIcon(icon, "1.4em", "wizard-choice-card-icon"));
            iconBox.getStyleClass().add("wizard-choice-card-icon-box");

            Label title = new Label(titleText);
            title.getStyleClass().add("wizard-choice-card-title");

            HBox titleRow = new HBox(8, title);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            if (badgeText != null) {
                Label badge = new Label(badgeText);
                badge.getStyleClass().add("wizard-choice-card-badge");
                titleRow.getChildren().add(badge);
            }

            Label body = new Label(bodyText);
            body.getStyleClass().add("wizard-choice-card-body");
            body.setWrapText(true);

            VBox textBox = new VBox(4, titleRow, body);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            HBox row = new HBox(14, iconBox, textBox);
            row.setAlignment(Pos.TOP_LEFT);
            row.setFillHeight(false);

            checkMark = createIcon(MaterialDesignIcon.CHECK_CIRCLE, "1.1em", "wizard-choice-card-check");
            checkMark.setVisible(false);
            StackPane.setAlignment(checkMark, Pos.TOP_RIGHT);

            getChildren().addAll(row, checkMark);

            setFocusTraversable(true);
            setOnMouseClicked(event -> select());
            setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.SPACE) {
                    select();
                    event.consume();
                }
            });
        }

        /** Wire the cards as an exclusive group: selecting one deselects the others and reports the change. */
        public static void group(Runnable onChange, ChoiceCard... cards) {
            for (ChoiceCard card : cards) {
                card.onSelect = () -> {
                    for (ChoiceCard other : cards) other.setSelected(other == card);
                    onChange.run();
                };
            }
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
            checkMark.setVisible(selected);
            pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, selected);
        }

        public boolean isSelected() {
            return selected;
        }

        private void select() {
            if (!selected && onSelect != null) onSelect.run();
        }
    }

    private void showStep(int index, boolean animate) {
        boolean forward = index > stepIndex;
        stepIndex = index;
        Step step = steps.get(index);
        Region content = step.getContent();
        contentSlot.getChildren().setAll(content);
        if (animate) playStepTransition(content, forward);
        // the first step offers quitting; later steps offer going back, plus quitting where a step labels it explicitly
        boolean first = previousIndex() < 0;
        String quitText = step.getQuitButtonText();
        boolean offersQuit = first || quitText != null;
        quitButton.setVisible(offersQuit);
        quitButton.setManaged(offersQuit);
        quitButton.setText(quitText != null ? quitText : Res.get("shared.shutDown"));
        backButton.setVisible(!first);
        backButton.setManaged(!first);
        ObservableBooleanValue blocked = step.nextBlocked();
        nextButton.disableProperty().unbind();
        nextButton.disableProperty().bind(blocked == null ? busy : busy.or(blocked));
        refreshNavigation();
        step.onShown();
    }

    // ease the incoming page in with a subtle directional slide
    private void playStepTransition(Region content, boolean forward) {
        content.setOpacity(0);
        content.setTranslateX(forward ? 32 : -32);
        FadeTransition fade = new FadeTransition(Duration.millis(250), content);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(250), content);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, slide).play();
    }

    private void onNext() {
        setControlsDisabled(true);
        steps.get(stepIndex).validate(valid -> UserThread.execute(() -> {
            int nextIndex = nextIndex();
            if (!valid) {
                setControlsDisabled(false);
            } else if (nextIndex >= 0) {
                setControlsDisabled(false);
                showStep(nextIndex, true);
            } else {
                // keep the working state until app startup replaces this screen; the disabled
                // start button itself reports the progress
                nextButton.setText(Res.get("startupWizard.finishing"));
                onComplete.run();
            }
        }));
    }

    private int nextIndex() {
        for (int i = stepIndex + 1; i < steps.size(); i++) {
            if (!steps.get(i).isSkipped()) return i;
        }
        return -1;
    }

    private int previousIndex() {
        for (int i = stepIndex - 1; i >= 0; i--) {
            if (!steps.get(i).isSkipped()) return i;
        }
        return -1;
    }

    // one dot per step on the current path, marking the current step
    private void updateProgressDots() {
        progressDots.getChildren().clear();
        for (Step step : steps) {
            if (step.isSkipped()) continue;
            Region dot = new Region();
            dot.getStyleClass().add("wizard-progress-dot");
            if (step == steps.get(stepIndex)) dot.getStyleClass().add("wizard-progress-dot-active");
            progressDots.getChildren().add(dot);
        }
        if (progressDots.getChildren().size() <= 1) progressDots.getChildren().clear();
        progressDots.setManaged(!progressDots.getChildren().isEmpty());
    }

    private void setControlsDisabled(boolean disabled) {
        backButton.setDisable(disabled);
        busy.set(disabled);
        quitButton.setDisable(disabled);
    }
}
