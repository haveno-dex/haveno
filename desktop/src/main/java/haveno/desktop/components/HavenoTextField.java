package haveno.desktop.components;

import com.jfoenix.controls.JFXTextField;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.Skin;

public class HavenoTextField extends JFXTextField {

    public HavenoTextField(String value) {
        super(value);
        GUIUtil.applyFilledStyle(this);
    }

    public HavenoTextField() {
        this(null);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXTextFieldSkinHavenoStyle<>(this, 0);
    }
}
