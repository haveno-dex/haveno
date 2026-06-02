package haveno.desktop.components;

import com.jfoenix.adapters.ReflectionHelper;
import com.jfoenix.controls.base.IFXLabelFloatControl;
import com.jfoenix.skins.PromptLinesWrapper;
import com.jfoenix.skins.ValidationPane;
import javafx.beans.property.DoubleProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;

import java.lang.reflect.Field;

/**
 * Code copied and adapted from com.jfoenix.skins.JFXTextFieldSkin
 * Completely hardened against NullPointerExceptions
 */

public class JFXTextFieldSkinHavenoStyle<T extends TextField & IFXLabelFloatControl> extends TextFieldSkin {

    private double inputLineExtension;
    private boolean invalid = true;

    private Text promptText;
    private Pane textPane;
    private Node textNode;
    private ObservableDoubleValue textRight;
    private DoubleProperty textTranslateX;

    private ValidationPane<T> errorContainer;
    private PromptLinesWrapper<T> linesWrapper;

    public JFXTextFieldSkinHavenoStyle(T textField, double inputLineExtension) {
        super(textField);
        
        try {
            if (this.getChildren() != null && !this.getChildren().isEmpty()) {
                textPane = (Pane) this.getChildren().get(0);
            }
        } catch (Exception e) {
            // Ignore
        }
        
        this.inputLineExtension = inputLineExtension;

        try {
            // get parent fields
            textNode = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textNode");
            textTranslateX = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textTranslateX");
            textRight = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textRight");
        } catch (Exception e) {
            // Ignore
        }

        try {
            linesWrapper = new PromptLinesWrapper<T>(
                    textField,
                    promptTextFillProperty(),
                    textField.textProperty(),
                    textField.promptTextProperty(),
                    () -> promptText);

            if (textPane != null) {
                linesWrapper.init(() -> createPromptNode(), textPane);
            }

            if (linesWrapper.usePromptText != null) {
                ReflectionHelper.setFieldContent(TextFieldSkin.class, this, "usePromptText", linesWrapper.usePromptText);
            }

            errorContainer = new ValidationPane<>(textField);

            if (linesWrapper.line != null && linesWrapper.focusedLine != null && linesWrapper.promptContainer != null && errorContainer != null) {
                getChildren().addAll(linesWrapper.line, linesWrapper.focusedLine, linesWrapper.promptContainer, errorContainer);
            }

            if (textField.disableProperty() != null) registerChangeListener(textField.disableProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateDisabled(); });
            if (textField.focusColorProperty() != null) registerChangeListener(textField.focusColorProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateFocusColor(); });
            if (textField.unFocusColorProperty() != null) registerChangeListener(textField.unFocusColorProperty(), obs -> { if (linesWrapper != null) linesWrapper.updateUnfocusColor(); });
            if (textField.disableAnimationProperty() != null) registerChangeListener(textField.disableAnimationProperty(), obs -> { if (errorContainer != null) errorContainer.updateClip(); });
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
            final double width = getSkinnable().getWidth() + inputLineExtension;
            
            double paddingLeft = 0;
            if (getSkinnable().getPadding() != null) {
                paddingLeft = getSkinnable().getPadding().getLeft();
            }

            if (linesWrapper != null) {
                linesWrapper.layoutLines(x, y, width, h, height, Math.floor(h));
                if (errorContainer != null && linesWrapper.focusedLine != null) {
                    errorContainer.layoutPane(x - paddingLeft, height + linesWrapper.focusedLine.getHeight(), width, h);
                }
            }

            if (getSkinnable().getWidth() > 0) {
                updateTextPos();
            }

            if (linesWrapper != null) {
                linesWrapper.updateLabelFloatLayout();
            }

            if (invalid) {
                invalid = false;
                if (errorContainer != null) errorContainer.invalid(w);
                if (linesWrapper != null) linesWrapper.invalid();
            }
        } catch (Exception e) {
            // Ignore layout exceptions to prevent crashes
        }
    }

    private void updateTextPos() {
        try {
            // Attempt to recover textNode if it was not found initially
            if (textNode == null) {
                textNode = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textNode");
            }
            if (textNode == null || textNode.getLayoutBounds() == null) return;

            double textWidth = textNode.getLayoutBounds().getWidth();
            final double promptWidth = (promptText == null || promptText.getLayoutBounds() == null) ? 0 : promptText.getLayoutBounds().getWidth();
            
            if (getSkinnable() == null || getSkinnable().getAlignment() == null) return;
            
            switch (getSkinnable().getAlignment().getHpos()) {
                case CENTER:
                    if (linesWrapper != null && linesWrapper.promptTextScale != null) {
                        linesWrapper.promptTextScale.setPivotX(promptWidth / 2);
                    }
                    if (textRight != null && textRight.get() > 0 && textTranslateX != null) {
                        double midPoint = textRight.get() / 2;
                        double newX = midPoint - textWidth / 2;
                        if (newX + textWidth <= textRight.get()) {
                            textTranslateX.set(newX);
                        }
                    }
                    break;
                case LEFT:
                    if (linesWrapper != null && linesWrapper.promptTextScale != null) {
                        linesWrapper.promptTextScale.setPivotX(0);
                    }
                    break;
                case RIGHT:
                    if (linesWrapper != null && linesWrapper.promptTextScale != null) {
                        linesWrapper.promptTextScale.setPivotX(promptWidth);
                    }
                    break;
            }
        } catch (Exception e) {
            // Ignore to prevent crash
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
            
            promptText.setLayoutX(1);
            
            if (linesWrapper.promptTextScale != null) {
                promptText.getTransforms().add(linesWrapper.promptTextScale);
            }
            
            if (linesWrapper.promptContainer != null) {
                linesWrapper.promptContainer.getChildren().add(promptText);
            }
            
            if (getSkinnable() != null && getSkinnable().isFocused() && getSkinnable() instanceof IFXLabelFloatControl && ((IFXLabelFloatControl) getSkinnable()).isLabelFloat()) {
                if (textPane != null) promptText.setTranslateY(-Math.floor(textPane.getHeight()));
                if (linesWrapper.promptTextScale != null) {
                    linesWrapper.promptTextScale.setX(0.85);
                    linesWrapper.promptTextScale.setY(0.85);
                }
            }

            try {
                Field field = ReflectionHelper.getField(TextFieldSkin.class, "promptNode");
                if (field != null) {
                    Object oldValue = field.get(this);
                    if (oldValue != null && textPane != null && textPane.getChildren() != null) {
                        textPane.getChildren().remove(oldValue);
                    }
                    field.set(this, promptText);
                }
            } catch (Exception e) {
                // Ignore reflection errors to prevent crash
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}