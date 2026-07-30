package haveno.desktop.components;

import com.jfoenix.controls.JFXTextField;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.Skin;

public class HavenoTextField extends JFXTextField {

    public HavenoTextField(String value) {
        super(value);
        GUIUtil.applyFilledStyle(this);

        // expose the floating prompt to screen readers
        promptTextProperty().addListener((o, oldValue, newValue) -> setAccessibleHelp(newValue));
    }

    public HavenoTextField() {
        this(null);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextFieldSkinHavenoStyle<>(this, 0);
    }
}
