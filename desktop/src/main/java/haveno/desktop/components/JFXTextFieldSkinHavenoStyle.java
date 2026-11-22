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
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * Code copied and adapted from com.jfoenix.skins.JFXTextFieldSkin
 */

@Slf4j
public class JFXTextFieldSkinHavenoStyle<T extends TextField & IFXLabelFloatControl> extends TextFieldSkin {

    private static boolean promptNodeFallbackLogged = false;

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
        textPane = (Pane) this.getChildren().get(0);
        this.inputLineExtension = inputLineExtension;

        // get parent fields
        textNode = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textNode");
        textTranslateX = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textTranslateX");
        textRight = ReflectionHelper.getFieldContent(TextFieldSkin.class, this, "textRight");

        linesWrapper = new PromptLinesWrapper<T>(
                textField,
                promptTextFillProperty(),
                textField.textProperty(),
                textField.promptTextProperty(),
                () -> promptText);

        linesWrapper.init(() -> createPromptNode(), textPane);

        ReflectionHelper.setFieldContent(TextFieldSkin.class, this, "usePromptText", linesWrapper.usePromptText);

        errorContainer = new ValidationPane<>(textField);

        getChildren().addAll(linesWrapper.line, linesWrapper.focusedLine, linesWrapper.promptContainer, errorContainer);

        registerChangeListener(textField.disableProperty(), obs -> linesWrapper.updateDisabled());
        registerChangeListener(textField.focusColorProperty(), obs -> {
            linesWrapper.updateFocusColor();
            linesWrapper.invalid(); // refresh the floating label when validation changes its color
        });
        registerChangeListener(textField.unFocusColorProperty(), obs -> linesWrapper.updateUnfocusColor());
        registerChangeListener(textField.disableAnimationProperty(), obs -> errorContainer.updateClip());
    }

    @Override
    protected void layoutChildren(final double x, final double y, final double w, final double h) {
        super.layoutChildren(x, y, w, h);

        final double height = getSkinnable().getHeight();
        final double width = getSkinnable().getWidth() + inputLineExtension;
        final double paddingLeft = getSkinnable().getPadding().getLeft();
        linesWrapper.layoutLines(x, y, width, h, height, Math.floor(h));
        errorContainer.layoutPane(x - paddingLeft, height + linesWrapper.focusedLine.getHeight(), width, h);

        if (getSkinnable().getWidth() > 0) {
            updateTextPos();
        }

        linesWrapper.updateLabelFloatLayout();

        if (invalid) {
            invalid = false;
            // update validation container
            errorContainer.invalid(w);
            // focus
            linesWrapper.invalid();
        }
    }


    private void updateTextPos() {
        if (textNode == null || textRight == null || textTranslateX == null) {
            return; // reflective access to TextFieldSkin failed, keep JavaFX's default text position
        }
        double textWidth = textNode.getLayoutBounds().getWidth();
        final double promptWidth = promptText == null ? 0 : promptText.getLayoutBounds().getWidth();
        switch (getSkinnable().getAlignment().getHpos()) {
            case CENTER:
                linesWrapper.promptTextScale.setPivotX(promptWidth / 2);
                double midPoint = textRight.get() / 2;
                double newX = midPoint - textWidth / 2;
                if (newX + textWidth <= textRight.get()) {
                    textTranslateX.set(newX);
                }
                break;
            case LEFT:
                linesWrapper.promptTextScale.setPivotX(0);
                break;
            case RIGHT:
                linesWrapper.promptTextScale.setPivotX(promptWidth);
                break;
        }

    }

    private void createPromptNode() {
        if (promptText != null || !linesWrapper.usePromptText.get()) {
            return;
        }
        // ReflectionHelper.getField returns null when reflective access is blocked (e.g. missing
        // --add-opens flags). In that case keep JavaFX's own prompt text instead of adding a second one.
        Field promptNodeField = ReflectionHelper.getField(TextFieldSkin.class, "promptNode");
        if (promptNodeField == null) {
            if (!promptNodeFallbackLogged) {
                promptNodeFallbackLogged = true;
                log.warn("Cannot access TextFieldSkin.promptNode via reflection, falling back to default JavaFX prompt text");
            }
            return;
        }
        promptText = new Text();
        promptText.setManaged(false);
        promptText.getStyleClass().add("text");
        promptText.visibleProperty().bind(linesWrapper.usePromptText);
        promptText.fontProperty().bind(getSkinnable().fontProperty());
        promptText.textProperty().bind(getSkinnable().promptTextProperty());
        promptText.fillProperty().bind(linesWrapper.animatedPromptTextFill);
        promptText.setLayoutX(1);
        promptText.getTransforms().add(linesWrapper.promptTextScale);
        linesWrapper.promptContainer.getChildren().add(promptText);
        if (getSkinnable().isFocused() && ((IFXLabelFloatControl) getSkinnable()).isLabelFloat()) {
            promptText.setTranslateY(-Math.floor(textPane.getHeight()));
            linesWrapper.promptTextScale.setX(0.85);
            linesWrapper.promptTextScale.setY(0.85);
        }

        try {
            Object oldValue = promptNodeField.get(this);
            if (oldValue != null) {
                textPane.getChildren().remove(oldValue);
            }
            promptNodeField.set(this, promptText);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
