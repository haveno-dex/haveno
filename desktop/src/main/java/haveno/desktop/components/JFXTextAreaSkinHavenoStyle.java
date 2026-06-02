package haveno.desktop.components;

import com.jfoenix.adapters.ReflectionHelper;
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.skins.PromptLinesWrapper;
import com.jfoenix.skins.ValidationPane;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Code copied and adapted from com.jfoenix.skins.JFXTextAreaSkin
 * Completely hardened against NullPointerExceptions
 */

public class JFXTextAreaSkinHavenoStyle extends TextAreaSkin {

    private boolean invalid = true;

    private ScrollPane scrollPane;
    private Text promptText;

    private ValidationPane<JFXTextArea> errorContainer;
    private PromptLinesWrapper<JFXTextArea> linesWrapper;

    public JFXTextAreaSkinHavenoStyle(JFXTextArea textArea) {
        super(textArea);
        
        try {
            if (this.getChildren() != null && !this.getChildren().isEmpty()) {
                scrollPane = (ScrollPane) getChildren().get(0);
            }
        } catch (Exception e) {
            // Ignore
        }

        if (textArea != null) {
            textArea.setWrapText(true);
        }

        try {
            linesWrapper = new PromptLinesWrapper<>(
                    textArea,
                    promptTextFillProperty(),
                    textArea != null ? textArea.textProperty() : null,
                    textArea != null ? textArea.promptTextProperty() : null,
                    () -> promptText);

            if (scrollPane != null) {
                linesWrapper.init(() -> createPromptNode(), scrollPane);
            }
            
            errorContainer = new ValidationPane<>(textArea);
            
            if (linesWrapper.line != null && linesWrapper.focusedLine != null && linesWrapper.promptContainer != null && errorContainer != null) {
                getChildren().addAll(linesWrapper.line, linesWrapper.focusedLine, linesWrapper.promptContainer, errorContainer);
            }

            if (textArea != null) {
                if (textArea.disableProperty() != null) registerChangeListener(textArea.disableProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateDisabled(); });
                if (textArea.focusColorProperty() != null) registerChangeListener(textArea.focusColorProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateFocusColor(); });
                if (textArea.unFocusColorProperty() != null) registerChangeListener(textArea.unFocusColorProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateUnfocusColor(); });
                if (textArea.disableAnimationProperty() != null) registerChangeListener(textArea.disableAnimationProperty(), obs -> { if (errorContainer != null) errorContainer.updateClip(); });
            }
        } catch (Exception e) {
            // Ignore setup errors
        }
    }


    @Override
    protected void layoutChildren(final double x, final double y, final double w, final double h) {
        super.layoutChildren(x, y, w, h);

        if (getSkinnable() == null) return;

        try {
            final double height = getSkinnable().getHeight();
            final double width = getSkinnable().getWidth();
            
            if (linesWrapper != null) {
                linesWrapper.layoutLines(x - 2, y - 2, width, h, height, (promptText == null || promptText.getLayoutBounds() == null) ? 0 : promptText.getLayoutBounds().getHeight() + 3);
                if (errorContainer != null && linesWrapper.focusedLine != null) {
                    errorContainer.layoutPane(x, height + linesWrapper.focusedLine.getHeight(), width, h);
                }
                linesWrapper.updateLabelFloatLayout();
            }

            if (invalid) {
                invalid = false;
                if (scrollPane != null && scrollPane.getChildrenUnmodifiable() != null && !scrollPane.getChildrenUnmodifiable().isEmpty()) {
                    Region viewPort = (Region) scrollPane.getChildrenUnmodifiable().get(0);
                    if (viewPort != null) {
                        viewPort.setBackground(new Background(new BackgroundFill(Color.TRANSPARENT, CornerRadii.EMPTY, Insets.EMPTY)));
                        viewPort.applyCss();
                    }
                }
                if (errorContainer != null) errorContainer.invalid(w);
                if (linesWrapper != null) linesWrapper.invalid();
            }
        } catch (Exception e) {
            // Ignore layout exceptions to prevent crashes
        }
    }

    private void createPromptNode() {
        try {
            if (promptText != null || linesWrapper == null || linesWrapper.usePromptText == null || !linesWrapper.usePromptText.get()) {
                return;
            }
            promptText = new Text();
            promptText.setManaged(false);
            promptText.getStyleClass().add("text");
            promptText.visibleProperty().bind(linesWrapper.usePromptText);
            
            if (getSkinnable() != null) {
                if (getSkinnable().fontProperty() != null) promptText.fontProperty().bind(getSkinnable().fontProperty());
                if (getSkinnable().promptTextProperty() != null) promptText.textProperty().bind(getSkinnable().promptTextProperty());
            }
            
            if (linesWrapper.animatedPromptTextFill != null) {
                promptText.fillProperty().bind(linesWrapper.animatedPromptTextFill);
            }
            
            if (linesWrapper.promptTextScale != null) {
                promptText.getTransforms().add(linesWrapper.promptTextScale);
            }
            
            if (linesWrapper.promptContainer != null) {
                linesWrapper.promptContainer.getChildren().add(promptText);
            }
            
            if (getSkinnable() != null && getSkinnable().isFocused() && getSkinnable() instanceof JFXTextArea && ((JFXTextArea) getSkinnable()).isLabelFloat()) {
                if (scrollPane != null) promptText.setTranslateY(-Math.floor(scrollPane.getHeight()));
                if (linesWrapper.promptTextScale != null) {
                    linesWrapper.promptTextScale.setX(0.85);
                    linesWrapper.promptTextScale.setY(0.85);
                }
            }

            try {
                Field field = ReflectionHelper.getField(TextAreaSkin.class, "promptNode");
                if (field != null) {
                    Object oldValue = field.get(this);
                    if (oldValue != null && oldValue instanceof Node) {
                        // Safely remove highlight instead of crashing
                        try {
                            removeHighlight(Arrays.asList((Node) oldValue));
                        } catch (Exception ex) {}
                    }
                    field.set(this, promptText);
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}